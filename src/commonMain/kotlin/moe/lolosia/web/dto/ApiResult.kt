package moe.lolosia.web.dto

import kotlinx.serialization.Serializable

/**
 * 统一响应信封，结构固定为 `{code, msg, data}`（与旧 JVM 版逐字一致，前端 `request.ts` 按此解包）。
 *
 * 注解式控制器不必自己拼信封：`EnvelopeInterceptor` 按响应 MIME 统一包一层；
 * 手写路由 / StatusPages 直接 `call.respond(ApiResult...)`，该中间件见三键齐备会跳过，不重复包。
 *
 * @author 洛洛希雅Lolosia
 */
@Serializable
data class ApiResult<T>(
    val code: Int = 200,
    val msg: String = "success",
    val data: T? = null,
) {
    companion object {
        fun <T> ok(data: T? = null, msg: String = "success"): ApiResult<T> =
            ApiResult(200, msg, data)

        fun <T> err(code: Int, msg: String): ApiResult<T> = ApiResult(code, msg, null)
    }
}

/**
 * 带 HTTP 状态码的业务异常：[status] 同时作为 HTTP 状态码与信封的 `code`
 * （旧 JVM 版的 `ErrorResponseException` 在 native 上的对应物，由 StatusPages 转换）。
 *
 * 鉴权失败用 `401`：前端 `request.ts` 对 `401/403` 会清登录态并跳回登录页。
 */
class ApiException(val status: Int, message: String) : RuntimeException(message)
