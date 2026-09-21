package moe.lolosia.classpath

import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.defaultForFilePath
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.routing.Route
import io.ktor.server.routing.get

/**
 * 用 [Classpath] 中的资源提供静态内容。
 *
 * 对应 JVM 上的 `staticResources(remotePath, basePackage)`，区别是资源来自嵌入二进制的
 * `classpath.zip`，而非 classloader。
 *
 * 用法（与原来几乎一致）：
 * ```
 * routing {
 *     staticClasspath("/home", "static/home") {
 *         index = "index.html"
 *         // SPA history 路由回退
 *         fallback { path -> call.serveIndex() }
 *     }
 * }
 * ```
 *
 * @author 洛洛希雅Lolosia
 */

/** [staticClasspath] 的配置。 */
public class StaticClasspathConfig {
    /** 目录请求（或以 `/` 结尾）时返回的文件名；设为 null 则不自动补 index。 */
    public var index: String? = "index.html"

    /** `Cache-Control` 值；null 表示不加该响应头。 */
    public var cacheControl: String? = "max-age=3600"

    /** 未命中时的回退（SPA 常用：把未知路径交给前端路由，返回 index.html）。 */
    public var fallback: (suspend ApplicationCall.(path: String) -> Unit)? = null
}

/**
 * 把 [zipPrefix] 下的资源挂到 [remotePath] 上。
 *
 * - 命中目录（路径为空或以 `/` 结尾）时自动追加 [StaticClasspathConfig.index]；
 * - 未命中时调用 [StaticClasspathConfig.fallback]，没有则返回 404；
 * - 自动设置 `Content-Type`（按扩展名）、`Cache-Control`、`ETag`（配合 Ktor 的 ConditionalHeaders 做 304）。
 */
public fun Route.staticClasspath(
    remotePath: String,
    zipPrefix: String = "",
    cp: Classpath = ClasspathHolder.instance,
    configure: StaticClasspathConfig.() -> Unit = {},
) {
    val config = StaticClasspathConfig().apply(configure)
    val base = "/" + remotePath.trim('/')
    val prefix = zipPrefix.trim('/')

    suspend fun ApplicationCall.serve(rawPath: String): Boolean {
        val rel = normalizeClasspathPath(rawPath)
        val candidates = buildList {
            val joined = listOf(prefix, rel).filter { it.isNotEmpty() }.joinToString("/")
            add(joined)
            // 目录请求 / 无扩展名的 history 路由：尝试补 index
            config.index?.let { idx ->
                if (joined.isEmpty() || joined.endsWith("/")) add("$joined$idx")
                else if (!joined.substringAfterLast('/').contains('.')) add("$joined/$idx")
            }
        }

        for (candidate in candidates) {
            val bytes = cp.read(candidate) ?: continue
            config.cacheControl?.let { response.header(HttpHeaders.CacheControl, it) }
            response.header(HttpHeaders.ETag, "\"${candidate.hashCode().toUInt().toString(16)}-${bytes.size}\"")
            respondBytes(bytes, ContentType.defaultForFilePath(candidate))
            return true
        }
        return false
    }

    suspend fun ApplicationCall.serveOrFallback(rawPath: String) {
        if (serve(rawPath)) return
        val fb = config.fallback
        if (fb != null) fb.invoke(this, rawPath) else respond(HttpStatusCode.NotFound)
    }

    get("$base/{path...}") {
        call.serveOrFallback(call.parameters.getAll("path")?.joinToString("/") ?: "")
    }
    get(base) {
        call.serveOrFallback("")
    }
}
