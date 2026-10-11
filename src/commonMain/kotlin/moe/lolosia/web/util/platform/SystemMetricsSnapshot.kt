package moe.lolosia.web.util.platform

/**
 * 一次系统指标采样：CPU / 内存 / 磁盘使用率与网络 / 磁盘吞吐速率。
 *
 * 使用率是百分比（0~100），速率是字节/秒；`used` / `total` 是字节数（用于展示 `3.2 / 8.0 GB`）。
 * 由 [SystemMetricsReader] 产出，最终存进 `sys_metric_sample` 或直接在内存环形缓冲里滚。
 *
 * @author 洛洛希雅Lolosia
 */
data class SystemMetricsSnapshot(
    /** CPU 使用率（%）。 */
    val cpu: Double,
    /** 内存使用率（%）。 */
    val mem: Double,
    /** 根分区使用率（%）。 */
    val disk: Double,
    /** 入站速率（字节/秒）。 */
    val netIn: Double,
    /** 出站速率（字节/秒）。 */
    val netOut: Double,
    /** 磁盘读速率（字节/秒）。 */
    val diskRead: Double,
    /** 磁盘写速率（字节/秒）。 */
    val diskWrite: Double,
    val memUsed: Long,
    val memTotal: Long,
    val diskUsed: Long,
    val diskTotal: Long,
    /** CPU 逻辑核数（展示用）。 */
    val cores: Int,
)
