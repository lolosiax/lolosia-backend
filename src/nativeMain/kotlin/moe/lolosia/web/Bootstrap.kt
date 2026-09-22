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
import moe.lolosia.web.settings.AppSettings
import moe.lolosia.web.settings.ENV_SETTINGS_FILE
import moe.lolosia.web.settings.SETTINGS_FILE
import moe.lolosia.web.settings.loadAppSettings
import moe.lolosia.web.database.Database
import moe.lolosia.web.database.repository.SysRoleRepository
import moe.lolosia.web.database.repository.SysUserRepository
import moe.lolosia.web.database.repository.SysUserRoleRepository
import moe.lolosia.web.database.repository.ViewUserRoleRepository
import moe.lolosia.web.configuration.startAppKoin
import moe.lolosia.web.dto.ApiResult
import moe.lolosia.web.service.CacheManager
import moe.lolosia.web.service.SessionManager
import moe.lolosia.web.util.classpath.DiskClasspath
import moe.lolosia.web.util.classpath.classpath
import moe.lolosia.web.util.classpath.installPlatformClasspath
import moe.lolosia.web.util.koin.registerInjectable
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
 * [installInterceptors] 与 [installRoutes] 由各目标源集传入：KSP 生成的中间件注册表与路由注册代码
 * 都位于目标源集，而 common 代码看不到 platform 声明，故入口下沉到本层。
 *
 * @param portOverride 命令行参数传来的端口（最高优先级）；为 `null` 时取配置里的端口
 * @param platformModules 目标源集特有的绑定（sqlx4k 生成的仓库实现只在该目标源集可见）
 * @param installInterceptors 中间件注册（由目标源集传入 `configureInterceptors(koin)`）
 * @param installRoutes 路由注册（由目标源集传入 `generatedRoutes()`）
 */
public fun startServer(
    portOverride: Int?,
    platformModules: List<Module>,
    installInterceptors: Application.(Koin) -> Unit,
    installRoutes: Routing.() -> Unit,
) {
    val log = Log.of("Bootstrap")

    val cp = installPlatformClasspath()
    val loaded = loadAppSettings(
        classpath = cp,
        diskPath = envOrNull(ENV_SETTINGS_FILE) ?: SETTINGS_FILE,
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
        configureApplication(koin, installInterceptors, installRoutes)
    }.start(wait = true)
}

private fun Application.configureApplication(
    koin: Koin,
    installInterceptors: Application.(Koin) -> Unit,
    installRoutes: Routing.() -> Unit,
) {
    val log = Log.of("Application")

    // 注册「可按类型注入」的实例：控制器形参声明 ctx: Context / config: AppSettings 即自动注入
    RouterInjection.register(Context::class) { call -> call.attributes[ContextKey] }
    koin.registerInjectable<AppSettings>()
    koin.registerInjectable<Database>()
    koin.registerInjectable<CacheManager>()
    koin.registerInjectable<SessionManager>()
    koin.registerInjectable<SysUserRepository>()
    koin.registerInjectable<SysRoleRepository>()
    koin.registerInjectable<SysUserRoleRepository>()
    koin.registerInjectable<ViewUserRoleRepository>()

    install(ContentNegotiation) {
        json(
            Json {
                encodeDefaults = true
                ignoreUnknownKeys = true
                isLenient = true
            }
        )
    }

    // 中间件按 @Interceptor(order) 注册。必须晚于 ContentNegotiation：信封层按响应 MIME 判定，
    // 要等内容协商把响应对象转成带 Content-Type 的 OutgoingContent 之后才认得出 JSON。
    installInterceptors(koin)

    // 让静态资源支持 If-None-Match / If-Modified-Since 的 304 协商
    install(ConditionalHeaders)

    install(StatusPages) {
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
}
