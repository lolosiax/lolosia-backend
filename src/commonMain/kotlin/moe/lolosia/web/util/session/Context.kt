package moe.lolosia.web.util.session

import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.call
import io.ktor.util.AttributeKey
import moe.lolosia.router.Injectable

/**
 * 请求上下文：控制器通过形参声明 `ctx: Context` 即可**按类型自动注入**（无需注解）。
 *
 * 见自研路由插件 [Injectable]。
 *
 * @author 洛洛希雅Lolosia
 */
@Injectable
interface Context {
    val call: ApplicationCall
}

internal class ApplicationCallContext(override val call: ApplicationCall) : Context

val ContextKey: AttributeKey<Context> = AttributeKey("ctx")

/** 为每个请求创建 [Context] 并放入 `call.attributes`。 */
fun Application.installCtxInterceptor() {
    intercept(ApplicationCallPipeline.Call) {
        call.attributes.put(ContextKey, ApplicationCallContext(call))
        proceed()
    }
}
