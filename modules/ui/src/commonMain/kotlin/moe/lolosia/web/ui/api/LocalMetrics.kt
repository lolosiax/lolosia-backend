package moe.lolosia.web.ui.api

/**
 * 本地服务指标；采不到的项为 `null`。
 *
 * @author 洛洛希雅Lolosia
 */
data class LocalMetrics(
    val cpu: Double? = null,
    val mem: Double? = null,
    val disk: Double? = null,
)
