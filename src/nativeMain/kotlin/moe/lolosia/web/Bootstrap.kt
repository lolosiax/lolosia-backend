package moe.lolosia.web

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.plugins.conditionalheaders.ConditionalHeaders
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondRedirect
import io.ktor.server.routing.Routing
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import kotlinx.serialization.json.Json
import moe.lolosia.web.classpath.DiskClasspath
import moe.lolosia.web.classpath.classpath
import moe.lolosia.web.classpath.installPlatformClasspath
import moe.lolosia.web.classpath.staticClasspath
import moe.lolosia.web.util.router.RouterInjection
import moe.lolosia.web.util.router.RouterParamException
import moe.lolosia.web.util.session.Context
import moe.lolosia.web.util.session.ContextKey
import moe.lolosia.web.util.session.installCtxInterceptor

/**
 * 服务启动（native 专用：需要装载嵌入式 classpath）。
 *
 * [installRoutes] 由各目标源集传入 —— KSP 生成的路由注册代码位于目标源集，
 * 而 Kotlin 编译模型下 **common 代码看不到 platform 声明**，故入口必须下沉到本层/目标源集。
 *
 * 对应旧 JVM 版的 `ApplicationConfigKt.configureApplication`，差异：
 * - 启动方式从 `EngineMain` + `application.yaml` 改为 `embeddedServer`（Native 无 config-yaml / 无 EngineMain）；
 * - 资源访问从 classloader 改为嵌入式 `classpath.zip`（[ClasspathHolder]）；
 * - 日志暂用 `println`（KMP 日志库见方案 §8 P0-0.5）。
 */
public fun startServer(port: Int, installRoutes: Routing.() -> Unit) {
    val cp = installPlatformClasspath()
    val source = if (cp is DiskClasspath) "磁盘回退目录" else "可执行文件内嵌 zip"
    println("[ktor-template] classpath 已装载：${cp.entries.size} 个条目（来源：$source）")
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

    // 让静态资源支持 If-None-Match / If-Modified-Since 的 304 协商
    install(ConditionalHeaders)

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
        // 手写路由：前端静态资源（来自嵌入式 classpath，SPA 未命中回退 index.html）
        // 路径取自 generateConstants 依 viteJar 平台生成的常量，不写死字符串
        staticClasspath("/$VITE_DEFAULT", "static/$VITE_DEFAULT") {
            index = "index.html"
            cacheControl = "max-age=60"
            fallback = { _ ->
                val index = classpath.read("static/$VITE_DEFAULT/index.html")
                if (index != null) {
                    respondBytes(index, ContentType.Text.Html)
                } else {
                    respond(HttpStatusCode.NotFound, ApiResult.err<Unit>(404, "前端资源缺失"))
                }
            }
        }
        get("/") { call.respondRedirect("/$VITE_DEFAULT/", permanent = true) }
        get("/$VITE_DEFAULT") { call.respondRedirect("/$VITE_DEFAULT/", permanent = true) }

        // 自研路由插件生成：全部 @Controller
        installRoutes()
    }
}
