package moe.lolosia.web.service.media

import moe.lolosia.web.database.repository.MediaAssetRepository
import moe.lolosia.web.dto.ApiException
import moe.lolosia.web.dto.MediaPropertyApplyReq
import moe.lolosia.web.dto.MediaPropertyReq
import moe.lolosia.web.dto.MessageResult
import moe.lolosia.web.service.files.FileBrowser
import moe.lolosia.web.util.router.Injectable
import moe.lolosia.web.util.router.Service
import moe.lolosia.web.util.session.Context
import kotlin.time.Clock

/**
 * 属性编辑页的写入端（`doc/media-index-design.md` §10.1）。
 *
 * 两条行为约定在这里落地：
 * 1. **`rating = explicit` ⇒ `analysisSkip` 强制为真**（界面上该勾选框自动置灰打勾）；
 *    反向不成立 —— 「这张不涉黄，但我就是不想让它上云」是合法状态；
 * 2. 评级写入来源恒为 [SOURCE_MANUAL]：**人工一旦定过级，规则与模型都不许再改它**（§7.3）。
 *
 * 批量定级是**服务侧循环单条**，不是新的批量语义，也不包大事务 —— 一批几千张图单事务太久，
 * 而且中途失败时「已改的那些」本来就是对的，重跑即可。
 *
 * @author 洛洛希雅Lolosia
 */
@Injectable
@Service
class MediaPropertyService(
    private val browser: FileBrowser,
    private val assets: MediaAssetRepository,
    private val tagService: MediaTagService,
) {

    /** 属性页一次性提交：评级 / 外发策略 / 描述，`null` 的字段保持原值。 */
    suspend fun save(ctx: Context, req: MediaPropertyReq): MessageResult {
        val (relative, _) = browser.requirePath(req.path)
        if (relative.isEmpty()) throw ApiException(400, "缺少文件路径")
        val row = assets.findOneByPath(ctx.db, relative).getOrThrow()
            ?: throw ApiException(404, "该文件尚未建立索引，请先跑一轮索引扫描")
        val operator = ctx.userId
        val now = Clock.System.now()

        val rating = req.rating?.let { RatingPolicy.normalize(it) } ?: row.rating
        val ratingSource = if (req.rating != null) SOURCE_MANUAL else row.ratingSource
        val analysisSkip = RatingPolicy.outboundBlocked(rating, req.analysisSkip ?: row.analysisSkip)
        if (rating != row.rating || ratingSource != row.ratingSource || analysisSkip != row.analysisSkip) {
            assets.executeUpdateRatingById(ctx.db, row.id, rating, ratingSource, analysisSkip, now, operator)
                .getOrThrow()
        }
        if (req.rating != null) tagService.projectRating(ctx.db, row.id, rating, SOURCE_MANUAL, operator)
        if (req.caption != null) {
            assets.executeUpdateCaptionById(
                ctx.db, row.id, req.caption.trim().ifEmpty { null }, SOURCE_MANUAL, now, operator,
            ).getOrThrow()
        }
        return MessageResult("已保存")
    }

    /** 把同一套评级应用到本目录（可选含子目录）。 */
    suspend fun apply(ctx: Context, req: MediaPropertyApplyReq): MessageResult {
        val (relative, _) = browser.requirePath(req.path)
        val rating = req.rating?.let { RatingPolicy.normalize(it) }
        if (rating == null && req.analysisSkip == null) throw ApiException(400, "没有要应用的改动")
        val dirs = if (req.recursive) browser.collectDirs(relative) else listOf(relative)
        val operator = ctx.userId
        val now = Clock.System.now()
        var changed = 0
        for (dir in dirs) {
            val rows = assets.findAllByDirPath(ctx.db, dir).getOrElse { continue }
            for (row in rows) {
                val newRating = rating ?: row.rating
                val source = if (rating != null) SOURCE_MANUAL else row.ratingSource
                val skip = RatingPolicy.outboundBlocked(newRating, req.analysisSkip ?: row.analysisSkip)
                if (newRating == row.rating && source == row.ratingSource && skip == row.analysisSkip) continue
                assets.executeUpdateRatingById(ctx.db, row.id, newRating, source, skip, now, operator).getOrThrow()
                if (rating != null) tagService.projectRating(ctx.db, row.id, newRating, SOURCE_MANUAL, operator)
                changed++
            }
        }
        return MessageResult("已应用到 $changed 个文件")
    }
}
