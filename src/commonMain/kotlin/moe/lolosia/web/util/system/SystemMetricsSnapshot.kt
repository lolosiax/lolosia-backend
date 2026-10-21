package moe.lolosia.web.util.system

/**
 * 一份系统指标的**原始计数快照**。
 *
 * 全部是**累计计数器**（CPU 节拍、网络收发字节、磁盘读写字节），不是瞬时值：
 * 占用率 / 速率由服务层对相邻两次快照求差、再除以时间间隔得到
 * （见 `moe.lolosia.web.service.dashboard.SystemMetricService`）。
 *
 * 内存与磁盘容量是**瞬时值**（`memUsed` 与 `diskUsed` 是「已用」，不是累计占用），随快照一并带回。
 *
 * @author 洛洛希雅Lolosia
 */
data class SystemMetricsSnapshot(
    /** CPU 累计繁忙节拍（总节拍减去空闲与 iowait）。 */
    val cpuBusyTicks: Long,
    /** CPU 累计总节拍。 */
    val cpuTotalTicks: Long,
    /** 已用内存（字节）。 */
    val memUsedBytes: Long,
    /** 总内存（字节）。 */
    val memTotalBytes: Long,
    /** 已用磁盘（字节）。 */
    val diskUsedBytes: Long,
    /** 总磁盘（字节）。 */
    val diskTotalBytes: Long,
    /** 累计入站字节。 */
    val netInBytes: Long,
    /** 累计出站字节。 */
    val netOutBytes: Long,
    /** 累计磁盘读字节。 */
    val diskReadBytes: Long,
    /** 累计磁盘写字节。 */
    val diskWriteBytes: Long,
    /** 逐核累计繁忙节拍（顺序与系统核编号一致）；采集不到时为空。 */
    val cpuCoreBusyTicks: List<Long> = emptyList(),
    /** 逐核累计总节拍；采集不到时为空。 */
    val cpuCoreTotalTicks: List<Long> = emptyList(),
)
