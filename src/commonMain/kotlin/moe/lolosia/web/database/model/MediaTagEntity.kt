package moe.lolosia.web.database.model

import io.github.smyrgeorge.sqlx4k.annotation.Id
import io.github.smyrgeorge.sqlx4k.annotation.Table
import kotlin.time.Instant

/**
 * `media_tag`：标签词表（命名空间 + 层级 + 别名 + 归并重定向）。
 *
 * [parentId] 为 `NULL` 是**合法状态**而不是半成品：还不知道图里是什么时，先手输一个根级自由标签
 * （`namespace = user`），以后再挂到某个父级下面（`doc/media-index-design.md` §7.5）。
 *
 * 层级只用来「向下蕴含」：查 `character:Vtuber` 要能命中所有成员的图。
 *
 * [alias] 与 [mergedInto] 一起保证「旧名永远搜得到」——改名把旧名追加进 [alias]，
 * 合并则把被并入的行记为 [mergedInto]，命中它时沿链重定向，两者都不是删除。
 *
 * [aliveKey] 是生成列（`namespace:name`，软删时取 `NULL`），代码只读不写。
 *
 * @author 洛洛希雅Lolosia
 */
@Table("media_tag")
data class MediaTagEntity(
    @Id
    val id: Long = 0L,
    /** `character` / `series` / `object` / `action` / `scene` / `meta` / `user`。 */
    val namespace: String,
    /** 展示名；自由标签就是用户手输的原文。 */
    val name: String,
    /** 父标签；`null` = 根级自由标签。 */
    val parentId: Long? = null,
    /** 逗号分隔的别名 / 旧名；改名与合并都往这里追加。 */
    val alias: String? = null,
    /** 被并入的标签 id；本行降级为「重定向」。 */
    val mergedInto: Long? = null,
    val description: String? = null,
    val createdAt: Instant? = null,
    val createdBy: String? = null,
    val updatedAt: Instant? = null,
    val updatedBy: String? = null,
    val deleted: Boolean = false,
    /** 生成列，只读。 */
    val aliveKey: String? = null,
)
