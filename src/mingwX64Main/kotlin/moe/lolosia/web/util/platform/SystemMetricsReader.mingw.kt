package moe.lolosia.web.util.platform

/**
 * Windows（`mingwX64`，本地开发用）下的系统指标采集：不支持。
 *
 * 采集 CPU / 内存 / 磁盘 / 网络计数器在 Win32 上要另走 PDH 之类的接口，与部署目标（Linux 开发板）
 * 不是一套；本地开发也不关心这些数字，故直接标记为不支持，仪表板会把系统卡片整体隐藏。
 *
 * @author 洛洛希雅Lolosia
 */
actual object SystemMetricsReader {

    actual val supported: Boolean = false

    actual fun sample(): SystemMetricsSnapshot? = null
}
