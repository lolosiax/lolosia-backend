package moe.lolosia.web.util.ktor

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import moe.lolosia.web.service.dav.httpDate
import moe.lolosia.web.util.classpath.Classpath
import moe.lolosia.web.util.classpath.ClasspathHolder
import moe.lolosia.web.util.classpath.StaticClasspathConfig
import moe.lolosia.web.util.classpath.normalizeClasspathPath

/**
 * 把 [zipPrefix] 下的资源挂到 [remotePath] 上。
 *
 * - 命中目录（路径为空或以 `/` 结尾）时自动追加 [StaticClasspathConfig.index]；
 * - 未命中且开了 [StaticClasspathConfig.spaFallback] 时回退到根 index（SPA 的 history 路由）；
 * - 再未命中则调用 [StaticClasspathConfig.fallback]，都没有就 404；
 * - 命中后可选经 [StaticClasspathConfig.transform] 改写内容与 MIME；
 * - 自动设置 `Content-Type`（按扩展名）、`Cache-Control`、`Last-Modified` 与 `ETag`，
 *   交给 `ConditionalHeaders` 插件做 304 协商。
 *
 * 用法：
 * ```
 * staticClasspath("/home", "static/home") {
 *     index = "index.html"
 *     spaFallback = true
 * }
 * ```
 */
fun Route.staticClasspath(
    remotePath: String,
    zipPrefix: String = "",
    cp: Classpath = ClasspathHolder.instance,
    configure: StaticClasspathConfig.() -> Unit = {},
) {
    val config = StaticClasspathConfig().apply(configure)
    val base = "/" + remotePath.trim('/')
    val prefix = zipPrefix.trim('/')

    /** 按 zip 内的条目路径取资源并写出；命中返回 `true`。 */
    suspend fun ApplicationCall.serveEntry(entry: String): Boolean {
        val bytes = cp.read(entry) ?: return false
        val transformed = config.transform?.invoke(this, bytes)
        val body = transformed?.bytes ?: bytes
        config.cacheControl?.let { response.header(HttpHeaders.CacheControl, it) }
        // 校验器必须带上「最后修改时间」：内嵌资源的时间戳是进程启动时间，重启（= 重新部署）后
        // 整体前进。只用「路径 + 长度」当 ETag 是不够的 —— 换一份二进制后 index.html 的长度
        // 大概率没变，浏览器会一直收到 304 吃那份旧的，然后去请求已经被删掉的 chunk 而白屏。
        val lastModified = cp.lastModified(entry)
        lastModified?.let { response.header(HttpHeaders.LastModified, httpDate(it)) }
        val etag = "\"${entry.hashCode().toUInt().toString(16)}-${body.size}-${lastModified ?: 0L}\""
        response.header(HttpHeaders.ETag, etag)
        respondBytes(body, transformed?.contentType ?: ContentType.defaultForFilePath(entry))
        return true
    }

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
            if (serveEntry(candidate)) return true
        }
        return false
    }

    /** 根 index 也走 [serveEntry]，这样回退响应与直接访问 `/home/` 的缓存头完全一致。 */
    suspend fun ApplicationCall.serveIndex(): Boolean {
        val idx = config.index ?: return false
        return serveEntry(listOf(prefix, idx).filter { it.isNotEmpty() }.joinToString("/"))
    }

    suspend fun ApplicationCall.serveOrFallback(rawPath: String) {
        if (serve(rawPath)) return
        if (config.spaFallback && serveIndex()) return
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
