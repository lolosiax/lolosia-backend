package moe.lolosia.web.interceptor

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.call
import io.ktor.server.request.path
import moe.lolosia.web.manager.SessionManager
import moe.lolosia.web.util.ErrorResponseException
import org.koin.core.context.GlobalContext

/**
 * JWT/会话认证中间件。
 * 只检查 /api/ 开头的路径，白名单与起始匹配路径除外；通过 Authorization: Bearer <sessionId> 校验会话。
 *
 * @author 洛洛希雅Lolosia
 * @since 2024-04-25 10:01
 */
fun Application.installAuthInterceptor() {
    // 全路径匹配
    val ignore = setOf(
        "/api/sse",
        "/api/login",
        "/api/captcha",
        "/api/verify",
        "/api/register",
        "/api/user/updatePassword",
        "/api/user/avatar",
        "/api/client/",
        "/api/resources/checkFiles",
    )

    // 起始匹配
    val ignoreMatches = listOf(
        "/api/oss/show/",
        "/api/oss/get/",
        "/api/user/avatar/get/",
    )

    intercept(ApplicationCallPipeline.Call) {
        val path = call.request.path().substringBefore('?')
        if (path.startsWith("/api/") && path !in ignore && !ignoreMatches.any { path.startsWith(it) }) {
            val sessionManager = GlobalContext.get().get<SessionManager>()
            if (sessionManager.mySession(call.request) == null) {
                throw ErrorResponseException(HttpStatusCode.Unauthorized, "身份认证失败，请重新登录")
            }
        }
        proceed()
    }
}
