package moe.lolosia.web.configuration

import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.plugins.conditionalheaders.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.plugins.cors.routing.*
import io.ktor.server.plugins.statuspages.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.json.Json
import moe.lolosia.web.VITE_DEFAULT
import moe.lolosia.web.database.Database
import moe.lolosia.web.database.repository.SysRoleRepository
import moe.lolosia.web.dto.ApiException
import moe.lolosia.web.dto.ApiResult
import moe.lolosia.web.generated.configureInterceptors
import moe.lolosia.web.generated.generatedRoutes
import moe.lolosia.web.generated.registerInjectables
import moe.lolosia.web.service.AppSettingService
import moe.lolosia.web.service.WebpCache
import moe.lolosia.web.service.anime.AnimeFeedService
import moe.lolosia.web.service.anime.AnimeMetaService
import moe.lolosia.web.service.dav.DavLockManager
import moe.lolosia.web.service.dav.DavStore
import moe.lolosia.web.service.dashboard.AccessStatService
import moe.lolosia.web.service.dashboard.SystemMetricService
import moe.lolosia.web.service.dashboard.TokenUsageService
import moe.lolosia.web.service.ddns.DdnsService
import moe.lolosia.web.service.files.ThumbnailService
import moe.lolosia.web.service.media.MediaAnalyzeService
import moe.lolosia.web.service.media.MediaIndexService
import moe.lolosia.web.service.permission.PermissionRegistry
import moe.lolosia.web.service.permission.syncRegistryGroups
import moe.lolosia.web.settings.AppSettings
import moe.lolosia.web.util.classpath.FileStoreClasspath
import moe.lolosia.web.util.classpath.StaticClasspathConfig
import moe.lolosia.web.util.fs.FileStore
import moe.lolosia.web.util.ktor.staticClasspath
import moe.lolosia.web.util.ktor.webDav
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
fun Application.configureApplication(koin: Koin) {
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
            // 入口文件名不带构建哈希（`index.html` / `index.js`），按 `max-age` 缓存的话，
            // 重新部署后浏览器会拿旧入口去请求已被删掉的 chunk，页面直接白屏 —— 手机上又没有强刷。
            // 一律回源校验：内容没变就是 304，变了立刻换新（见 Classpath.lastModified）。
            cacheControl = "no-cache"
            spaFallback = true
            fallback = { _ -> respond(HttpStatusCode.NotFound, ApiResult.err<Unit>(404, "前端资源缺失")) }
        }
        get("/") { call.respondRedirect("/$VITE_DEFAULT/", permanent = true) }
        get("/$VITE_DEFAULT") { call.respondRedirect("/$VITE_DEFAULT/", permanent = true) }

        // 运行期资源目录：旧版 `/res/resources/**` → `work/resources/**`（landing 页背景图走这里）。
        // `?webp` 时按旧版语义转 WebP 并落盘缓存（见 WebpCache）；不支持的格式或转换失败回退原图。
        val webpCache = koin.get<WebpCache>()
        staticClasspath("/res/resources", cp = FileStoreClasspath(koin.get<FileStore>(), "resources")) {
            index = null
            transform = { bytes ->
                if (request.queryParameters.contains("webp")) {
                    webpCache.get(bytes)?.let { StaticClasspathConfig.Transformed(it, ContentType("image", "webp")) }
                } else {
                    null
                }
            }
        }

        // WebDAV：把机械盘挂到配置的路径下（可选）。不走 `/api`，因此既不被会话鉴权拦截，
        // 响应也不会被 JSON 信封包装 —— Windows 的 WebDAV 客户端要的是原样状态码与 207 多状态 XML。
        // 「是否允许访问隐藏文件」是运行时可改的（设置面板 → sys_cache），所以按回调取值而不是定死
        koin.get<AppSettings>().webdav.takeIf { it.enabled }?.let { cfg ->
            val appSetting = koin.get<AppSettingService>()
            webDav(cfg, DavStore(cfg.root) { appSetting.allowHiddenFiles }, DavLockManager())
        }

        // 自研路由插件生成：全部 @Controller
        generatedRoutes()
    }

    // DDNS：挂到应用作用域按配置间隔轮询，未启用时只记一条日志
    koin.get<DdnsService>().start(this)

    // 缩略图后台生成：由 files.thumbnail.enabled 开关控制，未启用时只记一条日志
    koin.get<ThumbnailService>().start()

    // 媒体索引后台扫描：**只做本地的事**（stat / 哈希 / EXIF / 规则打标），不出网（§8.2）
    koin.get<MediaIndexService>().start()

    // 外发分析（视觉自动打标）：默认关；开启后才把素材交给外部模型（见 doc/vision-autotag-design.md）
    koin.get<MediaAnalyzeService>().start()

    // 仪表盘：系统指标后台采样与访问统计聚合（由 dashboard.enabled 开关控制，未启用只记一条日志）
    koin.get<SystemMetricService>().start()
    koin.get<AccessStatService>().start()
    koin.get<TokenUsageService>().start()

    // 番组表：MioBT 系发布站 RSS 采集（轮询 + 单实例）+ Bangumi 目录同步（由 anime.enabled 控制）
    koin.get<AnimeFeedService>().start(this)
    koin.get<AnimeMetaService>().start(this)

    // 数据库：启动即后台探一次，失败只记日志并周期重连 —— 不阻塞启动，库恢复后自动可用。
    // 连上后顺带把权限注册表声明的组继承同步进 sys_role.parent_id（幂等；规则不落库，见 service.permission），
    // 以及把落库的运行时设置（WebDAV 隐藏文件开关）读回内存。
    val database = koin.get<Database>()
    database.startReconnectLoop(this) {
        syncRegistryGroups(database.driver, koin.get<PermissionRegistry>(), koin.get<SysRoleRepository>())
        koin.get<AppSettingService>().load()
    }
}
