package moe.lolosia.web.database.model

import io.github.smyrgeorge.sqlx4k.annotation.Id
import io.github.smyrgeorge.sqlx4k.annotation.Table
import kotlin.time.Instant

/**
 * `folder_remark`：**文件夹备注**（一行一段自由文本，按相对路径定位）。
 *
 * 与文件的 [MediaAssetEntity.caption] 对齐，但目录不在 `media_asset` 里，只能另起一张表。
 * 一个目录最多一条活行（`deleted = 0`）：保存时不新增历史，直接改那一条；清空即软删。
 *
 * 既供前端右侧属性面板展示 / 编辑，也供外发分析时作为「所属文件夹」的上下文喂给模型。
 *
 * @author 洛洛希雅Lolosia
 */
@Table("folder_remark")
data class FolderRemarkEntity(
    @Id
    val id: Long = 0L,
    /** 相对浏览根的目录路径；根目录是空串。 */
    val path: String,
    val remark: String? = null,
    val createdAt: Instant? = null,
    val createdBy: String? = null,
    val updatedAt: Instant? = null,
    val updatedBy: String? = null,
    val deleted: Boolean = false,
)
