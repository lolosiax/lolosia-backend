package moe.lolosia.web

import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.response.respond
import io.ktor.server.routing.Routing
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import moe.lolosia.router.RouterInjection
import moe.lolosia.router.RouterParamException
import moe.lolosia.web.util.session.Context
import moe.lolosia.web.util.session.ContextKey
import moe.lolosia.web.util.session.installCtxInterceptor

/** 服务端口（与旧 JVM 版现网部署一致）。 */
const val PORT: Int = 7129

/** 统一响应信封（后续由 dto/response 包完善）。 */
@Serializable
data class ApiResult<T>(
    val code: Int = 200,
    val msg: String = "success",
    val data: T? = null,
) {
    companion object {
        fun <T> ok(data: T? = null, msg: String = "success"): ApiResult<T> = ApiResult(200, msg, data)
        fun <T> err(code: Int, msg: String): ApiResult<T> = ApiResult(code, msg, null)
    }
}

/**
 * 装配并启动服务（与平台无关，放 commonMain）。
 *
 * [installRoutes] 由各目标源集传入（KSP 生成的路由注册代码位于目标源集，
 * 因为 Kotlin 的编译模型里 **common 代码看不到 platform 声明**）。
 *
 * 对应旧 JVM 版的 `ApplicationConfigKt.configureApplication`，但：
 * - 启动方式从 `EngineMain` + `application.yaml` 改为代码内 `embeddedServer`（Native 无 config-yaml / 无 EngineMain）；
 * - 日志暂用 `println`（KMP 日志库待接入，见方案 §8 P0-0.5）；
 * - 路由由自研路由插件生成（`generatedRoutes`），不再依赖 ktorfitx。
 */
fun startServer(port: Int, installRoutes: Routing.() -> Unit) {
    println("[ktor-template] starting on 0.0.0.0:$port (Kotlin/Native + CIO)")
    embeddedServer(CIO, port = port, host = "0.0.0.0") {
        configureApplication(installRoutes)
    }.start(wait = true)
}

private fun Application.configureApplication(installRoutes: Routing.() -> Unit) {
    // 注册「可按类型注入」的实例：控制器形参声明 ctx: Context 即自动注入
    RouterInjection.register(Context::class) { call -> call.attributes[ContextKey] }

    installCtxInterceptor()

    install(ContentNegotiation) {
        json(
            Json {
                encodeDefaults = true
                ignoreUnknownKeys = true
                isLenient = true
            }
        )
    }

    install(StatusPages) {
        exception<RouterParamException> { call, cause ->
            call.respond(HttpStatusCode.BadRequest, ApiResult.err<Unit>(400, cause.message ?: "参数错误"))
        }
        exception<Throwable> { call, cause ->
            println("[error] ${call.request.local.uri}: $cause")
            call.respond(
                HttpStatusCode.InternalServerError,
                ApiResult.err<Unit>(500, cause.message ?: "服务器内部错误"),
            )
        }
    }

    routing {
        // 手写路由（长尾场景，后续由 @Route 扫描机制承载）
        get("/") {
            call.respond(ApiResult.ok("ktor-template native"))
        }

        // 自研路由插件生成：全部 @Controller
        installRoutes()
    }
}
