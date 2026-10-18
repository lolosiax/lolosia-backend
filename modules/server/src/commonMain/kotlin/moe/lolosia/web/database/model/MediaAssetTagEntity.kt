package moe.lolosia.web.database.model

import io.github.smyrgeorge.sqlx4k.annotation.Id
import io.github.smyrgeorge.sqlx4k.annotation.Table
import kotlin.time.Instant

/**
 * `media_asset_tag`：文件 ↔ 标签关联。
 *
 * 刻意**没有** `UNIQUE(asset_id, tag_id)`：同一个标签可能同时存在 `manual` 与 `model` 两条来源，
 * 判定时取优先级最高的那条（manual > rule > model），去重放在服务层做
 * （见 `doc/media-index-design.md` §7.3）。
 *
 * [negative] 是人工否决（「这张图明确不是这个标签」）：没有它，重新跑模型会把人工纠正过的标签
 * 一遍遍加回来。
 *
 * @author 洛洛希雅Lolosia
 */
@Table("media_asset_tag")
data class MediaAssetTagEntity(
    @Id
    val id: Long = 0L,
    val assetId: Long,
    val tagId: Long,
    /** `manual` / `rule` / `model`。 */
    val source: String,
    /** 模型名 + 版本，如 `wd14-v1.4`。 */
    val provider: String? = null,
    /** 0~1；`manual` 为 `null`。 */
    val confidence: Double? = null,
    /** 人工否决。 */
    val negative: Boolean = false,
    val createdAt: Instant? = null,
    val createdBy: String? = null,
    val updatedAt: Instant? = null,
    val updatedBy: String? = null,
    val deleted: Boolean = false,
)
