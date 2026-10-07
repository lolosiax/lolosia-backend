package moe.lolosia.web

import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.plugins.conditionalheaders.ConditionalHeaders
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.cors.routing.CORS
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondRedirect
import io.ktor.server.routing.Routing
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import kotlinx.serialization.json.Json
import moe.lolosia.web.settings.ENV_SETTINGS_FILE
import moe.lolosia.web.settings.loadAppSettings
import moe.lolosia.web.configuration.startAppKoin
import moe.lolosia.web.dto.ApiException
import moe.lolosia.web.dto.ApiResult
import moe.lolosia.web.service.ddns.DdnsService
import moe.lolosia.web.util.classpath.DiskClasspath
import moe.lolosia.web.util.classpath.classpath
import moe.lolosia.web.util.classpath.installPlatformClasspath
import moe.lolosia.web.util.ktor.staticClasspath
import moe.lolosia.web.util.log.Log
import moe.lolosia.web.util.log.LogLevel
import moe.lolosia.web.util.platform.envOrNull
import moe.lolosia.web.util.router.RouterInjection
import moe.lolosia.web.util.router.RouterParamException
import moe.lolosia.web.util.session.Context
import moe.lolosia.web.util.session.ContextKey
import org.koin.core.Koin
import org.koin.core.module.Module

/**
 * 服务启动（native 专用：需要装载嵌入式 classpath）。
 *
 * [installInterceptors]、[installInjectables] 与 [installRoutes] 由各目标源集传入：KSP 生成的
 * 中间件注册表、DI 绑定表与路由注册代码都位于目标源集，而 common 代码看不到 platform 声明，
 * 故入口下沉到本层。
 *
 * @param portOverride 命令行参数传来的端口（最高优先级）；为 `null` 时取配置里的端口
 * @param platformModules 目标源集特有的绑定（sqlx4k 生成的仓库实现只在该目标源集可见）
 * @param installInterceptors 中间件注册（由目标源集传入 `configureInterceptors(koin)`）
 * @param installInjectables 路由按类型注入的注册（由目标源集传入生成的 `Koin.registerInjectables`）
 * @param installRoutes 路由注册（由目标源集传入 `generatedRoutes()`）
 */
public fun startServer(
    portOverride: Int?,
    platformModules: List<Module>,
    installInterceptors: Application.(Koin) -> Unit,
    installInjectables: Koin.() -> Unit,
    installRoutes: Routing.() -> Unit,
) {
    val log = Log.of("Bootstrap")

    val cp = installPlatformClasspath()
    val loaded = loadAppSettings(
        classpath = cp,
        // KTOR_CONFIG 指了就用它；否则按 SETTINGS_FILES（yaml / yml / json）在启动目录里找第一个存在的
        diskPath = envOrNull(ENV_SETTINGS_FILE),
        env = ::envOrNull,
    )
    // 命令行参数优先级最高：直接写回配置，保证「生效配置」与真实监听端口一致
    val config = if (portOverride == null) loaded else loaded.copy(server = loaded.server.copy(port = portOverride))
    Log.level = LogLevel.parse(config.logLevel)

    // Koin 容器：业务服务挂在 serviceModule，仓库实现由目标源集传入
    val koin = startAppKoin(config, cp, platformModules)

    val source = if (cp is DiskClasspath) "磁盘回退目录" else "可执行文件内嵌 zip"
    log.info { "classpath 已装载：${cp.entries.size} 个条目（来源：$source）" }
    log.info { "配置已加载：port=${config.server.port}, db=${config.datasource.url}, 日志级别=${Log.level}" }
    log.info { "starting on ${config.server.host}:${config.server.port} (Kotlin/Native + CIO)" }

    embeddedServer(CIO, port = config.server.port, host = config.server.host) {
        configureApplication(koin, installInterceptors, installInjectables, installRoutes)
    }.start(wait = true)
}

private fun Application.configureApplication(
    koin: Koin,
    installInterceptors: Application.(Koin) -> Unit,
    installInjectables: Koin.() -> Unit,
    installRoutes: Routing.() -> Unit,
) {
    val log = Log.of("Application")

    // 路由的「按类型注入」分两半：
    // 逐请求的 Context 由 call.attributes 提供（键由鉴权中间件写入）；容器实例由生成的注册表接入
    RouterInjection.register(Context::class) { call -> call.attributes[ContextKey] }
    koin.installInjectables()

    install(ContentNegotiation) {
        json(
            Json {
                encodeDefaults = true
                ignoreUnknownKeys = true
                isLenient = true
            }
        )
    }

    // 跨域：前端 dev server（:5005）与后端（:7129）不同源，浏览器一律先发预检。
    // 必须早于业务拦截器注册（Call 阶段按注册顺序执行），否则预检拿不到 Allow-Origin。
    install(CORS) {
        allowMethod(HttpMethod.Options)
        allowMethod(HttpMethod.Put)
        allowMethod(HttpMethod.Delete)
        allowMethod(HttpMethod.Patch)
        allowHeader(HttpHeaders.Authorization)
        // allowHeader(ContentType) 会顺带打开 allowNonSimpleContentTypes，
        // 否则 JSON 请求的预检与实际请求都会被拦成 403
        allowHeader(HttpHeaders.ContentType)
        allowHeader("x-upgrade") // SSE 握手自定义头
        // 前端 getAvatar 显式带 `Cache-Control: no-cache`，它不属于 CORS 简单头，
        // 不放行的话预检会被拒（403），浏览器侧表现为 `net::ERR_FAILED`
        allowHeader(HttpHeaders.CacheControl)
        anyHost()
    }

    // 中间件按 @Interceptor(order) 注册。必须晚于 ContentNegotiation：信封层按响应 MIME 判定，
    // 要等内容协商把响应对象转成带 Content-Type 的 OutgoingContent 之后才认得出 JSON。
    installInterceptors(koin)

    // 让静态资源支持 If-None-Match / If-Modified-Since 的 304 协商
    install(ConditionalHeaders)

    install(StatusPages) {
        // 业务/鉴权异常：HTTP 状态码与信封 code 取同一个值
        exception<ApiException> { call, cause ->
            call.respond(HttpStatusCode.fromValue(cause.status), ApiResult.err<Unit>(cause.status, cause.message ?: "请求失败"))
        }
        exception<RouterParamException> { call, cause ->
            call.respond(HttpStatusCode.BadRequest, ApiResult.err<Unit>(400, cause.message ?: "参数错误"))
        }
        exception<Throwable> { call, cause ->
            log.error(cause) { "${call.request.local.uri} 处理失败" }
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

    // DDNS：挂到应用作用域按配置间隔轮询，未启用时只记一条日志
    koin.get<DdnsService>().start(this)
}
