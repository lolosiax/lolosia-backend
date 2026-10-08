package moe.lolosia.web.controller

import moe.lolosia.web.dto.MediaDetail
import moe.lolosia.web.dto.MediaIndexScanReq
import moe.lolosia.web.dto.MediaPropertyApplyReq
import moe.lolosia.web.dto.MediaPropertyReq
import moe.lolosia.web.dto.MessageResult
import moe.lolosia.web.service.files.normalizeRelative
import moe.lolosia.web.service.media.MEDIA_INDEX_EVENT
import moe.lolosia.web.service.media.MEDIA_READ
import moe.lolosia.web.service.media.MEDIA_WRITE
import moe.lolosia.web.service.media.MediaIndexService
import moe.lolosia.web.service.media.MediaPropertyService
import moe.lolosia.web.service.media.MediaQueryService
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
 * - `media/detail` 与 `media/property/…` 是**属性页**用的：详情读全量元数据，属性页写评级 / 描述；
 * - `media/index/scan` 只做**本地索引**（stat / 哈希 / EXIF / 规则打标），**不出网**；
 *   外发分析要等用户显式点「全盘分析」，那是 P3 的 `media/analyze/…`。
 *
 * §11.2 里 P2 的标签词表（`tags/…`、`tag/…`）、检索（`search` / `facets` / `manifest`）
 * 与 P4 的向量（`similar`）暂未落地，接口路径新增即定，届时按同一张表补齐。
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
}
