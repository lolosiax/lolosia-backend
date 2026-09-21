package moe.lolosia.web.interceptor

import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.call
import moe.lolosia.web.util.session.ContextKey
import moe.lolosia.web.util.session.ApplicationCallContext

/**
 * ctx 绑定中间件：为每个请求创建 Context 并放入 call.attributes，
 * 控制器通过 ktorfitx 的 `@Attribute("ctx")` 获取（业务代码传 ctx 而非 call）。
 *
 * @author 洛洛希雅Lolosia
 * @since 2026-08-30 15:32
 */
fun Application.installCtxInterceptor() {
    intercept(ApplicationCallPipeline.Call) {
        call.attributes.put(ContextKey, ApplicationCallContext(call))
        proceed()
    }
}
