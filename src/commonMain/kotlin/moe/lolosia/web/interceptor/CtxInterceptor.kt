package moe.lolosia.web.interceptor

import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.call
import moe.lolosia.web.util.session.ApplicationCallContext
import moe.lolosia.web.util.session.ContextKey

/**
 * 第 2 层：为每个请求创建请求上下文并放入 `call.attributes`，控制器形参写 `ctx: Context` 即得。
 *
 * @author 洛洛希雅Lolosia
 */
@Interceptor(-5)
public fun Application.installCtxInterceptor() {
    intercept(ApplicationCallPipeline.Call) {
        call.attributes.put(ContextKey, ApplicationCallContext(call))
        proceed()
    }
}
