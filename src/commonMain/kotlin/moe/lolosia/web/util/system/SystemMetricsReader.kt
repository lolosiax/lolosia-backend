package moe.lolosia.web.util.system

/**
 * 读取一份系统指标快照。
 *
 * 解析失败（`/proc` 缺失、字段异常、平台不支持）一律返回 `null`，**不抛异常** ——
 * 调用方据此把「本机采集不到」如实反映到接口的 `available` 上。
 *
 * @param diskPath 统计磁盘容量用的挂载点，Linux 传 `/`；不支持的平台忽略该参数。
 */
internal expect fun readSystemMetrics(diskPath: String): SystemMetricsSnapshot?
