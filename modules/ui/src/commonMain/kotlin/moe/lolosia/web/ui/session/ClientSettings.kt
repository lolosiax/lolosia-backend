package moe.lolosia.web.ui.session

import kotlinx.serialization.Serializable

/**
 * 客户端本地设置。
 *
 * [baseUrl] 的实际值由本机设置 / 环境变量提供，仓库内只保留本机开发默认值。
 *
 * @param baseUrl 服务端根地址，末尾不带 `/`。
 * @param token 会话 id；未登录为 `null`。
 * @param userName 上次登录的用户名，用于回填。
 * @author 洛洛希雅Lolosia
 */
@Serializable
data class ClientSettings(
    val baseUrl: String = "http://127.0.0.1:7129",
    val token: String? = null,
    val userName: String? = null,
)
