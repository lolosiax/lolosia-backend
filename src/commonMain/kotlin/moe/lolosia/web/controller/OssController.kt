package moe.lolosia.web.controller

import io.ktor.http.*
import io.ktor.http.content.*
import moe.lolosia.web.dto.*
import moe.lolosia.web.service.oss.UserFileService
import moe.lolosia.web.util.router.*
import moe.lolosia.web.util.session.Context

/**
 * OSS 文件接口（路径与出参形状**以前端 `E:\Dev\Node\lolosia-web` 的 `src/api/oss.ts` 为准**）。
 *
 * 上传 `POST /api/oss/upload` 是 multipart：多个 `file` 文件项 + 多个 `reference` 字段
 * （本地已存在、无需重传的 md5）+ 单个 `service` 字段 —— 前者绑成 `List<UploadedFile>`、
 * 后者绑成 `List<String>`（见 `FormData.fileValues` / `fieldValues`）。
 *
 * 下载 `GET /api/oss/get/{user}/{md5}` 在鉴权白名单的前缀里（`<img src>` 带不了请求头），
 * 归属用户由路径给出。
 *
 * @author 洛洛希雅Lolosia
 */
@Controller("/api")
object OssController {

    @POST("/oss/list")
    suspend fun list(ctx: Context, files: UserFileService, @Body req: OssListReq): List<OssFile> =
        files.list(ctx, req.service)

    @POST("/oss/getMetas")
    suspend fun getMetas(ctx: Context, files: UserFileService, @Body req: OssMetasReq): List<OssFile> =
        files.getMetas(ctx, req.service, req.md5)

    @POST("/oss/fileCheck")
    suspend fun fileCheck(ctx: Context, files: UserFileService, @Body req: OssCheckReq): List<Boolean> =
        files.check(ctx, req.md5)

    @POST("/oss/upload")
    suspend fun upload(
        ctx: Context,
        files: UserFileService,
        @FilePart("file") uploads: List<UploadedFile>,
        @Form("reference") references: List<String>,
        @Form("service") service: String?,
    ): MessageResult = files.upload(ctx, uploads, references, service, ctx.userId)

    @GET("/oss/get/{user}/{md5}")
    suspend fun get(
        ctx: Context,
        files: UserFileService,
        @Path user: String,
        @Path md5: String,
    ): OutgoingContent {
        val content = files.read(ctx, user, md5)
        return ByteArrayContent(content.bytes, contentTypeFor(content.name))
    }

    /** 按扩展名给 MIME；未知类型一律 `application/octet-stream`（下载而非内联预览）。 */
    private fun contentTypeFor(name: String): ContentType =
        when (name.substringAfterLast('.', "").lowercase()) {
            "png" -> ContentType.Image.PNG
            "jpg", "jpeg" -> ContentType.Image.JPEG
            "gif" -> ContentType.Image.GIF
            "webp" -> ContentType.parse("image/webp")
            "pdf" -> ContentType.Application.Pdf
            "txt" -> ContentType.Text.Plain
            "json" -> ContentType.Application.Json
            else -> ContentType.Application.OctetStream
        }
}
