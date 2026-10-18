package moe.lolosia.web.ui.api

/**
 * 本地服务健康状态。
 *
 * @param ok 服务是否可用。
 * @param version 服务版本。
 * @param uptime 已运行秒数。
 * @author 洛洛希雅Lolosia
 */
data class LocalHealth(
    val ok: Boolean,
    val version: String = "",
    val uptime: Long = 0,
)
