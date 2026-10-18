package moe.lolosia.web.database.model

import io.github.smyrgeorge.sqlx4k.annotation.Id
import io.github.smyrgeorge.sqlx4k.annotation.Table
import kotlin.time.Instant

/**
 * `folder_tag`：**文件夹** ↔ 标签关联。
 *
 * 目录本身不在 `media_asset` 里（媒体索引只登记文件），所以文件的 `media_asset_tag` 挂不到它身上，
 * 只能按**相对路径**另存一张表；词表仍复用 `media_tag`。
 *
 * 与 [MediaAssetTagEntity] 一样刻意**没有** `UNIQUE(path, tag_id)`：去重放在服务层做。
 * 也没有 `negative` —— 文件夹标签恒为人工来源，没有规则 / 模型会来抢（见 `sql/folder_tag.sql`）。
 *
 * @author 洛洛希雅Lolosia
 */
@Table("folder_tag")
data class FolderTagEntity(
    @Id
    val id: Long = 0L,
    /** 相对浏览根的目录路径；根目录是空串。 */
    val path: String,
    val tagId: Long,
    /** 恒为 `manual`。 */
    val source: String,
    val createdAt: Instant? = null,
    val createdBy: String? = null,
    val updatedAt: Instant? = null,
    val updatedBy: String? = null,
    val deleted: Boolean = false,
)
