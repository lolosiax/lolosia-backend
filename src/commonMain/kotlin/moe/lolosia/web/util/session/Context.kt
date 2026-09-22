package moe.lolosia.web.util.session

import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.call
import io.ktor.util.AttributeKey
import moe.lolosia.web.ApiException
import moe.lolosia.web.service.SessionMap
import moe.lolosia.web.util.router.Injectable

/**
 * 请求上下文：控制器通过形参声明 `ctx: Context` 即可**按类型自动注入**（无需注解）。
 *
 * 见自研路由插件 [Injectable]。
 *
 * 会话由鉴权拦截器解析后放进 `call.attributes`（键 [SessionKey]），这里的属性都是**同步**读取，
 * 不需要 suspend —— 与旧 JVM 版「懒解析」的差别只是把解析时机提前到了拦截器里。
 *
 * @author 洛洛希雅Lolosia
 */
@Injectable
public interface Context {
    public val call: ApplicationCall

    /** 当前会话；未携带有效 `Authorization` 时为 `null`。 */
    public val session: SessionMap? get() = call.attributes.getOrNull(SessionKey)

    /** 会话 id（前端拿它当 token）。 */
    public val sessionId: String? get() = session?.id

    /** 当前登录用户 id；会话里没写用户时（如匿名的 `/api/session/new`）为 `null`。 */
    public val userId: String? get() = session?.userId

    /** 取登录用户 id，取不到按「未登录」处理（401）。 */
    public fun requireUserId(): String =
        userId ?: throw ApiException(401, "身份认证失败，请重新登录")
}

internal class ApplicationCallContext(override val call: ApplicationCall) : Context

/** 请求上下文在 `call.attributes` 中的键。 */
public val ContextKey: AttributeKey<Context> = AttributeKey("ctx")

/** 已解析会话在 `call.attributes` 中的键（由鉴权拦截器写入）。 */
public val SessionKey: AttributeKey<SessionMap> = AttributeKey("session")

/** 为每个请求创建 [Context] 并放入 `call.attributes`。 */
public fun Application.installCtxInterceptor() {
    intercept(ApplicationCallPipeline.Call) {
        call.attributes.put(ContextKey, ApplicationCallContext(call))
        proceed()
    }
}
