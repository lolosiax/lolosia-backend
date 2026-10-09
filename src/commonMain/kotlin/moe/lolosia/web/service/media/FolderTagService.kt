package moe.lolosia.web.service.media

import moe.lolosia.web.database.repository.FolderTagRepository
import moe.lolosia.web.database.repository.MediaTagRepository
import moe.lolosia.web.dto.ApiException
import moe.lolosia.web.dto.MediaTagItem
import moe.lolosia.web.dto.MediaTagRef
import moe.lolosia.web.dto.MediaTagsReq
import moe.lolosia.web.dto.MessageResult
import moe.lolosia.web.service.files.FileBrowser
import moe.lolosia.web.util.router.Injectable
import moe.lolosia.web.util.router.Service
import moe.lolosia.web.util.session.Context
import kotlin.time.Clock

/**
 * **文件夹**标签的读写（`folder_tag` 表）。
 *
 * 目录不在 `media_asset` 里，所以文件的 `media/tags/…` 那套（要求先有索引行）套不到目录上；
 * 这里按**相对路径**直接挂标签，词表仍复用 `media_tag`。
 *
 * 用途是文件管理器的展示开关，不是检索：`feature:日期分组` 打开分组能力，
 * `feature:自动分组-年份` / `feature:自动分组-月份` 指定进入该目录时默认选用的分组模式。
 * 这些语义全部在前端解释，后端只做「路径 ↔ 标签」的存取。
 *
 * @author 洛洛希雅Lolosia
 */
@Injectable
@Service
class FolderTagService(
    private val browser: FileBrowser,
    private val tags: MediaTagRepository,
    private val tagService: MediaTagService,
    private val bindings: FolderTagRepository,
) {

    /** 某个目录身上的标签。`path` 为空串表示浏览根（根目录本身也可以被打标签）。 */
    suspend fun list(ctx: Context, path: String?): List<MediaTagItem> {
        val relative = browser.requireDir(path)
        val links = bindings.findAllByPath(ctx.db, relative).getOrThrow()
        if (links.isEmpty()) return emptyList()
        // 一个标签可能有多条历史行（软删后重打）：按 tagId 去重即可，目录没有来源优先级
        return links.map { it.tagId }.distinct().mapNotNull { tagId ->
            val tag = tags.findOneById(ctx.db, tagId).getOrThrow() ?: return@mapNotNull null
            MediaTagItem(
                id = tag.id,
                namespace = tag.namespace,
                name = tag.name,
                parentId = tag.parentId,
                source = SOURCE_MANUAL,
            )
        }.sortedBy { it.id }
    }

    /** 打标：来源恒为 [SOURCE_MANUAL]，标签不存在则按需建（与文件打标同一套 `ensureTag`）。 */
    suspend fun attach(ctx: Context, req: MediaTagsReq): MessageResult {
        val (relative, refs) = requireRequest(req)
        val operator = ctx.userId
        var changed = 0
        for (ref in refs) {
            if (attachOne(ctx, relative, ref, operator) != null) changed++
        }
        return MessageResult("已为文件夹打上 $changed 个标签")
    }

    /** 解除关联：只摘关联、不删词表里的标签。 */
    suspend fun detach(ctx: Context, req: MediaTagsReq): MessageResult {
        val (relative, refs) = requireRequest(req)
        val operator = ctx.userId
        var changed = 0L
        for (ref in refs) {
            val tagId = tags.findOneByNamespaceAndName(ctx.db, ref.namespace, ref.name).getOrNull()?.id ?: continue
            changed += bindings.executeSoftDeleteByPathAndTagId(ctx.db, relative, tagId, Clock.System.now(), operator)
                .getOrThrow()
        }
        return MessageResult("已解除 $changed 个标签")
    }

    /** 已经挂过就跳过（幂等），避免同一个目录上堆出多条同标签的活行。 */
    private suspend fun attachOne(ctx: Context, path: String, ref: MediaTagRef, operator: String?): Long? {
        val tagId = tagService.ensureTag(ctx.db, ref.namespace, ref.name, operator) ?: return null
        if (bindings.findAllByPathAndTagId(ctx.db, path, tagId).getOrNull().orEmpty().isNotEmpty()) return tagId
        val now = Clock.System.now()
        bindings.executeInsert(ctx.db, path, tagId, SOURCE_MANUAL, now, operator, now, operator).getOrThrow()
        return tagId
    }

    /** 打标类请求的公共前置：目录必须存在，标签归一化 + 去重。 */
    private fun requireRequest(req: MediaTagsReq): Pair<String, List<MediaTagRef>> {
        val relative = browser.requireDir(req.path)
        val refs = req.tags
            .map { MediaTagRef(it.namespace.trim(), it.name.trim()) }
            .filter { it.namespace.isNotEmpty() && it.name.isNotEmpty() }
            .distinct()
        if (refs.isEmpty()) throw ApiException(400, "没有要处理的标签")
        return relative to refs
    }
}
