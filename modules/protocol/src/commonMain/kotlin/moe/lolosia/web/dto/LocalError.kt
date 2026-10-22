package moe.lolosia.web.dto

import kotlinx.serialization.Serializable

/**
 * 本地服务的错误响应体。
 *
 * `code` 沿用 HTTP 语义：400 参数错误、401 未授权、404 未知方法、409 状态冲突、
 * 501 未实现、503 暂不可用。
 *
 * @author 洛洛希雅Lolosia
 */
@Serializable
data class LocalError(
    val code: Int,
    val message: String,
)
