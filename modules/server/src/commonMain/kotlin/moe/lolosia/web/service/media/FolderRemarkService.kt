package moe.lolosia.web.service.media

import io.github.smyrgeorge.sqlx4k.QueryExecutor
import moe.lolosia.web.database.repository.FolderRemarkRepository
import moe.lolosia.web.dto.MediaFolderRemark
import moe.lolosia.web.dto.MediaFolderRemarkReq
import moe.lolosia.web.dto.MessageResult
import moe.lolosia.web.service.files.FileBrowser
import moe.lolosia.web.util.router.Injectable
import moe.lolosia.web.util.router.Service
import moe.lolosia.web.util.session.Context
import kotlin.time.Clock

/**
 * **文件夹备注**的读写（`folder_remark` 表）。
 *
 * 与文件的 `caption` 对齐：一段自由文本，人工可改。目录不在 `media_asset` 里，所以单独按**相对路径**
 * 存取。两个用途：
 * - 前端右侧属性面板选中目录时展示 / 编辑；
 * - 外发分析把「图片所属文件夹」的备注作为上下文喂给模型（见 [remarkOf]，无需 [Context]）。
 *
 * @author 洛洛希雅Lolosia
 */
@Injectable
@Service
class FolderRemarkService(
    private val browser: FileBrowser,
    private val repo: FolderRemarkRepository,
) {

    /** 某个目录的备注。`path` 为空串表示浏览根。 */
    suspend fun get(ctx: Context, path: String?): MediaFolderRemark {
        val relative = browser.requireDir(path)
        return MediaFolderRemark(relative, remarkOf(ctx.db, relative).orEmpty())
    }

    /** 保存备注：有则更新、无则插入；文本为空即清除（软删）。 */
    suspend fun save(ctx: Context, req: MediaFolderRemarkReq): MessageResult {
        val relative = browser.requireDir(req.path)
        val remark = req.remark?.trim().orEmpty()
        val now = Clock.System.now()
        val operator = ctx.userId
        val existing = repo.findOneByPath(ctx.db, relative).getOrThrow()
        if (remark.isEmpty()) {
            if (existing != null) repo.executeSoftDeleteById(ctx.db, existing.id, now, operator).getOrThrow()
            return MessageResult("已清除文件夹备注")
        }
        if (existing == null) {
            repo.executeInsert(ctx.db, relative, remark, now, operator, now, operator).getOrThrow()
        } else {
            repo.executeUpdateRemarkById(ctx.db, existing.id, remark, now, operator).getOrThrow()
        }
        return MessageResult("已保存文件夹备注")
    }

    /**
     * 按路径取备注，**不需要 [Context]** —— 供外发分析等后台场景使用。
     *
     * 空串与全空白都当「没有备注」返回 `null`。
     */
    suspend fun remarkOf(exec: QueryExecutor, path: String): String? =
        repo.findOneByPath(exec, path).getOrNull()?.remark?.trim()?.ifEmpty { null }
}
