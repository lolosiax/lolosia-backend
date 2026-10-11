package moe.lolosia.web.util.platform

/**
 * 系统指标采集的平台入口。
 *
 * Linux 下读 `/proc`（stat / meminfo / net.dev / diskstats）与根分区的 `statvfs`；
 * 速率类指标（网络 / 磁盘 IO）依赖**相邻两次采样的差分**，因此实现是有状态的 ——
 * 首次调用（或长时间没有上次读数）时这些速率记 0。
 *
 * Windows 目标（本地开发用的 `mingwX64`）下 [supported] 为 `false`、[sample] 恒为 `null`，
 * 仪表板据此把系统卡片整体隐藏或显示「本平台不采集」。这是「能显示什么取决于实际情况」的落点。
 *
 * @author 洛洛希雅Lolosia
 */
expect object SystemMetricsReader {

    /** 当前平台是否支持采集。 */
    val supported: Boolean

    /**
     * 采样一次；平台不支持、或 `/proc` 读取失败时返回 `null`。
     *
     * 会阻塞（读 `/proc` 是同步文件 IO），调用方应放在后台协程里。
     */
    fun sample(): SystemMetricsSnapshot?
}
