package moe.lolosia.web.util.ktor

import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.call
import moe.lolosia.web.util.session.ApplicationCallContext
import moe.lolosia.web.util.session.ContextKey

/** 为每个请求创建请求上下文并放入 `call.attributes`。 */
public fun Application.installCtxInterceptor() {
    intercept(ApplicationCallPipeline.Call) {
        call.attributes.put(ContextKey, ApplicationCallContext(call))
        proceed()
    }
}
