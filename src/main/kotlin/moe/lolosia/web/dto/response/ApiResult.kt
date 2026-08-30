package moe.lolosia.web.dto.response

/**
 * @author 洛洛希雅Lolosia
 * @since 2026-08-30 15:27
 */

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respond
import kotlinx.serialization.Serializable

/**
 * 统一响应体 {code, msg, data}。
 * kotlinx.serialization 会根据该注解在编译期生成序列化器，是实际的默认序列化。
 */
@Serializable
data class ApiResult<T : Any>(
    val code: Int,
    val msg: String,
    val data: T? = null,
) {
    companion object {
        fun <T : Any> ok(data: T? = null): ApiResult<T> = ApiResult(0, "success", data)
        fun <T : Any> fail(code: Int, msg: String): ApiResult<T> = ApiResult(code, msg, null)
    }
}

/**
 * 成功响应：效果等同 Spring 的 return 实体。
 * reified 保留具体泛型类型，kotlinx 才能解析出 ApiResult<T> 的序列化器。
 */
suspend inline fun <reified T : Any> ApplicationCall.ok(data: T? = null) =
    respond(HttpStatusCode.OK, ApiResult.ok(data))

/** 业务失败响应（HTTP 状态仍是 200，业务码由 code 表达）。 */
suspend fun ApplicationCall.fail(code: Int, msg: String) =
    respond(HttpStatusCode.OK, ApiResult.fail<Unit>(code, msg))
