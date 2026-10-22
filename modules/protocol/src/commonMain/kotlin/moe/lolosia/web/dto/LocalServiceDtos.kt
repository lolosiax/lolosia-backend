package moe.lolosia.web.dto

import kotlinx.serialization.Serializable

/**
 * 本地系统服务的连接信息，由服务进程写入受保护文件、UI 进程读取。
 *
 * @param port loopback 端口。
 * @param token 本次服务启动随机生成的 token，重启即轮换。
 * @param pid 服务进程 id，便于排查。
 * @param protocol 协议版本，与 UI 期望值不一致时提示升级。
 * @author 洛洛希雅Lolosia
 */
@Serializable
data class LocalEndpoint(
    val port: Int,
    val token: String,
    val pid: Long,
    val protocol: Int = LOCAL_PROTOCOL_VERSION,
)

/**
 * `GET /local/v1/health` 的 `data`。
 *
 * @param ok 服务是否可用。
 * @param version 服务版本。
 * @param uptime 已运行秒数。
 * @param protocol 协议版本。
 */
@Serializable
data class LocalHealthDto(
    val ok: Boolean,
    val version: String = "",
    val uptime: Long = 0,
    val protocol: Int = LOCAL_PROTOCOL_VERSION,
)

/**
 * `GET /local/v1/capabilities` 的 `data`。
 *
 * @param items 能力标识列表。
 */
@Serializable
data class LocalCapabilitiesDto(
    val items: List<String> = emptyList(),
)

/**
 * `GET /local/v1/metrics` 的 `data`；采不到的项为 `null`，百分比取 0..100。
 *
 * @param cpu CPU 使用率。
 * @param mem 内存使用率。
 * @param disk 系统盘使用率。
 */
@Serializable
data class LocalMetricsDto(
    val cpu: Double? = null,
    val mem: Double? = null,
    val disk: Double? = null,
)

/**
 * `GET /local/v1/version` 的 `data`。
 *
 * @param version 版本号。
 * @param build 构建标识。
 */
@Serializable
data class LocalVersionDto(
    val version: String = "",
    val build: String = "",
)

/** 本地服务协议版本；UI 与服务不一致时按「需要升级」处理。 */
const val LOCAL_PROTOCOL_VERSION: Int = 1
