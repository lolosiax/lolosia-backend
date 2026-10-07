package moe.lolosia.web.util.ktor

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import moe.lolosia.web.util.classpath.Classpath
import moe.lolosia.web.util.classpath.ClasspathHolder
import moe.lolosia.web.util.classpath.StaticClasspathConfig
import moe.lolosia.web.util.classpath.normalizeClasspathPath

/**
 * 把 [zipPrefix] 下的资源挂到 [remotePath] 上。
 *
 * - 命中目录（路径为空或以 `/` 结尾）时自动追加 [StaticClasspathConfig.index]；
 * - 未命中时调用 [StaticClasspathConfig.fallback]，没有则返回 404；
 * - 命中后可选经 [StaticClasspathConfig.transform] 改写内容与 MIME；
 * - 自动设置 `Content-Type`（按扩展名）、`Cache-Control`、`ETag`（配合 ConditionalHeaders 支持 304）。
 *
 * 用法：
 * ```
 * staticClasspath("/home", "static/home") {
 *     index = "index.html"
 *     fallback { call.serveIndex() }
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
            val transformed = config.transform?.invoke(this, bytes)
            val body = transformed?.bytes ?: bytes
            config.cacheControl?.let { response.header(HttpHeaders.CacheControl, it) }
            response.header(HttpHeaders.ETag, "\"${candidate.hashCode().toUInt().toString(16)}-${body.size}\"")
            respondBytes(body, transformed?.contentType ?: ContentType.defaultForFilePath(candidate))
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
