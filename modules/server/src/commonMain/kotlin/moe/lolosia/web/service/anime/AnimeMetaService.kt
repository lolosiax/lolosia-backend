package moe.lolosia.web.service.anime

import io.github.smyrgeorge.sqlx4k.QueryExecutor
import io.ktor.http.HttpMethod
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.*
import moe.lolosia.web.api.*
import moe.lolosia.web.database.Database
import moe.lolosia.web.database.model.ALIAS_SOURCE_BANGUMI
import moe.lolosia.web.database.model.STATUS_AIRING
import moe.lolosia.web.database.repository.*
import moe.lolosia.web.dto.ApiException
import moe.lolosia.web.service.CacheManager
import moe.lolosia.web.settings.AppSettings
import moe.lolosia.web.util.log.Log
import moe.lolosia.web.util.router.Injectable
import moe.lolosia.web.util.router.Service
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/**
 * 番剧**目录同步**服务：定时拉 Bangumi 每日放送，upsert 番剧与别名（见 `doc/anime-timetable-design.md` §4.3）。
 *
 * 与采集链路解耦：目录拉取失败**不影响** RSS 采集（只是别名暂时不更新）。Bangumi 会拒无 UA
 * 的请求，故一律带 [AppSettings.AnimeConfig.bangumiUserAgent]。
 *
 * @author 洛洛希雅Lolosia
 */
@Injectable
@Service
class AnimeMetaService(
    settings: AppSettings,
    private val db: Database,
    private val cacheManager: CacheManager,
    private val series: AnimeSeriesRepository,
    private val aliasRepo: AnimeSeriesAliasRepository,
) {

    private val log = Log.of("Anime")
    private val config = settings.anime

    /** 配了中继地址就走中继取数（境外节点），否则直连 [AppSettings.AnimeConfig.bangumiCalendarUrl]。 */
    private val relayEnabled: Boolean get() = config.bangumiRelayUrl.isNotBlank()

    private val relayBase: String get() = config.bangumiRelayUrl.trimEnd('/')

    private val relayHeaders: Map<String, String> get() = mapOf("X-Relay-Token" to config.bangumiRelayToken)

    /** 挂到应用作用域周期同步；未启用或未开 Bangumi 时只记一条日志。 */
    fun start(scope: CoroutineScope) {
        if (!config.enabled || !config.bangumiEnabled) {
            log.info { "番剧目录同步未启用（anime.enabled / bangumiEnabled）" }
            return
        }
        val hours = config.metaRefreshHours.coerceAtLeast(1)
        val intervalMs = hours * 3600_000L
        // TTL 取 3 倍间隔（默认 24h → 3 天）：进程停超 TTL 后缓存过期，再启动会立刻同步
        val ttl = (hours * 3).hours
        // 目录刷新是**低频 + 对方限流**：按「挂钟时间」持久化在 sys_cache，而不是以进程启动时间为准。
        // 于是频繁重启（部署）不会重复拉 Bangumi，重启也不重置计时。
        log.info { "番剧目录同步已启动：interval=${hours}h（按挂钟时间，记录于 sys_cache）" }
        scope.launch {
            // 启动时从 sys_cache 继承排期（读不到 = 从未同步 / 已过期 / 库不可用 → 立即同步）
            var nextAtMs = nextRefreshAt() ?: 0L
            var fails = failCount()
            while (isActive) {
                val nowMs = Clock.System.now().toEpochMilliseconds()
                if (nowMs < nextAtMs) {
                    // 睡到到期；单次最多睡 1 小时就醒来重判（缓存被手工改动 / 系统时钟跳变时能及时跟上）
                    delay((nextAtMs - nowMs).coerceAtMost(MAX_SLEEP_MILLIS))
                    continue
                }
                val ok = runCatching { syncNow() }
                    .onFailure { log.warn(it) { "同步番剧目录失败" } }
                    .isSuccess
                fails = if (ok) 0 else fails + 1
                // 成功 → 睡一整个 interval；失败 → **退避重试**（60s 起指数增长、封顶 interval），
                // 免得对方长时间不可达时每 60s 空拉一次（刷日志 + 无谓请求）
                val waitMs = if (ok) intervalMs else backoffMs(fails, intervalMs)
                nextAtMs = Clock.System.now().toEpochMilliseconds() + waitMs
                markSchedule(nextAtMs, fails, ttl)
            }
        }
    }

    /**
     * 同步一次番剧目录，返回 upsert 的番剧数。
     *
     * 配了 [AppSettings.AnimeConfig.bangumiRelayUrl] 就走中继（境外取数），否则直连。
     */
    suspend fun syncNow(): Int =
        if (relayEnabled) syncViaRelay() else applyCalendar(fetchCalendarOrThrow())

    /** 手动「同步目录」：中继模式只发通知（秒回，结果由后台周期任务取回）；直连模式同步完成才返回。 */
    suspend fun requestRefresh(): String =
        if (relayEnabled) {
            notifyRelay()
            "已通知中继拉取，稍后自动取回目录"
        } else {
            "同步完成：${syncNow()} 部"
        }

    /**
     * 中继模式的一次同步：**先通知、隔一会再来取结果**。
     *
     * 通知本身立刻返回（中继此刻不出站），出站发生在中继侧的延迟 + 抖动之后；本协程再等
     * [AppSettings.AnimeConfig.bangumiRelayWaitSeconds] 秒才开始轮询结果。这样「入站请求」与
     * 「出站请求」在时间上错开，也全程没有常连通道。
     */
    private suspend fun syncViaRelay(): Int {
        notifyRelay()
        delay(config.bangumiRelayWaitSeconds.coerceAtLeast(0) * 1000L)
        val deadline = Clock.System.now() + RELAY_POLL_BUDGET
        while (true) {
            when (val out = fetchRelayResult()) {
                is RelayResult.Ready -> return applyCalendar(out.calendar)
                is RelayResult.Failed -> throw ApiException(502, "中继拉取 Bangumi 失败：${out.message}")
                RelayResult.Pending -> Unit
            }
            if (Clock.System.now() >= deadline) throw ApiException(504, "中继尚未取回番剧目录")
            delay(ANIME_RELAY_POLL_MILLIS)
        }
    }

    /** 通知中继登记一次拉取任务（不入参、不等出站）。走自定义通道（msgpack+gzip）。 */
    private suspend fun notifyRelay() {
        val (base, path) = splitAbsoluteUrl(relayBase + ANIME_RELAY_NOTIFY_PATH)
        xhrRequest(HttpMethod.Post, base, path, headers = relayHeaders)
    }

    /** 取一次中继结果。走自定义通道（msgpack+gzip）。 */
    private suspend fun fetchRelayResult(): RelayResult {
        val (base, path) = splitAbsoluteUrl(relayBase + ANIME_RELAY_RESULT_PATH)
        val data = xhrRequest(HttpMethod.Get, base, path, headers = relayHeaders).jsonObject
        return when (data.str("status")) {
            "ready" -> RelayResult.Ready(
                data["calendar"]?.jsonArray ?: throw ApiException(502, "中继结果缺少 calendar 字段")
            )
            "error" -> RelayResult.Failed(data.str("error") ?: "unknown")
            else -> RelayResult.Pending
        }
    }

    private suspend fun fetchCalendarOrThrow(): JsonArray =
        fetchCalendar() ?: throw ApiException(502, "拉取 Bangumi 每日放送失败")

    /** 把每日放送数组 upsert 进库，返回更新的番剧数。 */
    private suspend fun applyCalendar(days: JsonArray): Int {
        val exec = driverOrNull() ?: throw ApiException(503, "数据库暂不可用，稍后再试")
        val now = Clock.System.now()
        var count = 0
        for (day in days) {
            val weekday = day.jsonObject["weekday"]?.jsonObject?.int("id") ?: continue
            val items = day.jsonObject["items"]?.jsonArray ?: continue
            for (item in items) {
                if (runCatching { upsert(exec, item.jsonObject, weekday, now) }.getOrDefault(false)) count++
            }
        }
        log.info { "番剧目录同步完成：$count 部" }
        return count
    }

    /** upsert 一部番及其别名；返回是否有更新。 */
    private suspend fun upsert(exec: QueryExecutor, item: JsonObject, weekday: Int, now: Instant): Boolean {
        val bangumiId = item.int("id")?.toLong() ?: return false
        val nameOriginal = item.str("name")
        val nameCn = item.str("name_cn")?.takeIf { it.isNotBlank() }
        val title = nameCn ?: nameOriginal ?: return false
        val cover = item["images"]?.jsonObject?.let { it.str("large") ?: it.str("common") }
        val airDate = item.str("air_date")
        val siteUrl = item.str("url")
        val rating = item["rating"]?.jsonObject?.dbl("score")
        val summary = item.str("summary")

        val existing = series.findOneByBangumiId(exec, bangumiId).getOrNull()
        val id = if (existing == null) {
            series.executeInsert(
                exec, bangumiId, title, nameOriginal, cover, weekday, null, airDate, null,
                siteUrl, rating, summary, STATUS_AIRING, now, now,
            )
            series.findOneByBangumiId(exec, bangumiId).getOrNull()?.id ?: return false
        } else {
            series.executeUpdateById(
                exec, existing.id, title, nameOriginal,
                // 已回源到本地的封面不被目录同步覆盖回原始 URL（由 AnimeCoverService 负责回源）
                if (isLocalAnimeCover(existing.cover)) existing.cover else cover,
                weekday, existing.airTime, airDate,
                existing.season, siteUrl, rating, summary, existing.status, now,
            )
            existing.id
        }

        aliasRepo.deleteBySeriesId(exec, id)
        for (alias in listOfNotNull(nameOriginal, nameCn)) {
            val norm = AnimeTitleMatcher.normalize(alias)
            if (norm.isNotBlank()) aliasRepo.executeInsert(exec, id, alias, norm, ALIAS_SOURCE_BANGUMI, now)
        }
        return true
    }

    /** 拉 Bangumi 每日放送（原始 JSON 数组）。 */
    private suspend fun fetchCalendar(): JsonArray? = runCatching {
        val (base, path) = splitAbsoluteUrl(config.bangumiCalendarUrl)
        get<JsonElement>(
            base, path, rawResponse = true,
            headers = mapOf("User-Agent" to config.bangumiUserAgent),
        ).jsonArray
    }.onFailure { log.warn(it) { "拉取 Bangumi 每日放送失败：${config.bangumiCalendarUrl}" } }.getOrNull()

    private fun driverOrNull(): QueryExecutor? = runCatching { db.driver }.getOrNull()

    private val cache: CacheManager.Accessor get() = cacheManager[ANIME_CATALOG_CACHE_PATH]

    /** 读「下次该刷新的时刻」（epoch 毫秒）；读不到（从未同步 / 已过期 / 库不可用）返回 `null`。 */
    private suspend fun nextRefreshAt(): Long? =
        runCatching { cache.get(ANIME_CATALOG_NEXT_REFRESH)?.toLongOrNull() }.getOrNull()

    /** 读连续失败次数（用于退避）；读不到按 0。 */
    private suspend fun failCount(): Int =
        runCatching { cache.get(ANIME_CATALOG_FAIL_COUNT)?.toIntOrNull() ?: 0 }.getOrNull() ?: 0

    /** 记下排期（下次刷新时刻 + 连续失败数）；写失败只记日志，不影响本进程的节流。 */
    private suspend fun markSchedule(nextAtMs: Long, fails: Int, ttl: Duration) {
        runCatching {
            cache.put(ANIME_CATALOG_NEXT_REFRESH, nextAtMs.toString(), ttl)
            cache.put(ANIME_CATALOG_FAIL_COUNT, fails.toString(), ttl)
        }.onFailure { log.warn(it) { "写入番剧目录排期失败" } }
    }

    /** 失败退避：`60s * 2^(fails-1)`，封顶 [capMs]（默认上限即正常间隔）。 */
    private fun backoffMs(fails: Int, capMs: Long): Long =
        (ANIME_RETRY_MILLIS shl (fails - 1).coerceIn(0, 20)).coerceAtMost(capMs)

    /** 中继取数的三态结果。 */
    private sealed interface RelayResult {
        /** 已取回：本项为 Bangumi 每日放送数组。 */
        data class Ready(val calendar: JsonArray) : RelayResult

        /** 中继侧拉取失败，[message] 为远程错误信息。 */
        data class Failed(val message: String) : RelayResult

        /** 中继还没拉到（本次通知尚未到点），稍后再问。 */
        data object Pending : RelayResult
    }

    private companion object {
        /** 挂钟排期的单次最长睡眠：到期前最多每小时醒一次重判，便于响应缓存被改 / 时钟跳变。 */
        const val MAX_SLEEP_MILLIS: Long = 3600_000L

        /** 中继模式下一次同步的总预算：等待 + 轮询结果的上限。 */
        val RELAY_POLL_BUDGET: Duration = 10.minutes
    }
}

/** 取字符串字段；JSON null 或缺失返回 `null`。 */
private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

/** 取整数字段；缺失或非数返回 `null`。 */
private fun JsonObject.int(key: String): Int? = (this[key] as? JsonPrimitive)?.intOrNull

/** 取浮点字段；缺失或非数返回 `null`。 */
private fun JsonObject.dbl(key: String): Double? = (this[key] as? JsonPrimitive)?.doubleOrNull
