package moe.lolosia.web.service.anime

import io.ktor.http.HttpMethod
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import moe.lolosia.web.api.get
import moe.lolosia.web.api.xhrRequest
import moe.lolosia.web.database.Database
import moe.lolosia.web.database.repository.AnimeSeriesRepository
import moe.lolosia.web.dto.ApiException
import moe.lolosia.web.service.files.ThumbnailCache
import moe.lolosia.web.service.files.ThumbnailGenerator
import moe.lolosia.web.settings.AppSettings
import moe.lolosia.web.util.fs.FileStore
import moe.lolosia.web.util.log.Log
import moe.lolosia.web.util.router.Injectable
import moe.lolosia.web.util.router.Service
import okio.Buffer
import kotlin.time.Clock

/**
 * 番剧**封面回源**：把 Bangumi 图床的封面图拉到本地 `work/resources/anime/cover/`，
 * 前端再经 `/res/resources/anime/cover/{bangumiId}.jpg?webp` 取（见 `doc/anime-timetable-design.md` §3.1 / §4.4）。
 *
 * Bangumi 图床在墙内打不开：中继模式下境外节点先备好、这里分批问「已经好了多少张」再逐张取回；
 * 直连模式则直接从 `cover` 里的原始 URL 下载。落盘后把 `anime_series.cover` 改写成本地路径，
 * 于是目录同步再跑也不会把本地封面覆盖回原始 URL（见 [AnimeMetaService] 的 upsert）。
 *
 * @author 洛洛希雅Lolosia
 */
@Injectable
@Service
class AnimeCoverService(
    settings: AppSettings,
    private val db: Database,
    private val store: FileStore,
    private val series: AnimeSeriesRepository,
) {

    private val log = Log.of("Anime")
    private val config = settings.anime

    private val relayEnabled: Boolean get() = config.bangumiRelayUrl.isNotBlank()
    private val relayBase: String get() = config.bangumiRelayUrl.trimEnd('/')
    private val relayHeaders: Map<String, String> get() = mapOf("X-Relay-Token" to config.bangumiRelayToken)

    /** 封面缩略图缓存（复用文件管理器那套：档位 + SHA-1 分片 + 元数据，见 [ThumbnailCache]）。 */
    private val thumbs: ThumbnailCache = ThumbnailCache(
        root = "$ANIME_COVER_CACHE_ROOT/v$ANIME_COVER_CACHE_VERSION",
        sizes = config.coverThumbSizes.filter { it > 0 }.ifEmpty { listOf(256) },
    )

    /** 挂到应用作用域周期回源；未启用时只记一条日志。 */
    fun start(scope: CoroutineScope) {
        if (!config.enabled || !config.bangumiEnabled || !config.coverEnabled) {
            log.info { "番剧封面回源未启用（anime.enabled / bangumiEnabled / coverEnabled）" }
            return
        }
        val minutes = config.coverPollMinutes.coerceAtLeast(1)
        log.info { "番剧封面回源已启动：interval=${minutes}min，batch=${config.coverBatchSize}" }
        scope.launch {
            while (isActive) {
                runCatching { syncOnce() }.onFailure { log.warn(it) { "回源番剧封面失败" } }
                delay(minutes * 60_000L)
            }
        }
    }

    /** 一轮：取一批还没本地的封面，逐张下载落盘，然后改写 `cover`。 */
    private suspend fun syncOnce() {
        val exec = runCatching { db.driver }.getOrNull() ?: return
        // 先捞一个候选池（远大于单轮批量），中继模式下再按「远端已就绪」筛，避免卡在未就绪的那几条上
        val candidates = series.findAllByPendingCover(exec, CANDIDATE_LIMIT).getOrElse { return }
        if (candidates.isEmpty()) return
        val ready: Set<Long>? = if (relayEnabled) (relayReadyIds() ?: return) else null
        val picked = candidates
            .filter { row -> ready == null || (row.bangumiId != null && row.bangumiId in ready) }
            .take(config.coverBatchSize.coerceAtLeast(1))
        var ok = 0
        for (row in picked) {
            val bangumiId = row.bangumiId ?: continue
            val bytes = runCatching { fetchCover(bangumiId, row.cover) }.getOrNull() ?: continue
            if (store.write("$ANIME_COVER_STORE_DIR/$bangumiId.jpg", bytes)) {
                series.executeUpdateCover(exec, row.id, "$ANIME_COVER_URL_PREFIX$bangumiId.jpg", Clock.System.now())
                ok++
            }
            delay(ANIME_COVER_FETCH_DELAY_MILLIS)
        }
        if (ok > 0) log.info { "番剧封面回源：本轮新增 $ok 张（候选 ${candidates.size}，就绪 ${ready?.size ?: "直连"}）" }
    }

    /** 中继已备好的封面 id 集合；问不到时返回 `null`（当作「都还没好」，本轮不下手）。 */
    private suspend fun relayReadyIds(): Set<Long>? = runCatching {
        val (base, path) = splitAbsoluteUrl(relayBase + ANIME_RELAY_COVER_STATUS_PATH)
        val data = xhrRequest(HttpMethod.Get, base, path, headers = relayHeaders).jsonObject
        data["ids"]?.jsonArray?.mapNotNull { it.jsonPrimitive.longOrNull }?.toSet()
    }.onFailure { log.warn(it) { "查询中继封面清单失败" } }.getOrNull()

    /** 取一张封面原图：中继模式走中继，直连模式直接取 `cover` 里的原始 URL。 */
    private suspend fun fetchCover(bangumiId: Long, originalUrl: String?): ByteArray {
        val base: String
        val path: String
        val headers: Map<String, String>
        if (relayEnabled) {
            val (b, p) = splitAbsoluteUrl("$relayBase$ANIME_RELAY_COVER_PATH/$bangumiId.jpg")
            base = b; path = p; headers = relayHeaders
        } else {
            val url = originalUrl?.takeIf { it.isNotBlank() } ?: throw ApiException(404, "番 $bangumiId 没有封面地址")
            val (b, p) = splitAbsoluteUrl(url)
            base = b; path = p; headers = mapOf("User-Agent" to config.bangumiUserAgent)
        }
        val buffer = Buffer()
        get<Flow<ByteArray>>(base, path, headers = headers, noLog = true).collect { buffer.write(it) }
        return buffer.readByteArray()
    }

    /**
     * 取一张封面的**缩略图**（最长边 [size]，缺省取最小档）。
     *
     * 命中 `.cache` 直接返回；没有就从回源大图 `work/resources/anime/cover/{id}.jpg` 解码缩放一次并落盘
     * （解码是纯 CPU 重活，挪到默认调度器上跑，别占着请求线程）。大图不存在时返回 `null`。
     */
    suspend fun thumbnail(bangumiId: Long, size: Int?): ByteArray? {
        val tiers = thumbs.orderedSizes
        val target = size?.takeIf { tiers.contains(it) } ?: tiers.firstOrNull() ?: return null
        val key = "$bangumiId.jpg"
        thumbs.read(key, target)?.let { return it }
        val source = store.read("$ANIME_COVER_STORE_DIR/$key") ?: return null
        return withContext(Dispatchers.Default) {
            val set = ThumbnailGenerator.generate(source, listOf(target), config.coverThumbQuality, null)
                ?: return@withContext null
            val item = set.items.firstOrNull() ?: return@withContext null
            thumbs.write(key, target, item.bytes)
            item.bytes
        }
    }

    private companion object {
        /** 候选池上限：一次最多扫多少条待回源记录（远大于单轮批量，避免被未就绪项卡住）。 */
        const val CANDIDATE_LIMIT: Long = 1_000L
    }
}
