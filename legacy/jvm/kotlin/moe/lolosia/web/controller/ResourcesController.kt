package moe.lolosia.web.controller

import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.toHttpDate
import io.ktor.server.response.header
import io.ktor.server.response.respondFile
import io.ktor.server.routing.Routing
import io.ktor.server.routing.get
import io.ktor.util.date.GMTDate
import moe.lolosia.web.annotation.Route
import moe.lolosia.web.service.ResourcesService
import moe.lolosia.web.util.ErrorResponseException
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.io.File

/**
 * 静态资源控制器：`/res/resources/` → `work/resources/`。
 *
 * - 只匹配 /res/resources/ 前缀，其余 /res/... 路径由项目自己的路由注册。
 * - 无内存缓存（直接读文件系统）；Last-Modified/ETag → 304 由 ConditionalHeaders 插件处理。
 * - ?webp=true 时经 ResourcesService 走 WebP 磁盘缓存。
 *
 * @author 洛洛希雅Lolosia
 * @since 2026-01-16 17:46
 */
object ResourcesController : KoinComponent {

    private val resourcesService: ResourcesService by inject()

    @Route
    fun serve(routing: Routing) {
        routing.get("/res/resources/{path...}") {
            val path = call.parameters.getAll("path")?.joinToString(File.separator)
            val webp = call.request.queryParameters["webp"] != null
            val file = path?.let { resourcesService.resolve("resources/$it", webp) }
                ?: throw ErrorResponseException(HttpStatusCode.NotFound, "资源不存在")
            // 缓存协商头：前端可据此用 If-Modified-Since/If-None-Match 触发 304，减少请求
            call.response.header(HttpHeaders.CacheControl, "max-age=3600")
            call.response.header(HttpHeaders.LastModified, GMTDate(file.lastModified()).toHttpDate())
            call.response.header(HttpHeaders.ETag, "\"${file.lastModified()}-${file.length()}\"")
            call.respondFile(file)
        }
    }
}
