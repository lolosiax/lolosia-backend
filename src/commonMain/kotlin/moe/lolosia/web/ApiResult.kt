package moe.lolosia.web

import kotlinx.serialization.Serializable

/** 服务端口（与旧 JVM 版现网部署一致；可用命令行参数覆盖）。 */
public const val PORT: Int = 7129

/**
 * 统一响应信封。
 *
 * 手写路由（`@Route`）与自研路由插件（`@Controller`）的返回值都会经信封中间件包一层，
 * 结构固定为 `{code, msg, data}`。
 */
@Serializable
public data class ApiResult<T>(
    val code: Int = 200,
    val msg: String = "success",
    val data: T? = null,
) {
    public companion object {
        public fun <T> ok(data: T? = null, msg: String = "success"): ApiResult<T> =
            ApiResult(200, msg, data)

        public fun <T> err(code: Int, msg: String): ApiResult<T> = ApiResult(code, msg, null)
    }
}
