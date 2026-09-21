package moe.lolosia.web.config.web

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.httpMethod
import io.ktor.server.request.uri
import io.ktor.server.response.respond
import moe.lolosia.web.dto.response.ApiResult
import moe.lolosia.web.util.ErrorResponseException
import moe.lolosia.web.util.packageLogger

private val logger = packageLogger()

/**
 * 全局异常处理：业务异常（ErrorResponseException）→ 对应状态码；其他异常 → 500。
 * 统一输出 {code, msg, data} 结构。
 *
 * @author 洛洛希雅Lolosia
 * @since 2026-08-30 15:38
 */
fun Application.configureErrors() {
    install(StatusPages) {
        exception<ErrorResponseException> { call, cause ->
            call.respond(cause.status, ApiResult.fail<Unit>(cause.status.value, cause.message))
        }
        exception<Throwable> { call, cause ->
            logger.error("未处理异常: {} {}", call.request.httpMethod, call.request.uri, cause)
            call.respond(
                HttpStatusCode.InternalServerError,
                ApiResult.fail<Unit>(HttpStatusCode.InternalServerError.value, cause.message ?: "服务器内部错误")
            )
        }
    }
}
