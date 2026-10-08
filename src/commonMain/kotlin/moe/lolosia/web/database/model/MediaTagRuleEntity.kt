package moe.lolosia.web.database.model

import io.github.smyrgeorge.sqlx4k.annotation.Id
import io.github.smyrgeorge.sqlx4k.annotation.Table
import kotlin.time.Instant

/**
 * `media_tag_rule`：目录 / 文件名 → 标签或评级的规则。
 *
 * 这是**零依赖兜底**：不装 worker、不跑模型，光靠目录结构（`VRChat/`、`白上吹雪/`）与文件名
 * （`*r18*`）就能打出一批标签与评级。它只读路径与文件名、不出网，所以允许在后台扫描时自动跑。
 *
 * [tagId] 与 [rating] 至少填一个；[priority] 小者优先，用于评级冲突时定胜负。
 *
 * 库中本表一条规则都没有时回退内嵌资源 `resources/media-rules.yaml`，有规则则完全以库为准
 * （见 `doc/media-index-design.md` §4.5）。
 *
 * @author 洛洛希雅Lolosia
 */
@Table("media_tag_rule")
data class MediaTagRuleEntity(
    @Id
    val id: Long = 0L,
    /** `path_prefix` / `path_contains` / `name_glob` / `ext`。 */
    val matchType: String,
    /** 如 `VRChat/` 或 `*r18*` 或 `jpeg`。 */
    val pattern: String,
    /** 命中后打的标签。 */
    val tagId: Long? = null,
    /** 命中后设的评级。 */
    val rating: String? = null,
    val priority: Int = 100,
    val enabled: Boolean = true,
    val createdAt: Instant? = null,
    val createdBy: String? = null,
    val updatedAt: Instant? = null,
    val updatedBy: String? = null,
    val deleted: Boolean = false,
)
