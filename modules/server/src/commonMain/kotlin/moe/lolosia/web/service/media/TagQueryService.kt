package moe.lolosia.web.service.media

import moe.lolosia.web.database.repository.FolderTagRepository
import moe.lolosia.web.database.repository.MediaAssetTagRepository
import moe.lolosia.web.database.repository.MediaTagRepository
import moe.lolosia.web.dto.MediaTagItem
import moe.lolosia.web.service.files.FileBrowser
import moe.lolosia.web.util.router.Injectable
import moe.lolosia.web.util.router.Service
import moe.lolosia.web.util.session.Context

/**
 * 标签选择器（文件管理器右栏那个搜索选择框）的只读数据源。
 *
 * 两件事：给出**全量词表**供按命名空间分栏浏览，以及给出**某个目录里已经在用**的标签，
 * 供选择器把「这个文件夹用过的」顶到最前面。
 *
 * 文件与目录是两套关联表（`media_asset_tag` / `folder_tag`），但选择器不关心标签挂在哪 ——
 * 「这个目录里有没有人在用」把两者合流后就是一份集合。
 *
 * @author 洛洛希雅Lolosia
 */
@Injectable
@Service
class TagQueryService(
    private val browser: FileBrowser,
    private val tags: MediaTagRepository,
    private val bindings: MediaAssetTagRepository,
    private val folderBindings: FolderTagRepository,
) {

    /**
     * 全量词表（`media_tag`），按命名空间、名字排序。
     *
     * 选择器只在控件获得焦点时拉一次，不在页面加载时拉，所以这里不做分页 ——
     * 词表量级是几百到几千（见 `doc/media-index-design.md` §7.5），一次取回最省事。
     */
    suspend fun vocabulary(ctx: Context): List<MediaTagItem> =
        tags.findAllByDeletedFalse(ctx.db).getOrThrow().map { tag ->
            MediaTagItem(
                id = tag.id,
                namespace = tag.namespace,
                name = tag.name,
                parentId = tag.parentId,
            )
        }

    /**
     * 某个目录里**已经在用**的标签。`path` 为空串表示浏览根。
     *
     * `count` 是「这个目录里有几个文件带着它」（不是全库引用数）：目录自己挂的标签记 0，
     * 于是两类来源在结果里天然分得开，排序也就有了依据。
     *
     * 否决行（`negative`）不计入 —— 那是「这个文件明确不是该标签」，摆进候选只会误导。
     */
    suspend fun directoryTags(ctx: Context, path: String?): List<MediaTagItem> {
        val relative = browser.requireDir(path)
        val used = mutableMapOf<Long, Long>()
        for (link in bindings.findAllByDirPath(ctx.db, relative).getOrThrow()) {
            if (link.negative) continue
            used[link.tagId] = (used[link.tagId] ?: 0L) + 1L
        }
        val own = folderBindings.findAllByPath(ctx.db, relative).getOrThrow().map { it.tagId }
        return (used.keys + own).distinct()
            .mapNotNull { tagId ->
                val tag = tags.findOneById(ctx.db, tagId).getOrThrow() ?: return@mapNotNull null
                MediaTagItem(
                    id = tag.id,
                    namespace = tag.namespace,
                    name = tag.name,
                    parentId = tag.parentId,
                    count = used[tagId] ?: 0L,
                )
            }
            .sortedWith(
                compareByDescending<MediaTagItem> { it.count }
                    .thenBy { it.namespace }
                    .thenBy { it.name },
            )
    }
}
