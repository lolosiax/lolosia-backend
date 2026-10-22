package moe.lolosia.local.metrics

import kotlinx.coroutines.delay
import moe.lolosia.web.dto.LocalMetricsDto
import kotlin.concurrent.Volatile

/**
 * 指标采样：把累计计数器对上一次快照求差，得到瞬时占用率。
 *
 * 首次调用没有基准，会短暂等待后再采一次；仍然拿不到时 CPU 返回 `null`。
 */
class MetricsSampler {

    @Volatile
    private var previous: SystemMetricsSnapshot? = null

    suspend fun read(diskPath: String = ""): LocalMetricsDto {
        var before = previous
        var current = readSystemMetrics(diskPath)
        if (before == null) {
            // 首次调用没有基准：先用当前快照当基准，等一小会儿再采一次。
            before = current
            delay(FIRST_SAMPLE_DELAY_MS)
            current = readSystemMetrics(diskPath)
        }
        if (current == null) return LocalMetricsDto()
        previous = current
        val totalDelta = before?.let { current.cpuTotalTicks - it.cpuTotalTicks } ?: 0L
        val busyDelta = before?.let { current.cpuBusyTicks - it.cpuBusyTicks } ?: 0L
        val cpu = if (totalDelta > 0) busyDelta.toDouble() / totalDelta * 100 else null
        return LocalMetricsDto(
            cpu = cpu,
            mem = ratio(current.memUsedBytes, current.memTotalBytes),
            disk = ratio(current.diskUsedBytes, current.diskTotalBytes),
        )
    }

    private fun ratio(used: Long, total: Long): Double? =
        if (total > 0) used.toDouble() / total * 100 else null
}

/** 首次采样等待时长；足够让 CPU 计数器产生可观测的增量。 */
private const val FIRST_SAMPLE_DELAY_MS: Long = 150
