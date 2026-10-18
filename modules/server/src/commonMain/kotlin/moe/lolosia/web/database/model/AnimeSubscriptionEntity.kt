package moe.lolosia.web.database.model

import io.github.smyrgeorge.sqlx4k.annotation.Id
import io.github.smyrgeorge.sqlx4k.annotation.Table
import kotlin.time.Instant

/**
 * `anime_subscription`：**(用户, 番剧, 发布组) 一行**（见 `doc/anime-timetable-design.md` §3.4）。
 *
 * [groupName] 为空串表示「该番的任意发布组」。软删 + 生成列 `alive_key` 保证可重新关注，
 * 故 [aliveKey] 是只读的生成列字段。
 *
 * @author 洛洛希雅Lolosia
 */
@Table("anime_subscription")
data class AnimeSubscriptionEntity(
    @Id
    val id: Long = 0L,
    val userId: String,
    /** `anime_series.id`。 */
    val seriesId: Long,
    /** 空串 = 该番任意发布组。 */
    val groupName: String = "",
    val enabled: Boolean = true,
    val createdAt: Instant? = null,
    val createdBy: String? = null,
    val updatedAt: Instant? = null,
    val updatedBy: String? = null,
    val deleted: Boolean = false,
    /** 生成列，只读。 */
    val aliveKey: String? = null,
)
