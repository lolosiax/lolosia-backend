package moe.lolosia.web.service.dashboard

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import moe.lolosia.web.database.Database
import moe.lolosia.web.database.model.SysMetricSampleEntity
import moe.lolosia.web.database.repository.SysMetricSampleRepository
import moe.lolosia.web.dto.DashboardMetric
import moe.lolosia.web.dto.DashboardMetrics
import moe.lolosia.web.dto.DashboardSeries
import moe.lolosia.web.dto.DashboardSystem
import moe.lolosia.web.dto.DashboardVital
import moe.lolosia.web.settings.AppSettings
import moe.lolosia.web.util.log.Log
import moe.lolosia.web.util.platform.SystemMetricsReader
import moe.lolosia.web.util.platform.SystemMetricsSnapshot
import moe.lolosia.web.util.router.Injectable
import moe.lolosia.web.util.router.Service
import moe.lolosia.web.util.session.Context
import moe.lolosia.web.util.time.nowEpochMs
import kotlin.concurrent.Volatile
import kotlin.math.roundToInt
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.seconds

/**
 * 系统指标：后台按配置间隔采样主机（CPU / 内存 / 磁盘 / 网络 / 磁盘 IO），并给仪表板提供读取。
 *
 * 两层数据：
 * - **内存环形缓冲**：最近 1 小时的原始采样（默认 2s 一点），供 `1m` / `10m` / `1h` 三个范围 ——
 *   它们要的是「细」而不是「久」，从库里按秒拉再降采样太重；
 * - **落库**：每 60s 写一行 `sys_metric_sample`（保留若干天），供 `1d` / `1w`。
 *
 * 平台不支持采集时（Windows 目标）[start] 只记一条日志、[system] 返回 `available = false`。
 *
 * @author 洛洛希雅Lolosia
 */
@Injectable
@Service
class SystemMetricService(
    settings: AppSettings,
    private val db: Database,
    private val samples: SysMetricSampleRepository,
) {

    private val log = Log.of("Dashboard")
    private val config = settings.dashboard

    /** 采样间隔（秒），下限 2 —— 再密就对不上「1 分钟 30 点」的比例，也没必要。 */
    private val intervalSeconds: Int = config.sampleIntervalSeconds.coerceAtLeast(2)

    /** 内存环形缓冲容量：正好覆盖 1 小时。 */
    private val ringCapacity: Int = (3600 / intervalSeconds).coerceAtLeast(30)

    /** 落库节拍：每多少拍写一行（把秒级采样降成 60s 一行）。 */
    private val persistEvery: Int = (60 / intervalSeconds).coerceAtLeast(1)

    /** 清理节拍：每多少拍清一次过期数据（约 1 小时一次）。 */
    private val pruneEvery: Int = ringCapacity

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** 最近 1 小时的采样（不可变列表，整体替换；读侧无锁）。 */
    @Volatile
    private var history: List<Point> = emptyList()

    /** 启动后台采样循环；未启用或平台不支持时只记日志。 */
    fun start() {
        if (!config.enabled) {
            log.info { "仪表板未启用（dashboard.enabled=false）" }
            return
        }
        if (!SystemMetricsReader.supported) {
            log.info { "当前平台不支持系统指标采集（/proc），仪表板系统卡片将为空" }
            return
        }
        log.info { "系统指标采样已启用：间隔=${intervalSeconds}s 保留=${config.retentionDays}天" }
        scope.launch {
            var tick = 0
            while (isActive) {
                val snapshot = runCatching { SystemMetricsReader.sample() }.getOrNull()
                if (snapshot != null) {
                    history = (history + Point(nowEpochMs(), snapshot)).takeLast(ringCapacity)
                    if (tick % persistEvery == 0) persist(snapshot)
                }
                tick++
                if (tick % pruneEvery == 0) prune()
                delay(intervalSeconds.seconds)
            }
        }
    }

    /** 系统状态卡片：取最近一次采样；平台不支持时为 `available = false`。 */
    fun system(): DashboardSystem {
        if (!SystemMetricsReader.supported) return DashboardSystem(available = false)
        val last = history.lastOrNull() ?: return DashboardSystem(available = true)
        return DashboardSystem(available = true, updatedAt = last.at, vitals = vitalsOf(last.snapshot))
    }

    /**
     * 实时监控曲线：按 [range] 取对应窗口的采样，降采样到固定点数后返回四个指标。
     *
     * 未知的 [range] 回退 `1m`。库里读不到（未建表 / 库不可用）时对应范围返回空曲线而不是报错。
     */
    suspend fun metrics(ctx: Context, range: String?): DashboardMetrics {
        val spec = RANGES.firstOrNull { it.key == range } ?: RANGES.first()
        val points = if (spec.fromDatabase) dbPoints(ctx, spec) else ringPoints(spec)
        val down = downsample(points)
        val at = down.map { it.at }
        return DashboardMetrics(
            range = spec.key,
            at = at,
            metrics = listOf(
                DashboardMetric("cpu", "CPU", "%", listOf(DashboardSeries("CPU", "#b98fd6", down.map { it.snapshot.cpu }))),
                DashboardMetric("mem", "内存", "%", listOf(DashboardSeries("内存", "#7ba7f0", down.map { it.snapshot.mem }))),
                DashboardMetric(
                    "traffic", "出入流量", "B/s",
                    listOf(
                        DashboardSeries("入站", "#8fd6c2", down.map { it.snapshot.netIn }),
                        DashboardSeries("出站", "#f0c98f", down.map { it.snapshot.netOut }),
                    ),
                ),
                DashboardMetric(
                    "diskio", "磁盘 IO", "B/s",
                    listOf(
                        DashboardSeries("读", "#b98fd6", down.map { it.snapshot.diskRead }),
                        DashboardSeries("写", "#f0a3c4", down.map { it.snapshot.diskWrite }),
                    ),
                ),
            ),
        )
    }

    private fun vitalsOf(s: SystemMetricsSnapshot): List<DashboardVital> = listOf(
        DashboardVital("cpu", "CPU", s.cpu, "${s.cores} 核"),
        DashboardVital("mem", "内存", s.mem, "${gb(s.memUsed)} / ${gb(s.memTotal)} GB"),
        DashboardVital("disk", "存储", s.disk, "${gb(s.diskUsed)} / ${gb(s.diskTotal)} GB"),
    )

    private fun ringPoints(spec: RangeSpec): List<Point> {
        val since = nowEpochMs() - spec.durationMs
        return history.filter { it.at >= since }
    }

    private suspend fun dbPoints(ctx: Context, spec: RangeSpec): List<Point> {
        val since = Clock.System.now() - (spec.durationMs / 1000).seconds
        val db = runCatching { ctx.db }.getOrNull() ?: return emptyList()
        val rows = runCatching { samples.findAllBySampledAtAfterOrderBySampledAtAsc(db, since).getOrThrow() }
            .getOrElse { return emptyList() }
        return rows.map { Point(it.sampledAt.toEpochMilliseconds(), it.toSnapshot()) }
    }

    /** 均匀降采样到 [TARGET_POINTS] 点；点不够时原样返回。 */
    private fun downsample(points: List<Point>): List<Point> {
        if (points.size <= TARGET_POINTS) return points
        val out = ArrayList<Point>(TARGET_POINTS)
        for (i in 0 until TARGET_POINTS) {
            val from = i * points.size / TARGET_POINTS
            val to = ((i + 1) * points.size / TARGET_POINTS).coerceIn(from + 1, points.size)
            val slice = points.subList(from, to)
            out += Point(slice[slice.size / 2].at, average(slice))
        }
        return out
    }

    private fun average(slice: List<Point>): SystemMetricsSnapshot {
        val n = slice.size.toDouble()
        val last = slice.last().snapshot
        return SystemMetricsSnapshot(
            cpu = slice.sumOf { it.snapshot.cpu } / n,
            mem = slice.sumOf { it.snapshot.mem } / n,
            disk = slice.sumOf { it.snapshot.disk } / n,
            netIn = slice.sumOf { it.snapshot.netIn } / n,
            netOut = slice.sumOf { it.snapshot.netOut } / n,
            diskRead = slice.sumOf { it.snapshot.diskRead } / n,
            diskWrite = slice.sumOf { it.snapshot.diskWrite } / n,
            memUsed = last.memUsed,
            memTotal = last.memTotal,
            diskUsed = last.diskUsed,
            diskTotal = last.diskTotal,
            cores = last.cores,
        )
    }

    private suspend fun persist(snapshot: SystemMetricsSnapshot) {
        if (!db.connected) return
        runCatching {
            val now = Clock.System.now()
            samples.executeInsert(
                db.driver, snapshot.cpu, snapshot.mem, snapshot.disk,
                snapshot.netIn, snapshot.netOut, snapshot.diskRead, snapshot.diskWrite, now, now,
            )
        }.onFailure { log.warn(it) { "系统指标落库失败" } }
    }

    private suspend fun prune() {
        if (!db.connected) return
        val before = Clock.System.now() - config.retentionDays.coerceAtLeast(1).days
        runCatching { samples.deleteBySampledAtBefore(db.driver, before) }
            .onFailure { log.warn(it) { "系统指标清理失败" } }
    }

    private fun gb(bytes: Long): String = oneDecimal(bytes / 1024.0 / 1024.0 / 1024.0)

    private fun oneDecimal(value: Double): String = ((value * 10).roundToInt() / 10.0).toString()

    /** 一次采样：时间戳（epoch 毫秒）+ 指标快照。 */
    private data class Point(val at: Long, val snapshot: SystemMetricsSnapshot)

    /** 时间范围：窗口长度 + 数据来源（内存环形缓冲 or 数据库）。 */
    private data class RangeSpec(val key: String, val durationMs: Long, val fromDatabase: Boolean)

    private fun SysMetricSampleEntity.toSnapshot(): SystemMetricsSnapshot = SystemMetricsSnapshot(
        cpu = cpu,
        mem = mem,
        disk = disk,
        netIn = netIn,
        netOut = netOut,
        diskRead = diskRead,
        diskWrite = diskWrite,
        memUsed = 0,
        memTotal = 0,
        diskUsed = 0,
        diskTotal = 0,
        cores = 0,
    )

    private companion object {
        /** 每条曲线固定返回这么多点（前端曲线宽度固定，多了浪费、少了不够平滑）。 */
        const val TARGET_POINTS: Int = 30

        /** 支持的时间范围：前三个走内存环形缓冲（细），后两个走数据库（久）。 */
        val RANGES: List<RangeSpec> = listOf(
            RangeSpec("1m", 60_000L, false),
            RangeSpec("10m", 600_000L, false),
            RangeSpec("1h", 3_600_000L, false),
            RangeSpec("1d", 86_400_000L, true),
            RangeSpec("1w", 604_800_000L, true),
        )
    }
}
