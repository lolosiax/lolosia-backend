package moe.lolosia.web.controller

import io.ktor.http.*
import io.ktor.http.content.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.utils.io.readAvailable
import moe.lolosia.web.dto.ApiException
import moe.lolosia.web.dto.FileDeleteReq
import moe.lolosia.web.dto.FileDetail
import moe.lolosia.web.dto.FileEntry
import moe.lolosia.web.dto.FileListResult
import moe.lolosia.web.dto.FileMkdirReq
import moe.lolosia.web.dto.FileOpResult
import moe.lolosia.web.dto.FileRegenerateThumbReq
import moe.lolosia.web.dto.FileRenameReq
import moe.lolosia.web.dto.FileScanReq
import moe.lolosia.web.dto.FileTransferReq
import moe.lolosia.web.dto.FileTransferResult
import moe.lolosia.web.dto.MessageResult
import moe.lolosia.web.service.files.FileBrowser
import moe.lolosia.web.service.files.FileSigner
import moe.lolosia.web.service.files.ThumbnailService
import moe.lolosia.web.service.files.normalizeRelative
import moe.lolosia.web.service.media.MediaQueryService
import moe.lolosia.web.util.ktor.*
import moe.lolosia.web.util.router.*
import moe.lolosia.web.util.session.Context

/** 上传时读写请求体的缓冲区大小：一次 64 KiB，够大又不至于让每块都白占内存。 */
private const val UPLOAD_BUFFER_SIZE = 64 * 1024

/**
 * 在线文件管理器接口：列目录、看详情、取缩略图与原图，以及**改名 / 新建目录 / 删除 / 上传 / 复制 / 移动**。
 *
 * 路径一律用**相对浏览根的路径**做查询参数（`?path=a/b/c.jpg`），根目录是空串。
 *
 * `thumb` / `raw` 是给 `<img src>` 直接访问的，浏览器原生标签带不了 `Authorization` 头，
 * 所以它们**不走会话鉴权**（在 [moe.lolosia.web.interceptor.installAuthInterceptor] 的白名单里），
 * 改为校验 URL 签名：`exp` + `sig` 由 `list` / `detail` 下发，签名覆盖「路径 + 过期时间」，
 * 光知道路径猜不出签名（见 [FileSigner]）。其余接口都要求 `lolosia.files.read`；
 * 写操作（`rename` / `mkdir` / `delete` / `upload` / `copy` / `move`）额外要求 `lolosia.files.write`。
 *
 * @author 洛洛希雅Lolosia
 */
@Controller("/api")
object FileController {

    @GET("/files/list")
    @Permission("lolosia.files.read")
    suspend fun list(
        ctx: Context,
        browser: FileBrowser,
        media: MediaQueryService,
        @Query path: String?,
        @Query sort: String?,
        @Query order: String?,
    ): FileListResult = media.enrich(ctx, browser.list(path, sort, order))

    /** 只列子目录：目录树懒加载用，比列全量轻。 */
    @GET("/files/dirs")
    @Permission("lolosia.files.read")
    suspend fun dirs(browser: FileBrowser, @Query path: String?): List<FileEntry> = browser.dirs(path)

    /** 单个文件的详情（预览面板）。 */
    @GET("/files/detail")
    @Permission("lolosia.files.read")
    suspend fun detail(
        ctx: Context,
        browser: FileBrowser,
        media: MediaQueryService,
        @Query path: String?,
    ): FileDetail = media.enrich(ctx, browser.detail(path))

    /** 请求把某个目录的图片插队生成缩略图（前端打开目录时调）。 */
    @POST("/files/scan")
    @Permission("lolosia.files.read")
    suspend fun scan(thumbs: ThumbnailService, @Body req: FileScanReq): MessageResult {
        if (!thumbs.enabled) return MessageResult("缩略图生成未启用")
        normalizeRelative(req.path)?.let { thumbs.prioritize(it) }
        return MessageResult("已排队")
    }

    /**
     * 强制重算一批文件的缩略图（右键「重新生成缩略图」）：清缓存后插队重建。
     *
     * 用在「缩略图与当前配置对不上」之后 —— 例如某张图刚被标上 `feature:不裁剪`、或改过裁剪参数。
     * 后台重建时会现查该文件的标记，所以先标后重建即可让旧图跟上最新规则。
     */
    @POST("/files/thumb/regenerate")
    @Permission("lolosia.files.read")
    suspend fun regenerateThumbs(thumbs: ThumbnailService, @Body req: FileRegenerateThumbReq): MessageResult {
        if (!thumbs.enabled) return MessageResult("缩略图生成未启用")
        thumbs.regenerate(req.paths)
        return MessageResult("已重新排队 ${req.paths.size} 个文件")
    }

    /**
     * 重命名**目录或文件**（同级改名，不移动）。
     *
     * 返回改名后的相对路径：前端据此在「当前路径就在这个目录里」时换掉前缀。
     */
    @POST("/files/rename")
    @Permission("lolosia.files.write")
    suspend fun rename(browser: FileBrowser, @Body req: FileRenameReq): FileOpResult =
        browser.rename(req.path, req.name)

    /** 在某个目录下新建子目录。 */
    @POST("/files/mkdir")
    @Permission("lolosia.files.write")
    suspend fun mkdir(browser: FileBrowser, @Body req: FileMkdirReq): FileOpResult =
        browser.mkdir(req.path, req.name)

    /** 删除文件或整个目录（含子内容）。 */
    @POST("/files/delete")
    @Permission("lolosia.files.write")
    suspend fun delete(browser: FileBrowser, @Body req: FileDeleteReq): FileOpResult =
        browser.delete(req.path)

    /**
     * 把一批项**复制**到目标目录（多选后粘贴的实现，见 [FileBrowser.transfer]）。
     *
     * 目标目录里重名时自动加序号，不覆盖；单项失败不中断整批，逐条报在结果里。
     */
    @POST("/files/copy")
    @Permission("lolosia.files.write")
    suspend fun copy(browser: FileBrowser, @Body req: FileTransferReq): FileTransferResult =
        browser.transfer(req.paths, req.dest, copy = true)

    /** 把一批项**移动**到目标目录（剪切后粘贴的实现），语义与 [copy] 一致，只是搬走源。 */
    @POST("/files/move")
    @Permission("lolosia.files.write")
    suspend fun move(browser: FileBrowser, @Body req: FileTransferReq): FileTransferResult =
        browser.transfer(req.paths, req.dest, copy = false)

    /**
     * 上传**单个文件**：`path` 是目标目录的相对路径（空串 = 浏览根），`name` 是原始文件名，
     * **请求体就是这个文件本身**（不是 multipart）。
     *
     * 之所以不用 multipart：`readForm()` 会把整个请求读进内存，机械盘上随手拖一个几 GB 的视频
     * 就能把板子撑爆。裸体会话体则能像 WebDAV 的 PUT 一样，边收边写进**目标目录下的临时文件**
     * （见 `WebDavRouting.davPut`），内存占用与文件大小无关。
     *
     * 目标目录里已有同名文件时自动加序号，不覆盖（见 [FileBrowser.uploadTarget]）。
     */
    @POST("/files/upload")
    @Permission("lolosia.files.write")
    suspend fun upload(
        ctx: Context,
        browser: FileBrowser,
        @Query path: String?,
        @Query name: String?,
    ): FileOpResult {
        val target = browser.uploadTarget(path, name ?: throw ApiException(400, "缺少文件名参数 name"))
        val channel = ctx.call.receiveChannel()
        val sink = browser.openSink(target.temp)
        var success = false
        try {
            val buffer = ByteArray(UPLOAD_BUFFER_SIZE)
            while (true) {
                val read = channel.readAvailable(buffer, 0, buffer.size)
                if (read == -1) break
                if (read > 0) sink.write(buffer, 0, read)
            }
            sink.flush()
            success = true
        } finally {
            sink.close()
            // 无论成败都要收尾：成功则把临时文件改名进目标目录，失败则把它删掉
            browser.finishUpload(target, success)
        }
        return FileOpResult(target.relative, target.name)
    }

    /**
     * 取缩略图（WebP）。`size` 不在配置档位里时回退到最小档。
     *
     * `size=full` 是特例：要的是**原始分辨率的 WebP 预览**（只换编码不缩放），首次请求时现生成
     * 一次并落盘缓存 —— 全屏查看器默认用它，档位缩略图最大只有 1024，屏幕一大就被放大糊掉。
     *
     * 尚未生成时返回 404：前端据此继续显示占位图，等 `files:thumb` 事件到了再重新拉。
     */
    @GET("/files/thumb")
    suspend fun thumb(
        browser: FileBrowser,
        thumbs: ThumbnailService,
        signer: FileSigner,
        @Query path: String?,
        @Query size: String?,
        @Query exp: String?,
        @Query sig: String?,
    ): OutgoingContent {
        val relative = requireSignedPath(signer, path, exp, sig)
        if (size == ThumbnailService.FULL_SIZE) {
            val bytes = thumbs.fullPreview(relative) ?: throw ApiException(404, "预览图尚未生成")
            return ByteArrayContent(bytes, ContentType.parse("image/webp"))
        }
        val sizes = browser.cache.orderedSizes
        val target = size?.toIntOrNull()?.takeIf { it in sizes } ?: sizes.first()
        val bytes = browser.cache.read(relative, target) ?: throw ApiException(404, "缩略图尚未生成")
        return ByteArrayContent(bytes, ContentType.parse("image/webp"))
    }

    /**
     * 取原图 / 原文件（缩略图还没生成时的回退、「看原图」，以及视频播放）。
     *
     * 支持 `Range`：流式分块下发，`Accept-Ranges: bytes` + `206 Partial Content` +
     * `Content-Range`（不可满足回 416）。视频播放器靠它拖进度条、边下边播；
     * 不支持 Range 时每次都得把整个文件读进内存，几百 MB 的视频会把板子撑爆。
     *
     * `download=1` 会补一个 `Content-Disposition: attachment`：浏览器的 `<a download>` 在跨源时会被忽略
     * （本地 dev 前端 5005、后端 7129），只有靠响应头才真能存成文件。
     */
    @GET("/files/raw")
    suspend fun raw(
        ctx: Context,
        browser: FileBrowser,
        signer: FileSigner,
        @Query path: String?,
        @Query exp: String?,
        @Query sig: String?,
        @Query download: String?,
    ): OutgoingContent {
        val relative = requireSignedPath(signer, path, exp, sig)
        val entry = browser.stat(relative) ?: throw ApiException(404, "文件不存在")
        if (entry.directory) throw ApiException(400, "这是一个目录")

        val contentType = ContentType.parse(entry.mime)
        val size = entry.size
        ctx.call.response.header(HttpHeaders.AcceptRanges, "bytes")
        if (download == "1") {
            ctx.call.response.header(HttpHeaders.ContentDisposition, attachmentDisposition(entry.name))
        }

        fun content(offset: Long, length: Long, status: HttpStatusCode) = FileRangeContent(
            length = length,
            offset = offset,
            contentType = contentType,
            status = status,
        ) { browser.openHandle(relative) ?: throw ApiException(404, "读取失败") }

        return when (val range = parseRange(ctx.call.request.headers[HttpHeaders.Range], size)) {
            RangeParse.None -> content(0L, size, HttpStatusCode.OK)

            RangeParse.Unsatisfiable -> {
                ctx.call.response.header(HttpHeaders.ContentRange, "bytes */$size")
                throw ApiException(416, "Range 不可满足")
            }

            is RangeParse.Ok -> {
                ctx.call.response.header(
                    HttpHeaders.ContentRange,
                    "bytes ${range.start}-${range.endInclusive}/$size",
                )
                content(range.start, range.endInclusive - range.start + 1, HttpStatusCode.PartialContent)
            }
        }
    }

    /** 校验直链签名，返回合法的相对路径。 */
    private fun requireSignedPath(signer: FileSigner, path: String?, exp: String?, sig: String?): String {
        val relative = normalizeRelative(path) ?: throw ApiException(400, "路径非法")
        if (relative.isEmpty()) throw ApiException(400, "缺少文件路径")
        if (!signer.verify(relative, exp?.toLongOrNull(), sig)) throw ApiException(403, "直链签名无效或已过期")
        return relative
    }

    /**
     * RFC 6266 的下载头：`filename*` 用 RFC 5987 的 UTF-8 百分号编码（中文名必须这么写），
     * 再配一个纯 ASCII 的 `filename` 兜底给不认识 `filename*` 的老客户端。
     */
    private fun attachmentDisposition(name: String): String {
        val ascii = name.filter { it.code in 0x20..0x7E && it != '"' && it != '\\' }.ifBlank { "download" }
        return "attachment; filename=\"$ascii\"; filename*=UTF-8''${encode5987(name)}"
    }

    /** 除 RFC 5987 的 `attr-char` 外，一律按 UTF-8 字节转成 `%XX`。 */
    private fun encode5987(value: String): String {
        val builder = StringBuilder()
        for (byte in value.encodeToByteArray()) {
            val code = byte.toInt() and 0xFF
            val ch = code.toChar()
            val safe = ch in 'a'..'z' || ch in 'A'..'Z' || ch in '0'..'9' || ch in "!#$&+-.^_`|~"
            if (safe) builder.append(ch)
            else builder.append('%').append(code.toString(16).uppercase().padStart(2, '0'))
        }
        return builder.toString()
    }
}
