package moe.lolosia.web.interceptor

import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.call
import io.ktor.server.request.path
import moe.lolosia.web.ApiException
import moe.lolosia.web.service.SessionManager
import moe.lolosia.web.util.session.SessionKey

/**
 * 会话鉴权（注解式路由之前）。
 *
 * - 只检查 `/api/` 开头的路径；
 * - 携带有效 `Authorization: Bearer <sessionId>` 时把会话放进 `call.attributes[SessionKey]`，
 *   控制器的 `ctx.session` / `ctx.userId` 直接读它（不需要 suspend，见 [moe.lolosia.web.util.session.Context]）；
 * - 白名单（[IGNORE] / [IGNORE_MATCHES]，字面量沿用旧 JVM 版）之外没有有效会话 → 401
 *   （前端 `request.ts` 对 401 会清登录态并跳回登录页）。
 *
 * @author 洛洛希雅Lolosia
 */
public fun Application.installAuthInterceptor(sessions: SessionManager) {
    intercept(ApplicationCallPipeline.Call) {
        val path = call.request.path().substringBefore('?')
        if (path.startsWith("/api/")) {
            val session = sessions.mySession(call.request)
            if (session != null) call.attributes.put(SessionKey, session)

            val anonymous = path in IGNORE || DEV_PROBES.any { path.startsWith(it) } ||
                IGNORE_MATCHES.any { path.startsWith(it) }
            if (!anonymous && session == null) {
                throw ApiException(401, "身份认证失败，请重新登录")
            }
        }
        proceed()
    }
}

/**
 * ⚠️ **临时**：P0 期间的诊断探针（[moe.lolosia.web.controller.DemoController]）需要免鉴权，
 * 否则「没有账号就没法登录取 token」的鸡生蛋问题会让冒烟脚本跑不起来
 * （探针里的 `/api/svc/selftest` 会造一个临时账号并返回会话 id，供后续带 token 验证鉴权链路）。
 * P0 收尾删掉 `DemoController` 时**连同这里一起删**。
 */
private val DEV_PROBES: List<String> = listOf(
    "/api/ping",
    "/api/whoami",
    "/api/config",
    "/api/db/",
    "/api/cache/",
    "/api/session/",
    "/api/svc/",
)

/** 完全放行的路径（全路径精确匹配）。 */
private val IGNORE: Set<String> = setOf(
    "/api/sse",
    "/api/login",
    "/api/captcha",
    "/api/verify",
    "/api/register",
    "/api/user/updatePassword",
    "/api/user/avatar",
)

/** 放行的路径前缀（取件类接口，前端用 `<img src>` 直接访问，带不了自定义请求头）。 */
private val IGNORE_MATCHES: List<String> = listOf(
    "/api/oss/show/",
    "/api/oss/get/",
    "/api/user/avatar/get/",
)
