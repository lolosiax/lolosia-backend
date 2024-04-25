package top.lolosia.web.service

import top.lolosia.web.LolosiaApplication
import top.lolosia.web.util.bundle.Bundle
import top.lolosia.web.util.bundle.bundleScope
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.MediaTypeFactory
import org.springframework.http.server.reactive.ServerHttpResponse
import org.springframework.stereotype.Service
import org.springframework.web.ErrorResponseException
import java.util.*
import kotlin.io.path.Path
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.readBytes

@Service
class HomeService {


    final val bootTime = Date()
    val classpathCaches = mutableMapOf<String, ByteArray>()
    val fileCaches = mutableMapOf<String, Pair<Long, ByteArray>>()

    /**
     * 列出可用平台列表
     */
    fun oAuthPlatforms(): Bundle {
        return platforms
    }

    val platforms by lazy {
        bundleScope {
            bundle("platforms") {
                // 智能决策平台
                if (checkPlatform("decisionGame")) {
                    "decision" set "/decisionGame/login"
                } else "decision" set "none"

                // 全球生产平台
                if (checkPlatform("produceGame")) {
                    "produce" set "/produceGame/login"
                } else "produce" set "none"

                // 销售平台
                if (checkPlatform("sellGame")) {
                    "sell" set "/sellGame/login"
                } else "sell" set "none"
            }
        }
    }

    private fun checkPlatform(platform: String): Boolean {
        if (Path("work/web/${platform}/index.html").exists()) {
            return true
        }
        return LolosiaApplication::class.java.getResource("/static/${platform}/index.html") != null
    }


    /**
     * 处理平台静态文件
     */
    fun handlePlatform(platform: String, file: String, resp: ServerHttpResponse): ByteArray {
        val files = listOf(
            "file" to "work/web/${platform}/${file}",
            "classpath" to "/static/${platform}/${file}",
            "file" to "work/web/${platform}/index.html",
            "classpath" to "/static/${platform}/index.html",
        )
        for ((type, path) in files) {
            val out: ByteArray = if (type == "classpath") {
                if (path.endsWith("/")) continue
                else if (classpathCaches.containsKey(path)) classpathCaches[path]!!
                else {
                    val bytes = LolosiaApplication::class.java.getResourceAsStream(path)?.readBytes() ?: continue
                    classpathCaches[path] = bytes
                    bytes
                }
            } else {
                val p = Path(path)
                if (!p.exists()) continue
                if (p.isDirectory()) continue
                val time = p.toFile().lastModified()
                if (fileCaches.containsKey(path)) {
                    if (time == fileCaches[path]!!.first) fileCaches[path]!!.second
                }
                val bytes = p.readBytes()
                fileCaches[path] = time to bytes
                bytes
            }

            resp.headers.contentType = MediaTypeFactory.getMediaType(path).orElse(MediaType.APPLICATION_OCTET_STREAM)
            resp.headers.contentLength = out.size.toLong()
            return out
        }
        throw ErrorResponseException(HttpStatus.NOT_FOUND)
    }

}