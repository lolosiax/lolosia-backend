package moe.lolosia.web.service

import moe.lolosia.web.LolosiaApplication
import moe.lolosia.web.config.SConfig
import moe.lolosia.web.util.session.Context
import moe.lolosia.web.util.session.IWebExchangeContext
import moe.lolosia.web.util.spring.createFileResponse
import org.springframework.http.*
import org.springframework.http.server.reactive.ServerHttpRequest
import org.springframework.stereotype.Service
import org.springframework.web.ErrorResponseException
import org.springframework.web.util.UriComponentsBuilder
import java.util.*
import kotlin.io.path.Path
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.readBytes

@Service
class HomeService {


    final val bootTime = Date()
    val classpathCaches = mutableMapOf<String, ByteArray>()
    val fileCaches = mutableMapOf<String, Pair<ByteArray, Long>>()


    /**
     * 处理平台静态文件
     */
    suspend fun handlePlatform(ctx: Context, platform: String, file: String): ResponseEntity<ByteArray> {
        ctx as IWebExchangeContext
        val request = ctx.request // 获取当前请求对象
        val fileName = UriComponentsBuilder.fromPath(file).build().path ?: file

        val paths = listOf(
            "work/web/${platform}/${file}",
            "/static/${platform}/${file}",
            "work/web/${platform}/index.html",
            "/static/${platform}/index.html",
        )

        for (path in paths) {
            // 1. 获取原始文件数据
            var (data, lastModified) = getFileData(path) ?: continue

            if (SConfig.server.hookCssHostUrl) {
                // 2. 判断是否为 CSS 文件，如果是则进行处理
                if (fileName.endsWith(".css")) {
                    data = processCssContent(data, request)
                }
            }

            val contentType = MediaTypeFactory.getMediaType(path).orElse(MediaType.APPLICATION_OCTET_STREAM)

            // 3. 返回响应，注意 Content-Length 必须使用处理后的 data.size
            return ctx.createFileResponse(
                lastModified,
                CacheControl.noCache(), // 动态内容建议使用 noCache
                contentType,
                data.size.toLong(),
                data
            )
        }

        throw ErrorResponseException(HttpStatus.NOT_FOUND)
    }

    /**
     * 核心方法：处理 CSS 内容，给绝对路径 url 添加当前请求的 Host
     */
    private fun processCssContent(originalBytes: ByteArray, request: ServerHttpRequest): ByteArray {
        val content = String(originalBytes, Charsets.UTF_8)

        // 构建当前请求的基础 URL (例如: http://xxx.xxx.xxx.xxx:7051)
        val scheme = request.uri.scheme ?: "http"
        val host = request.headers.host?.hostString ?: "localhost"
        val port = if (request.uri.port != -1) ":${request.uri.port}" else ""
        val baseUrl = "$scheme://$host$port"

        // 正则匹配 url('/path...') 或 url(/path...)
        // Group 1: 可选的引号 (' 或 ")
        // Group 2: 以 / 开头的路径字符串
        val regex = """url\(\s*(['"])?(/[^'")\s]+)\1?\s*\)""".toRegex()

        val newContent = content.replace(regex) { match ->
            val quote = match.groupValues[1] // 保留原本的引号（如果有）
            val path = match.groupValues[2]   // 获取路径，例如 /static/img.png

            // 拼接 Host 和路径
            "url(${quote}${baseUrl}${path}${quote})"
        }

        return newContent.toByteArray(Charsets.UTF_8)
    }

    private fun getFileData(path: String): Pair<ByteArray, Long>? {
        if (path.endsWith("/")) return null

        if (path.startsWith("work/web")) {
            val p = Path(path)
            if (!p.exists() || p.isDirectory()) return null
            // 判断文件是否被缓存且时间戳一致
            if (fileCaches.containsKey(path) && fileCaches[path]?.second == p.toFile().lastModified()) {
                return fileCaches[path]
            }

            val pair = p.readBytes() to p.toFile().lastModified()
            fileCaches[path] = pair
            return pair
        } else if (path.startsWith("/static")) {
            if (classpathCaches.containsKey(path)) {
                val data = classpathCaches[path]!!
                return Pair(data, bootTime.time)
            }

            val bytes = LolosiaApplication::class.java.getResourceAsStream(path)?.readBytes() ?: return null
            classpathCaches[path] = bytes
            return Pair(bytes, bootTime.time)
        }
        return null
    }
}