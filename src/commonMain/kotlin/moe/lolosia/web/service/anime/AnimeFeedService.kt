package moe.lolosia.web.service.anime

import io.github.smyrgeorge.sqlx4k.QueryExecutor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.*
import moe.lolosia.web.api.*
import moe.lolosia.web.database.Database
import moe.lolosia.web.database.model.MATCH_MATCHED
import moe.lolosia.web.database.model.MATCH_UNMATCHED
import moe.lolosia.web.database.model.RELEASE_SOURCE_MIOBT
import moe.lolosia.web.database.repository.*
import moe.lolosia.web.dto.ApiException
import moe.lolosia.web.service.SseService
import moe.lolosia.web.settings.AppSettings
import moe.lolosia.web.util.log.Log
import moe.lolosia.web.util.router.Injectable
import moe.lolosia.web.util.router.Service
import okio.Buffer
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days

/**
 * 番组**采集**服务：定时拉 MioBT 系发布站 RSS，解析成发布条目、匹配番剧、落库去重，
 * 命中用户关注时经 SSE 实时推送（见 `doc/anime-timetable-design.md` §4.1 / §5）。
 *
 * 三条硬边界：
 * - **库不可用不崩**：取执行句柄走 [driverOrNull]，拿不到就跳过本轮（失败只记日志）；
 * - **整条容错**：单条解析 / 插入失败不影响同轮其它条目；
 * - **单实例**：与缩略图同口径，只在一台机器上开 `anime.enabled`，否则会重复广播。
 *
 * @author 洛洛希雅Lolosia
 */
@Injectable
@Service
class AnimeFeedService(
    settings: AppSettings,
    private val db: Database,
    private val sse: SseService,
    private val series: AnimeSeriesRepository,
    private val aliasRepo: AnimeSeriesAliasRepository,
    private val releaseRepo: AnimeReleaseRepository,
    private val subscriptionRepo: AnimeSubscriptionRepository,
) {

    private val log = Log.of("Anime")
    private val config = settings.anime

    /** 挂到应用作用域按间隔轮询；未启用时只记一条日志。 */
    fun start(scope: CoroutineScope) {
        if (!config.enabled) {
            log.info { "番组表未启用（anime.enabled=false）" }
            return
        }
        val interval = config.pollIntervalSeconds.coerceAtLeast(ANIME_MIN_INTERVAL_SECONDS)
        log.info { "番组表采集已启动：rss=${config.rssUrl}, interval=${interval}s" }
        scope.launch {
            while (isActive) {
                // 采集是**高频**定时器：以进程启动时间为准（不持久化）；
                // 失败就短重试，别干等一整个周期
                val ok = runCatching { pollOnce() }
                    .onFailure { log.error(it) { "拉取番组更新失败" } }
                    .isSuccess
                delay(if (ok) interval * 1000L else ANIME_RETRY_MILLIS)
            }
        }
    }

    /** 立即拉取一轮（排障 / 手动触发），返回本轮新增条数。 */
    suspend fun pollNow(): Int = pollOnce()

    /** 一轮采集。 */
    private suspend fun pollOnce(): Int {
        val exec = driverOrNull() ?: throw ApiException(503, "数据库暂不可用，稍后再试")
        // 拉取失败要抛出来：手动触发时能回报错误，轮询侧也能据此短重试
        val xml = fetchRss() ?: throw ApiException(502, "拉取 RSS 失败")
        val items = AnimeRssParser.parse(xml)
        if (items.isEmpty()) return 0

        val categories = config.includeCategories.toSet()
        val aliases = loadAliases(exec)
        val now = Clock.System.now()
        var inserted = 0

        for (item in items) {
            if (categories.isNotEmpty() && item.category.isNotBlank() && item.category !in categories) continue
            val seen = releaseRepo.countByGuid(exec, item.guid).getOrElse { continue } > 0
            if (seen) continue

            val episodeNo = AnimeTitleMatcher.extractEpisode(item.title)
            val seriesId = AnimeTitleMatcher.match(item.title, aliases)
            val state = if (seriesId != null) MATCH_MATCHED else MATCH_UNMATCHED
            val affected = releaseRepo.executeInsert(
                exec, item.guid, RELEASE_SOURCE_MIOBT, seriesId, item.groupName, item.title,
                episodeNo, item.category, item.torrentUrl, item.detailUrl, state, item.publishedAt, now,
            ).getOrElse { -1L }
            if (affected < 0) continue
            inserted++
            if (seriesId != null) notify(exec, seriesId, item, episodeNo)
        }

        cleanup(exec)
        if (inserted > 0) log.info { "本轮新增 $inserted 条番组发布" }
        return inserted
    }

    /** 命中关注则广播：订阅了该番、且（关注任意组 或 组名一致）的用户才收到。 */
    private suspend fun notify(exec: QueryExecutor, seriesId: Long, item: AnimeRssParser.RawRelease, episodeNo: Int?) {
        val subs = subscriptionRepo.findAllBySeriesId(exec, seriesId).getOrElse { return }
        val targets = subs
            .filter { it.groupName.isBlank() || it.groupName == item.groupName }
            .map { it.userId }
            .toSet()
        if (targets.isEmpty()) return

        val seriesTitle = series.findOneById(exec, seriesId).getOrNull()?.title.orEmpty()
        val payload = buildJsonObject {
            put("seriesId", seriesId)
            put("seriesTitle", seriesTitle)
            put("groupName", item.groupName)
            put("title", item.title)
            put("episodeNo", episodeNo?.let { JsonPrimitive(it) } ?: JsonNull)
            put("torrentUrl", item.torrentUrl.orEmpty())
            put("detailUrl", item.detailUrl.orEmpty())
            put("publishedAt", JsonPrimitive(item.publishedAt?.toEpochMilliseconds() ?: 0L))
        }
        sse.broadcast(ANIME_RELEASE_EVENT, payload) { userId -> userId in targets }
    }

    /** 从别名字表构建匹配弹药（每轮加载一次，量级小）。 */
    private suspend fun loadAliases(exec: QueryExecutor): List<AnimeTitleMatcher.AliasRef> =
        aliasRepo.findAllByAlive(exec).getOrElse { emptyList() }
            .filter { it.aliasNorm.isNotBlank() }
            .map { AnimeTitleMatcher.AliasRef(it.seriesId, it.aliasNorm) }

    /** 清理 `retainDays` 之外的老发布条目。 */
    private suspend fun cleanup(exec: QueryExecutor) {
        val days = config.retainDays
        if (days <= 0) return
        val cutoff = Clock.System.now() - days.days
        releaseRepo.executeDeleteBefore(exec, cutoff).onFailure { log.warn(it) { "清理历史发布失败" } }
    }

    /** 拉 RSS 原始文本（带 UA；http 明文亦可）。 */
    private suspend fun fetchRss(): String? = runCatching {
        val (base, path) = splitAbsoluteUrl(config.rssUrl)
        val buffer = Buffer()
        get<Flow<ByteArray>>(base, path, headers = mapOf("User-Agent" to config.bangumiUserAgent), noLog = true)
            .collect { buffer.write(it) }
        buffer.readByteArray().decodeToString()
    }.onFailure { log.warn(it) { "拉取 RSS 失败：${config.rssUrl}" } }.getOrNull()

    private fun driverOrNull(): QueryExecutor? = runCatching { db.driver }.getOrNull()
}
