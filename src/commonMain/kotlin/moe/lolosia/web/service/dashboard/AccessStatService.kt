package moe.lolosia.web.service.dashboard

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import moe.lolosia.web.database.Database
import moe.lolosia.web.database.model.SysAccessStatEntity
import moe.lolosia.web.database.repository.SysAccessStatRepository
import moe.lolosia.web.dto.DashboardRankRow
import moe.lolosia.web.settings.AppSettings
import moe.lolosia.web.util.log.Log
import moe.lolosia.web.util.router.Injectable
import moe.lolosia.web.util.router.Service
import moe.lolosia.web.util.session.Context
import moe.lolosia.web.util.time.nowEpochMs
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * 访问统计：给「IP 排行 / Origin 排行」提供数据。
 *
 * 请求链路**不写库**：拦截器每次调用 [record] 只是在内存里按「小时桶 + IP + Host」累加计数，
 * 后台每 [FLUSH_INTERVAL] 把攒下的增量落库。这样热路径上只有一次加锁 + 计数，
 * 图片直链那种高频请求也不会把库打满。
 *
 * 桶取**小时**而不是分钟：排行是「谁访问得多」的长期视角，小时粒度足够，
 * 且窗口内的行数（桶数 × 独立 IP 数）小到可以直接取回内存里合并。
 *
 * 数据是纯派生的遥测：库不可用时这批增量直接丢弃（不值得为它做持久队列）。
 *
 * @author 洛洛希雅Lolosia
 */
@Injectable
@Service
class AccessStatService(
    settings: AppSettings,
    private val db: Database,
    private val stats: SysAccessStatRepository,
) {

    private val log = Log.of("Dashboard")
    private val config = settings.dashboard
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val mutex = Mutex()
    private val pending = mutableMapOf<Key, Long>()

    /** 记一次请求。**必须**在请求协程里调用（会短暂持有互斥锁）。 */
    suspend fun record(ip: String?, host: String?) {
        val clientIp = ip?.trim()?.takeIf { it.isNotEmpty() }?.take(MAX_IP) ?: return
        val bucket = nowEpochMs() / BUCKET_MS * BUCKET_MS
        val key = Key(bucket, clientIp, host?.trim().orEmpty().take(MAX_HOST))
        mutex.withLock { pending[key] = (pending[key] ?: 0L) + 1L }
    }

    /** 启动后台落库与清理循环；未启用时什么都不做。 */
    fun start() {
        if (!config.enabled) return
        scope.launch {
            var tick = 0
            while (isActive) {
                delay(FLUSH_INTERVAL)
                flush()
                tick++
                if (tick % PRUNE_EVERY == 0) prune()
            }
        }
    }

    /** IP 排行：保留窗口内按客户端 IP 求和，降序取前 [limit] 名。 */
    suspend fun topIps(ctx: Context, limit: Int): List<DashboardRankRow> =
        rank(ctx, limit) { it.ip }

    /** Origin 排行：口径是 Host 头（自建服务没有 CDN，只能给这个近似维度）。 */
    suspend fun topHosts(ctx: Context, limit: Int): List<DashboardRankRow> =
        rank(ctx, limit) { it.host }

    private suspend fun rank(
        ctx: Context,
        limit: Int,
        selector: (SysAccessStatEntity) -> String,
    ): List<DashboardRankRow> {
        val db = runCatching { ctx.db }.getOrNull() ?: return emptyList()
        val rows = runCatching { stats.findAllByBucketAtAfter(db, since()).getOrThrow() }.getOrElse { return emptyList() }
        val totals = mutableMapOf<String, Long>()
        for (row in rows) {
            val key = selector(row)
            if (key.isEmpty()) continue
            totals[key] = (totals[key] ?: 0L) + row.hits
        }
        return totals.entries
            .sortedByDescending { it.value }
            .take(limit)
            .map { DashboardRankRow(it.key, it.value) }
    }

    private fun since(): Instant = Clock.System.now() - config.accessRetentionDays.coerceAtLeast(1).days

    private suspend fun flush() {
        val batch = mutex.withLock {
            if (pending.isEmpty()) return
            val copy = pending.toMap()
            pending.clear()
            copy
        }
        if (!db.connected) return
        val now = Clock.System.now()
        for ((key, delta) in batch) {
            val bucket = Instant.fromEpochMilliseconds(key.bucketAt)
            runCatching {
                val existing = stats.findOneByBucketAtAndIpAndHost(db.driver, bucket, key.ip, key.host).getOrThrow()
                if (existing == null) {
                    stats.executeInsert(db.driver, bucket, key.ip, key.host, delta, now, now).getOrThrow()
                } else {
                    stats.executeUpdateHitsById(db.driver, existing.id, existing.hits + delta, now).getOrThrow()
                }
            }.onFailure { log.warn(it) { "访问统计落库失败" } }
        }
    }

    private suspend fun prune() {
        if (!db.connected) return
        val before = Clock.System.now() - config.accessRetentionDays.coerceAtLeast(1).days
        runCatching { stats.deleteByBucketAtBefore(db.driver, before) }
            .onFailure { log.warn(it) { "访问统计清理失败" } }
    }

    /** 聚合键：小时桶 + IP + Host。 */
    private data class Key(val bucketAt: Long, val ip: String, val host: String)

    private companion object {
        /** 统计桶大小：1 小时。 */
        const val BUCKET_MS: Long = 3_600_000L
        const val MAX_IP: Int = 64
        const val MAX_HOST: Int = 255

        /** 落库间隔。 */
        val FLUSH_INTERVAL = 10.seconds

        /** 每多少次落库清理一次过期数据（10s × 2160 ≈ 6 小时）。 */
        const val PRUNE_EVERY: Int = 2160
    }
}
