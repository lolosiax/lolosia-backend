package moe.lolosia.web.interceptor

import io.ktor.server.application.*
import io.ktor.server.request.*
import moe.lolosia.web.dto.ApiException
import moe.lolosia.web.service.SessionManager
import moe.lolosia.web.util.session.SessionKey

/**
 * 第 3 层：会话鉴权（Envelope 之前、Ctx 之后）。
 *
 * - 只检查 `/api/` 开头的路径；
 * - 携带有效 `Authorization: Bearer <sessionId>` 时把会话放进 `call.attributes[SessionKey]`，
 *   控制器的 `ctx.session` / `ctx.userId` 直接读它（不需要 suspend，见 [moe.lolosia.web.util.session.Context]）；
 * - 白名单（[IGNORE] / [IGNORE_MATCHES]，字面量沿用旧 JVM 版）之外没有有效会话 → 401
 *   （前端 `request.ts` 对 401 会清登录态并跳回登录页）。
 *
 * @author 洛洛希雅Lolosia
 */
@Interceptor(-3)
fun Application.installAuthInterceptor(sessions: SessionManager) {
    intercept(ApplicationCallPipeline.Call) {
        val path = call.request.path().substringBefore('?')
        if (path.startsWith("/api/")) {
            val session = sessions.mySession(call.request)
            if (session != null) call.attributes.put(SessionKey, session)

            val anonymous = path in IGNORE || IGNORE_MATCHES.any { path.startsWith(it) }
            if (!anonymous && session == null) {
                throw ApiException(401, "身份认证失败，请重新登录")
            }
        }
        proceed()
    }
}

/** 完全放行的路径（全路径精确匹配）。 */
private val IGNORE: Set<String> = setOf(
    "/api/sse",
    // 浏览器原生 EventSource 带不了自定义头，建流请求必然是匿名的；登录态靠它事后补绑，
    // 所以这里必须放行 —— 未登录时服务端按空操作处理（见 SseController.registry）
    "/api/sse/registry",
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
    // 文件管理器的缩略图 / 原图：给 <img> 用，认不出登录态，
    // 由控制器自己校验 URL 签名（exp + sig，见 FileSigner），拿不到签名一律 403
    "/api/files/thumb",
    "/api/files/raw",
)
