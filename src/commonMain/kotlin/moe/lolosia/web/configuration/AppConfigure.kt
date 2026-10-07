package moe.lolosia.web.configuration

import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.conditionalheaders.ConditionalHeaders
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.cors.routing.CORS
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondRedirect
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import kotlinx.serialization.json.Json
import moe.lolosia.web.VITE_DEFAULT
import moe.lolosia.web.dto.ApiException
import moe.lolosia.web.dto.ApiResult
import moe.lolosia.web.generated.configureInterceptors
import moe.lolosia.web.generated.generatedRoutes
import moe.lolosia.web.generated.registerInjectables
import moe.lolosia.web.service.ddns.DdnsService
import moe.lolosia.web.util.classpath.classpath
import moe.lolosia.web.util.ktor.staticClasspath
import moe.lolosia.web.util.log.Log
import moe.lolosia.web.util.router.RouterInjection
import moe.lolosia.web.util.router.RouterParamException
import moe.lolosia.web.util.session.Context
import moe.lolosia.web.util.session.ContextKey
import org.koin.core.Koin

/**
 * Ktor [Application] 的装配：插件（内容协商 / CORS / 条件请求 / 状态页）、注解式中间件、
 * 静态资源与生成路由，以及 DDNS 启动。
 *
 * 中间件与路由的内容来自 router-ksp 生成的 `actual` 注册表（`moe.lolosia.web.generated`），
 * 这里只按正确顺序调用。
 *
 * @author 洛洛希雅Lolosia
 * @since 2026-10-07 14:04
 */
public fun Application.configureApplication(koin: Koin) {
    val log = Log.of("Application")

    // 路由的「按类型注入」分两半：
    // 逐请求的 Context 由 call.attributes 提供（键由鉴权中间件写入）；容器实例由生成的注册表接入
    RouterInjection.register(Context::class) { call -> call.attributes[ContextKey] }
    koin.registerInjectables()

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
    configureInterceptors(koin)

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
        generatedRoutes()
    }

    // DDNS：挂到应用作用域按配置间隔轮询，未启用时只记一条日志
    koin.get<DdnsService>().start(this)
}
