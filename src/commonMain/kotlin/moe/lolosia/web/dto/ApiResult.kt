package moe.lolosia.web.dto

import kotlinx.serialization.Serializable

/**
 * 统一响应信封，结构固定为 `{code, msg, data}`。
 *
 * @author 洛洛希雅Lolosia
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
