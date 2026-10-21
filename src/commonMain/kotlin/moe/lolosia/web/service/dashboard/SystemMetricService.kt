package moe.lolosia.web.service.dashboard

import kotlinx.coroutines.*
import kotlinx.coroutines.sync.*
import moe.lolosia.web.database.Database
import moe.lolosia.web.database.repository.SysMetricSampleRepository
import moe.lolosia.web.settings.AppSettings
import moe.lolosia.web.util.log.Log
import moe.lolosia.web.util.router.Injectable
import moe.lolosia.web.util.router.Service
import moe.lolosia.web.util.session.Context
import moe.lolosia.web.util.system.SystemMetricsSnapshot
import moe.lolosia.web.util.system.readSystemMetrics
import moe.lolosia.web.util.time.PROCESS_START_MS
import moe.lolosia.web.util.time.nowEpochMs
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours

/**
 * 系统指标采样服务：后台按 `dashboard.sampleIntervalSeconds` 周期读一次系统计数器，
 * 对相邻两次求差得到占用率与速率，维护**内存环形缓冲**（保留最近约 1 小时），
 * 并把每分钟一条的降采样行落库、按 `dashboard.retainHours` 周期清理旧行。
 *
 * 采集走 [readSystemMetrics]：Linux 解析 `/proc`，Windows 走 Win32 / NT API；
 * 两边都采集不到时静默跳过，内存曲线与落库各自独立。
 * 数据库不可用时静默跳过落库（同 `ThumbnailService.syncSample` 的写法），不影响内存曲线。
 *
 * 对外两件事：[latest] 取最新一帧、[curve] 取六条曲线（CPU / 内存 / 入站 / 出站 / 读 / 写），
 * 已降采样到不超过 [TARGET_POINTS] 个点。
 *
 * @author 洛洛希雅Lolosia
 */
@Injectable
@Service
class SystemMetricService(
    private val db: Database,
    private val samples: SysMetricSampleRepository,
    settings: AppSettings,
) {

    /** 最新一帧系统指标。 */
    data class SystemMetricsLatest(
        val cpu: Double = 0.0,
        /** 逐核占用率（0~100），顺序与系统核编号一致；采集不到时为空。 */
        val cpuCores: List<Double> = emptyList(),
        val mem: Double = 0.0,
        val memUsed: Long = 0L,
        val memTotal: Long = 0L,
        val disk: Double = 0.0,
        val diskUsed: Long = 0L,
        val diskTotal: Long = 0L,
        val uptimeMs: Long = 0L,
    )

    /** 内部可变的最新计算结果（环形缓冲之外的单值状态）。 */
    private data class Current(
        val cpu: Double = 0.0,
        val cpuCores: List<Double> = emptyList(),
        val mem: Double = 0.0,
        val memUsed: Long = 0L,
        val memTotal: Long = 0L,
        val disk: Double = 0.0,
        val diskUsed: Long = 0L,
        val diskTotal: Long = 0L,
        val netIn: Double = 0.0,
        val netOut: Double = 0.0,
        val diskRead: Double = 0.0,
        val diskWrite: Double = 0.0,
    )

    private val log = Log.of("Dashboard")
    private val config = settings.dashboard
    /** 统计磁盘容量的挂载点；留空回退根分区 `/`（Windows 忽略）。 */
    private val diskPath = config.diskPath.ifBlank { "/" }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val intervalSeconds = config.sampleIntervalSeconds.coerceAtLeast(1)
    private val capacity = (3600 / intervalSeconds).coerceAtLeast(60)

    /** 采样协程写、请求协程读，共享可变状态一律串行访问。 */
    private val mutex = Mutex()

    private val cpuSeries = DoubleArray(capacity)
    private val memSeries = DoubleArray(capacity)
    private val netInSeries = DoubleArray(capacity)
    private val netOutSeries = DoubleArray(capacity)
    private val diskReadSeries = DoubleArray(capacity)
    private val diskWriteSeries = DoubleArray(capacity)

    private var head = 0
    private var size = 0

    private var last: SystemMetricsSnapshot? = null
    private var lastAtMs = 0L
    private var available = false
    private var current = Current()

    /** 启动后台采样；未启用时只记一条日志。 */
    fun start() {
        if (!config.enabled) {
            log.info { "系统指标采样未启用（dashboard.enabled=false）" }
            return
        }
        log.info { "系统指标采样已启用：间隔=${intervalSeconds}s 保留=${config.retainHours}h" }
        scope.launch { sampleLoop() }
    }

    /** 最新一帧；本机采集不到（Windows）时为 `null`。 */
    suspend fun latest(ctx: Context): SystemMetricsLatest? = mutex.withLock {
        if (!available) return null
        val c = current
        SystemMetricsLatest(
            cpu = c.cpu,
            cpuCores = c.cpuCores,
            mem = c.mem,
            memUsed = c.memUsed,
            memTotal = c.memTotal,
            disk = c.disk,
            diskUsed = c.diskUsed,
            diskTotal = c.diskTotal,
            uptimeMs = nowEpochMs() - PROCESS_START_MS,
        )
    }

    /**
     * 六条曲线：CPU / 内存 / 入站 / 出站 / 读 / 写。按 [range] 截取内存缓冲的窗口
     * （`30m` / `1h` / `6h` / `24h`，环形缓冲只覆盖约 1 小时，超出部分取到多少算多少），
     * 并降采样到不超过 [SystemMetricService.TARGET_POINTS] 个点。
     */
    suspend fun curve(ctx: Context, range: String): List<List<Double>> = mutex.withLock {
        val n = size
        if (n <= 0) return emptyList()
        val minutes = when (range) {
            "30m" -> 30
            "6h" -> 360
            "24h" -> 1440
            else -> 60
        }
        val orderStart = if (n < capacity) 0 else head
        val windowLen = minOf(n, (minutes * 60 / intervalSeconds).coerceAtLeast(1))
        val from = n - windowLen
        listOf(
            downsample(cpuSeries, orderStart, from, windowLen),
            downsample(memSeries, orderStart, from, windowLen),
            downsample(netInSeries, orderStart, from, windowLen),
            downsample(netOutSeries, orderStart, from, windowLen),
            downsample(diskReadSeries, orderStart, from, windowLen),
            downsample(diskWriteSeries, orderStart, from, windowLen),
        )
    }

    private suspend fun sampleLoop() {
        val persistEvery = (60 / intervalSeconds).coerceAtLeast(1)
        val cleanupEvery = (3600 / intervalSeconds).coerceAtLeast(1)
        var tick = 0
        while (scope.isActive) {
            runCatching { sampleOnce() }.onFailure { log.warn(it) { "系统指标采样失败" } }
            tick++
            if (tick % persistEvery == 0) runCatching { persist() }.onFailure { log.warn(it) { "写入系统指标失败" } }
            if (tick % cleanupEvery == 0) runCatching { cleanup() }.onFailure { log.warn(it) { "清理系统指标失败" } }
            delay(intervalSeconds * 1000L)
        }
    }

    private suspend fun sampleOnce() {
        val snap = readSystemMetrics(diskPath)
        val nowMs = nowEpochMs()
        mutex.withLock {
            if (snap == null) {
                available = false
                return
            }
            val prev = last
            val dt = if (prev != null && lastAtMs > 0L) (nowMs - lastAtMs) / 1000.0 else 0.0
            val cpu: Double
            val netIn: Double
            val netOut: Double
            val diskRead: Double
            val diskWrite: Double
            if (prev != null && dt > 0.0) {
                val dTotal = snap.cpuTotalTicks - prev.cpuTotalTicks
                val dBusy = snap.cpuBusyTicks - prev.cpuBusyTicks
                cpu = if (dTotal > 0L) (dBusy.toDouble() / dTotal * 100.0).coerceIn(0.0, 100.0) else current.cpu
                netIn = (snap.netInBytes - prev.netInBytes).coerceAtLeast(0L) / dt / MIB
                netOut = (snap.netOutBytes - prev.netOutBytes).coerceAtLeast(0L) / dt / MIB
                diskRead = (snap.diskReadBytes - prev.diskReadBytes).coerceAtLeast(0L) / dt / MIB
                diskWrite = (snap.diskWriteBytes - prev.diskWriteBytes).coerceAtLeast(0L) / dt / MIB
            } else {
                cpu = 0.0
                netIn = 0.0
                netOut = 0.0
                diskRead = 0.0
                diskWrite = 0.0
            }
            val mem = if (snap.memTotalBytes > 0L) snap.memUsedBytes.toDouble() / snap.memTotalBytes * 100.0 else 0.0
            val disk = if (snap.diskTotalBytes > 0L) snap.diskUsedBytes.toDouble() / snap.diskTotalBytes * 100.0 else 0.0
            // 逐核占用：只在前后两帧核数一致时求差，否则沿用上一帧（首次采样还没有差值）
            val cpuCores = if (prev != null && prev.cpuCoreTotalTicks.size == snap.cpuCoreTotalTicks.size) {
                snap.cpuCoreTotalTicks.indices.map { index ->
                    val coreTotal = snap.cpuCoreTotalTicks[index] - prev.cpuCoreTotalTicks[index]
                    val coreBusy = snap.cpuCoreBusyTicks[index] - prev.cpuCoreBusyTicks[index]
                    if (coreTotal > 0L) {
                        (coreBusy.toDouble() / coreTotal * 100.0).coerceIn(0.0, 100.0)
                    } else {
                        current.cpuCores.getOrElse(index) { 0.0 }
                    }
                }
            } else {
                current.cpuCores
            }

            last = snap
            lastAtMs = nowMs
            available = true
            current = Current(
                cpu = cpu,
                cpuCores = cpuCores,
                mem = mem,
                memUsed = snap.memUsedBytes,
                memTotal = snap.memTotalBytes,
                disk = disk,
                diskUsed = snap.diskUsedBytes,
                diskTotal = snap.diskTotalBytes,
                netIn = netIn,
                netOut = netOut,
                diskRead = diskRead,
                diskWrite = diskWrite,
            )
            cpuSeries[head] = cpu
            memSeries[head] = mem
            netInSeries[head] = netIn
            netOutSeries[head] = netOut
            diskReadSeries[head] = diskRead
            diskWriteSeries[head] = diskWrite
            head = (head + 1) % capacity
            if (size < capacity) size++
        }
    }

    /** 把最新一帧降采样落库；库不可用或采集不到时静默跳过。 */
    private suspend fun persist() {
        val data = mutex.withLock { if (available) current else null } ?: return
        val exec = runCatching { db.driver }.getOrNull() ?: return
        val now = Clock.System.now()
        runCatching {
            samples.executeInsert(
                exec, now, data.cpu, data.mem, data.memUsed, data.memTotal,
                data.disk, data.diskUsed, data.diskTotal,
                data.netIn, data.netOut, data.diskRead, data.diskWrite,
            ).getOrThrow()
        }.onFailure { log.warn(it) { "写入系统指标失败" } }
    }

    /** 清理早于 `retainHours` 的旧行。 */
    private suspend fun cleanup() {
        val exec = runCatching { db.driver }.getOrNull() ?: return
        val before = Clock.System.now() - config.retainHours.coerceAtLeast(1).hours
        runCatching { samples.executeDeleteBySampledAtBefore(exec, before).getOrThrow() }
            .onFailure { log.warn(it) { "清理系统指标失败" } }
    }

    /**
     * 把环形缓冲的一段按时间顺序取出并降采样到不超过 [TARGET_POINTS] 个点
     * （窗口本身不超过该点数时原样返回）。
     */
    private fun downsample(src: DoubleArray, orderStart: Int, from: Int, len: Int): List<Double> {
        if (len <= TARGET_POINTS) {
            return (0 until len).map { src[(orderStart + from + it) % capacity] }
        }
        val step = len.toDouble() / TARGET_POINTS
        return (0 until TARGET_POINTS).map { bucket ->
            val startIndex = (bucket * step).toInt()
            val endIndex = ((bucket + 1) * step).toInt().coerceAtMost(len).coerceAtLeast(startIndex + 1)
            var sum = 0.0
            for (i in startIndex until endIndex) sum += src[(orderStart + from + i) % capacity]
            sum / (endIndex - startIndex)
        }
    }

    companion object {
        /** 曲线降采样后的目标点数上限。 */
        const val TARGET_POINTS: Int = 30

        private const val MIB: Double = 1048576.0
    }
}
