package moe.lolosia.web.util.system

/**
 * 该目标（Windows）不提供系统指标采集能力，恒返回 `null`。
 *
 * 服务层据此把接口的 `available` 报为 `false`（面板显示「本机不支持」），而不是造假数据。
 */
internal actual fun readSystemMetrics(diskPath: String): SystemMetricsSnapshot? = null
