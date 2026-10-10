package moe.lolosia.web.service.dashboard

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.*
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import moe.lolosia.web.database.Database
import moe.lolosia.web.database.model.SysAccessStatEntity
import moe.lolosia.web.database.repository.SysAccessStatRepository
import moe.lolosia.web.settings.AppSettings
import moe.lolosia.web.util.log.Log
import moe.lolosia.web.util.router.Injectable
import moe.lolosia.web.util.router.Service
import moe.lolosia.web.util.session.Context
import moe.lolosia.web.util.time.nowEpochMs
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant

/**
 * 访问统计服务：把「一次请求」在内存里按「小时桶 + ip + host」聚合，每 60 秒落库一次。
 *
 * **线程安全**：请求协程只往 [channel] 里 `trySend`（非阻塞），**单独一个消费协程**从 channel
 * 取事件、独占 [counts] 这个可变 Map；`withTimeoutOrNull(1000)` 保证没有事件时也能周期性检查落库时机。
 * 落库对每条走单条 `executeUpsert`（`@Query` 不支持 `IN`，批量只能循环单条），完成后清空 Map。
 *
 * 小时桶是本地时区的 `yyyy-MM-ddTHH`，字典序等价时间序；库不可用时本批不落库、也不清空
 * （纯派生数据，等多一轮再写）。
 *
 * @author 洛洛希雅Lolosia
 */
@Injectable
@Service
class AccessStatService(
    private val db: Database,
    private val stats: SysAccessStatRepository,
    settings: AppSettings,
) {

    private val log = Log.of("Dashboard")
    private val config = settings.dashboard
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val channel = Channel<Pair<String, String>>(Channel.UNLIMITED)

    /** 仅由消费协程访问：`(小时桶, ip, host) -> hits`。 */
    private val counts = mutableMapOf<Triple<String, String, String>, Long>()

    /** 记一次访问；未启用直接返回，非阻塞。 */
    fun record(ip: String, host: String) {
        if (!config.enabled) return
        channel.trySend(ip to host)
    }

    /** 启动消费协程；未启用时只记一条日志。 */
    fun start() {
        if (!config.enabled) {
            log.info { "访问统计未启用（dashboard.enabled=false）" }
            return
        }
        log.info { "访问统计已启用" }
        scope.launch { consumeLoop() }
    }

    /** 最近 7 天命中次数最多的 IP。 */
    suspend fun topIps(ctx: Context, limit: Int): List<Pair<String, Long>> =
        recentRows(ctx)
            .groupBy { it.ip }
            .map { (key, rows) -> key to rows.sumOf { it.hits } }
            .sortedByDescending { it.second }
            .take(limit.coerceAtLeast(1))

    /** 最近 7 天命中次数最多的 Host。 */
    suspend fun topHosts(ctx: Context, limit: Int): List<Pair<String, Long>> =
        recentRows(ctx)
            .groupBy { it.host }
            .map { (key, rows) -> key to rows.sumOf { it.hits } }
            .sortedByDescending { it.second }
            .take(limit.coerceAtLeast(1))

    private suspend fun consumeLoop() {
        var lastFlush = nowEpochMs()
        while (scope.isActive) {
            val event = withTimeoutOrNull(1000L) { channel.receive() }
            if (event != null) {
                val (ip, host) = event
                val key = Triple(currentBucket(Clock.System.now()), ip, host)
                counts[key] = (counts[key] ?: 0L) + 1L
            }
            val now = nowEpochMs()
            if (now - lastFlush >= FLUSH_INTERVAL_MS) {
                flush()
                lastFlush = now
            }
        }
    }

    /** 把当前 Map 逐条 upsert 落库后清空；库不可用时保留本批（下一轮再写）。 */
    private suspend fun flush() {
        if (counts.isEmpty()) return
        val exec = runCatching { db.driver }.getOrNull() ?: return
        val snapshot = counts.toMap()
        val now = Clock.System.now()
        for ((key, hits) in snapshot) {
            runCatching { stats.executeUpsert(exec, key.first, key.second, key.third, hits, now).getOrThrow() }
                .onFailure { log.warn(it) { "写入访问统计失败" } }
        }
        counts.clear()
    }

    private suspend fun recentRows(ctx: Context): List<SysAccessStatEntity> {
        val since = currentBucket(Clock.System.now() - RETENTION_DAYS.days)
        return runCatching { stats.findAllByHourBucketAfter(ctx.db, since).getOrThrow() }
            .getOrNull()
            .orEmpty()
    }

    /** 把时刻折算成本地时区的小时桶字符串 `yyyy-MM-ddTHH`。 */
    private fun currentBucket(instant: Instant): String {
        val t = instant.toLocalDateTime(TimeZone.currentSystemDefault())
        return "${t.year}-${(t.month.ordinal + 1).pad2()}-${t.day.pad2()}T${t.hour.pad2()}"
    }

    private fun Int.pad2(): String = if (this < 10) "0$this" else "$this"

    private companion object {
        /** 落库间隔（毫秒），每分钟一批。 */
        const val FLUSH_INTERVAL_MS: Long = 60_000L

        /** top-ip / top-host 的统计窗口（天）。 */
        const val RETENTION_DAYS: Int = 7
    }
}
