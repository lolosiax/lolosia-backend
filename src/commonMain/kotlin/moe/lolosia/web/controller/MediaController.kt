package moe.lolosia.web.controller

import moe.lolosia.web.dto.MediaAnalyzeReq
import moe.lolosia.web.dto.MediaAnalyzeStatus
import moe.lolosia.web.dto.MediaAnalyzeUsage
import moe.lolosia.web.dto.MediaDetail
import moe.lolosia.web.dto.MediaIndexScanReq
import moe.lolosia.web.dto.MediaPropertyApplyReq
import moe.lolosia.web.dto.MediaPropertyReq
import moe.lolosia.web.dto.MediaTagItem
import moe.lolosia.web.dto.MediaTagsReq
import moe.lolosia.web.dto.MessageResult
import moe.lolosia.web.service.files.normalizeRelative
import moe.lolosia.web.service.media.FolderTagService
import moe.lolosia.web.service.media.MEDIA_INDEX_EVENT
import moe.lolosia.web.service.media.MEDIA_READ
import moe.lolosia.web.service.media.MEDIA_WRITE
import moe.lolosia.web.service.media.MediaAnalyzeService
import moe.lolosia.web.service.media.MediaIndexService
import moe.lolosia.web.service.media.MediaPropertyService
import moe.lolosia.web.service.media.MediaQueryService
import moe.lolosia.web.service.media.TagQueryService
import moe.lolosia.web.util.router.Body
import moe.lolosia.web.util.router.Controller
import moe.lolosia.web.util.router.GET
import moe.lolosia.web.util.router.POST
import moe.lolosia.web.util.router.Permission
import moe.lolosia.web.util.router.Query
import moe.lolosia.web.util.session.Context

/**
 * 媒体索引接口：属性页读写与本地索引扫描（`doc/media-index-design.md` §11.2 的 P1 子集）。
 *
 * - `media/detail` 与 `media/property/…`、`media/tags/…` 是**属性页**用的：详情读全量元数据，
 *   属性页写评级 / 描述，以及人工打标 / 解除 / 否决；
 * - `media/index/scan` 只做**本地索引**（stat / 哈希 / EXIF / 规则打标），**不出网**；
 *   外发分析要等用户显式点「全盘分析」，那是 P3 的 `media/analyze/…`。
 *
 * §11.2 里 P2 剩下的**词表管理**（`tag/tree`、`tag/rename`、`tag/move`、`tag/merge`）
 * 与检索（`search` / `facets` / `manifest`）、P4 的向量（`similar`）暂未落地，
 * 接口路径新增即定，届时按同一张表补齐。
 *
 * @author 洛洛希雅Lolosia
 */
@Controller("/api")
object MediaController {

    /** 属性页：一个文件的完整元数据 + 标签 + 评级。 */
    @GET("/media/detail")
    @Permission(MEDIA_READ)
    suspend fun detail(ctx: Context, query: MediaQueryService, @Query path: String?): MediaDetail =
        query.detail(ctx, path)

    /** 属性页：保存评级 / 外发策略 / 描述。 */
    @POST("/media/property/save")
    @Permission(MEDIA_WRITE)
    suspend fun save(
        ctx: Context,
        property: MediaPropertyService,
        @Body req: MediaPropertyReq,
    ): MessageResult = property.save(ctx, req)

    /** 属性页：把同一套评级应用到本目录（含子目录）。 */
    @POST("/media/property/apply")
    @Permission(MEDIA_WRITE)
    suspend fun apply(
        ctx: Context,
        property: MediaPropertyService,
        @Body req: MediaPropertyApplyReq,
    ): MessageResult = property.apply(ctx, req)

    /** 属性页：人工打标（来源 `manual`；标签不存在则按需建，§7.5）。 */
    @POST("/media/tags/attach")
    @Permission(MEDIA_WRITE)
    suspend fun attachTags(
        ctx: Context,
        property: MediaPropertyService,
        @Body req: MediaTagsReq,
    ): MessageResult = property.attachTags(ctx, req)

    /** 属性页：解除标签关联（只摘关联、不删标签；规则下次扫描可能再加回来）。 */
    @POST("/media/tags/detach")
    @Permission(MEDIA_WRITE)
    suspend fun detachTags(
        ctx: Context,
        property: MediaPropertyService,
        @Body req: MediaTagsReq,
    ): MessageResult = property.detachTags(ctx, req)

    /** 属性页：人工否决（`negative = 1`，规则与模型都不会再加回来，§7.3）。 */
    @POST("/media/tags/deny")
    @Permission(MEDIA_WRITE)
    suspend fun denyTags(
        ctx: Context,
        property: MediaPropertyService,
        @Body req: MediaTagsReq,
    ): MessageResult = property.denyTags(ctx, req)

    /**
     * **文件夹**标签：读。
     *
     * 目录不在 `media_asset` 里，属性页那套（`media/detail` 与 `media/tags/…`）挂不到它身上，
     * 所以这条单独按相对路径取（`folder_tag` 表）。`path` 为空串表示浏览根。
     */
    @GET("/media/folder-tags")
    @Permission(MEDIA_READ)
    suspend fun folderTags(
        ctx: Context,
        folderTags: FolderTagService,
        @Query path: String?,
    ): List<MediaTagItem> = folderTags.list(ctx, path)

    /** 文件夹标签：打上（来源 `manual`，标签不存在则按需建）。 */
    @POST("/media/folder-tags/attach")
    @Permission(MEDIA_WRITE)
    suspend fun attachFolderTags(
        ctx: Context,
        folderTags: FolderTagService,
        @Body req: MediaTagsReq,
    ): MessageResult = folderTags.attach(ctx, req)

    /** 文件夹标签：解除关联（只摘关联，不删词表里的标签）。 */
    @POST("/media/folder-tags/detach")
    @Permission(MEDIA_WRITE)
    suspend fun detachFolderTags(
        ctx: Context,
        folderTags: FolderTagService,
        @Body req: MediaTagsReq,
    ): MessageResult = folderTags.detach(ctx, req)

    /**
     * 标签选择器：全量词表（带命名空间），供控件按段浏览。
     *
     * 控件只在获得焦点时拉，不在页面加载时拉（见 `TagQueryService.vocabulary`）。
     */
    @GET("/media/tags")
    @Permission(MEDIA_READ)
    suspend fun tagVocabulary(ctx: Context, tags: TagQueryService): List<MediaTagItem> =
        tags.vocabulary(ctx)

    /**
     * 标签选择器：某个目录里**已经在用**的标签（目录下文件用过的 + 目录自己的），
     * 给控件把「这个文件夹用过的」顶到最前面。`path` 为空串表示浏览根。
     */
    @GET("/media/dir-tags")
    @Permission(MEDIA_READ)
    suspend fun directoryTags(
        ctx: Context,
        tags: TagQueryService,
        @Query path: String?,
    ): List<MediaTagItem> = tags.directoryTags(ctx, path)

    /**
     * 请求本地索引扫描：`path` 为空表示整个浏览根。
     *
     * 前端把它当「刷新索引」按钮用；真正的元数据与评级会在扫完后由 `media:index` 事件推回来。
     */
    @POST("/media/index/scan")
    @Permission(MEDIA_READ)
    suspend fun scan(index: MediaIndexService, @Body req: MediaIndexScanReq): MessageResult {
        if (!index.enabled) return MessageResult("媒体索引未启用")
        index.requestScan(normalizeRelative(req.path))
        return MessageResult("已排队，元数据与评级将通过 $MEDIA_INDEX_EVENT 推送")
    }

    /**
     * 对某范围发起**外发分析**（视觉自动打标，`doc/vision-autotag-design.md`）。
     *
     * 与 `media/index/scan` 的区别：那个只做本地索引（不出网），这个会把素材交给外部模型。
     * 需要写入权限（会改标签与描述），且受 `files.media.vision.enabled` 总开关约束。
     */
    @POST("/media/analyze/start")
    @Permission(MEDIA_WRITE)
    suspend fun analyzeStart(
        ctx: Context,
        analyze: MediaAnalyzeService,
        @Body req: MediaAnalyzeReq,
    ): MessageResult = analyze.start(req, ctx.userId)

    /** 外发分析：取消排队（正在处理的批会自然跑完）。 */
    @POST("/media/analyze/cancel")
    @Permission(MEDIA_WRITE)
    suspend fun analyzeCancel(analyze: MediaAnalyzeService): MessageResult = analyze.cancel()

    /** 外发分析：运行状态快照。 */
    @GET("/media/analyze/status")
    @Permission(MEDIA_READ)
    suspend fun analyzeStatus(analyze: MediaAnalyzeService): MediaAnalyzeStatus = analyze.status()

    /** 外发分析：token 台账（全局累计 + 按 provider 汇总 + 今日估算花费）。 */
    @GET("/media/analyze/usage")
    @Permission(MEDIA_READ)
    suspend fun analyzeUsage(analyze: MediaAnalyzeService): MediaAnalyzeUsage = analyze.usage()
}
