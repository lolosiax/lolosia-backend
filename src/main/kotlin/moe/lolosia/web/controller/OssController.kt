package moe.lolosia.web.controller

import cn.ktorfitx.server.annotation.Attribute
import cn.ktorfitx.server.annotation.Body
import cn.ktorfitx.server.annotation.Controller
import cn.ktorfitx.server.annotation.POST
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respond
import io.ktor.server.routing.Routing
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import kotlinx.serialization.Serializable
import moe.lolosia.web.annotation.Route
import moe.lolosia.web.dto.response.ApiResult
import moe.lolosia.web.dto.response.toJsonObject
import moe.lolosia.web.service.oss.UserFileService
import moe.lolosia.web.util.ErrorResponseException
import moe.lolosia.web.util.collectFileItems
import moe.lolosia.web.util.respondFileCached
import moe.lolosia.web.util.session.Context
import moe.lolosia.web.util.session.ContextKey
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

/**
 * OSS 文件服务控制器（上传/下载走手动路由，@Route 扫描注册，见 configureRouting）。
 *
 * @author 洛洛希雅Lolosia
 * @since 2026-08-30 15:37
 */
@Controller("/api")
object OssController : KoinComponent {

    private val userFileService: UserFileService by inject()

    @Serializable
    data class CheckFn(val md5: List<String>)

    @POST("/oss/fileCheck")
    suspend fun check(@Attribute("ctx") ctx: Context, @Body param: CheckFn): ApiResult<List<Boolean>> =
        ApiResult.ok(userFileService.fileCheck(ctx, param.md5))

    // ===== 手动路由（@Route 扫描注册） =====

    /** OSS 文件上传（手动路由：流式读取 multipart，避免 ktorfitx 收集整个 multipart 的文件为空问题） */
    @Route
    fun upload(routing: Routing) {
        routing.post("/api/oss/upload") {
            val ctx = call.attributes[ContextKey]
            val reference = call.request.queryParameters["reference"]
            val service = call.request.queryParameters["service"]
            val files = collectFileItems()
            val result = userFileService.uploadFromFileItems(
                ctx, files,
                reference?.split(",")?.filter { it.isNotBlank() } ?: emptyList(),
                service ?: "default"
            )
            // 手动路由经信封中间件统一包裹，Bundle 需先转 JsonObject
            call.respond(HttpStatusCode.OK, result.toJsonObject())
        }
    }

    /** OSS 文件访问（inline 预览，Last-Modified/ETag → 304 协商） */
    @Route
    fun show(routing: Routing) {
        routing.get("/api/oss/show/{md5}") {
            val ctx = call.attributes[ContextKey]
            val path = userFileService.getFilePath(ctx, call.parameters["md5"]!!)
                ?: throw ErrorResponseException(HttpStatusCode.NotFound, "文件不存在")
            call.respondFileCached(path.toFile())
        }
    }

    /** OSS 文件下载（Content-Disposition: attachment + 304 协商） */
    @Route
    fun download(routing: Routing) {
        routing.get("/api/oss/get/{md5}") {
            val ctx = call.attributes[ContextKey]
            val path = userFileService.getFilePath(ctx, call.parameters["md5"]!!)
                ?: throw ErrorResponseException(HttpStatusCode.NotFound, "文件不存在")
            call.respondFileCached(path.toFile(), downloadName = path.fileName.toString())
        }
    }
}
