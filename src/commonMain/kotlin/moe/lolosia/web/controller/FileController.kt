package moe.lolosia.web.controller

import io.ktor.http.*
import io.ktor.http.content.*
import moe.lolosia.web.dto.ApiException
import moe.lolosia.web.dto.FileDetail
import moe.lolosia.web.dto.FileEntry
import moe.lolosia.web.dto.FileListResult
import moe.lolosia.web.dto.FileScanReq
import moe.lolosia.web.dto.MessageResult
import moe.lolosia.web.service.files.FileBrowser
import moe.lolosia.web.service.files.FileSigner
import moe.lolosia.web.service.files.ThumbnailService
import moe.lolosia.web.service.files.normalizeRelative
import moe.lolosia.web.util.router.*

/**
 * 在线文件管理器接口（只读）：列目录、看详情、取缩略图与原图。
 *
 * 路径一律用**相对浏览根的路径**做查询参数（`?path=a/b/c.jpg`），根目录是空串。
 *
 * `thumb` / `raw` 是给 `<img src>` 直接访问的，浏览器原生标签带不了 `Authorization` 头，
 * 所以它们**不走会话鉴权**（在 [moe.lolosia.web.interceptor.installAuthInterceptor] 的白名单里），
 * 改为校验 URL 签名：`exp` + `sig` 由 `list` / `detail` 下发，签名覆盖「路径 + 过期时间」，
 * 光知道路径猜不出签名（见 [FileSigner]）。其余接口都要求 `lolosia.files.read`。
 *
 * @author 洛洛希雅Lolosia
 */
@Controller("/api")
object FileController {

    @GET("/files/list")
    @Permission("lolosia.files.read")
    suspend fun list(
        browser: FileBrowser,
        @Query path: String?,
        @Query sort: String?,
        @Query order: String?,
    ): FileListResult = browser.list(path, sort, order)

    /** 只列子目录：目录树懒加载用，比列全量轻。 */
    @GET("/files/dirs")
    @Permission("lolosia.files.read")
    suspend fun dirs(browser: FileBrowser, @Query path: String?): List<FileEntry> = browser.dirs(path)

    /** 单个文件的详情（预览面板）。 */
    @GET("/files/detail")
    @Permission("lolosia.files.read")
    suspend fun detail(browser: FileBrowser, @Query path: String?): FileDetail = browser.detail(path)

    /** 请求把某个目录的图片插队生成缩略图（前端打开目录时调）。 */
    @POST("/files/scan")
    @Permission("lolosia.files.read")
    suspend fun scan(thumbs: ThumbnailService, @Body req: FileScanReq): MessageResult {
        if (!thumbs.enabled) return MessageResult("缩略图生成未启用")
        normalizeRelative(req.path)?.let { thumbs.prioritize(it) }
        return MessageResult("已排队")
    }

    /**
     * 取缩略图（WebP）。`size` 不在配置档位里时回退到最小档。
     *
     * 尚未生成时返回 404：前端据此继续显示占位图，等 `files:thumb` 事件到了再重新拉。
     */
    @GET("/files/thumb")
    suspend fun thumb(
        browser: FileBrowser,
        signer: FileSigner,
        @Query path: String?,
        @Query size: String?,
        @Query exp: String?,
        @Query sig: String?,
    ): OutgoingContent {
        val relative = requireSignedPath(signer, path, exp, sig)
        val sizes = browser.cache.orderedSizes
        val target = size?.toIntOrNull()?.takeIf { it in sizes } ?: sizes.first()
        val bytes = browser.cache.read(relative, target) ?: throw ApiException(404, "缩略图尚未生成")
        return ByteArrayContent(bytes, ContentType.parse("image/webp"))
    }

    /**
     * 取原图 / 原文件（缩略图还没生成时的回退，以及「看原图」）。
     *
     * 整个文件读进内存再返回：文件管理器面向的是照片，单张几 MB 可以接受；
     * 真要传大文件应该走 WebDAV。
     */
    @GET("/files/raw")
    suspend fun raw(
        browser: FileBrowser,
        signer: FileSigner,
        @Query path: String?,
        @Query exp: String?,
        @Query sig: String?,
    ): OutgoingContent {
        val relative = requireSignedPath(signer, path, exp, sig)
        val entry = browser.stat(relative) ?: throw ApiException(404, "文件不存在")
        if (entry.directory) throw ApiException(400, "这是一个目录")
        val bytes = browser.readSource(relative) ?: throw ApiException(404, "读取失败")
        return ByteArrayContent(bytes, ContentType.parse(entry.mime))
    }

    /** 校验直链签名，返回合法的相对路径。 */
    private fun requireSignedPath(signer: FileSigner, path: String?, exp: String?, sig: String?): String {
        val relative = normalizeRelative(path) ?: throw ApiException(400, "路径非法")
        if (relative.isEmpty()) throw ApiException(400, "缺少文件路径")
        if (!signer.verify(relative, exp?.toLongOrNull(), sig)) throw ApiException(403, "直链签名无效或已过期")
        return relative
    }
}
