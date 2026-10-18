package moe.lolosia.web.database.model

import io.github.smyrgeorge.sqlx4k.annotation.Id
import io.github.smyrgeorge.sqlx4k.annotation.Table
import kotlin.time.Instant

/**
 * `anime_series_alias`：番剧别名（见 `doc/anime-timetable-design.md` §3.2）。
 *
 * RSS 标题里的番名形态多样（中文 / 日文 / 罗马字 / 英文），靠别名表做「标题 → 番剧」匹配。
 * [aliasNorm] 是规范化后的形态，与 `AnimeTitleMatcher` 的归一化**同口径**。
 * [source] 为 `bangumi`（目录同步）或 `manual`（管理员手动映射，形成学习闭环）。
 *
 * 没有 `deleted`：别名随番剧在目录同步时整表重建。
 *
 * @author 洛洛希雅Lolosia
 */
@Table("anime_series_alias")
data class AnimeSeriesAliasEntity(
    @Id
    val id: Long = 0L,
    /** 所属番剧 id（`anime_series.id`）。 */
    val seriesId: Long,
    /** 别名原文。 */
    val alias: String,
    /** 规范化后（小写、去空白与符号），匹配口径。 */
    val aliasNorm: String,
    /** `bangumi` / `manual`。 */
    val source: String = ALIAS_SOURCE_BANGUMI,
    val createdAt: Instant? = null,
)

/** 别名来源：Bangumi 目录同步。 */
const val ALIAS_SOURCE_BANGUMI: String = "bangumi"

/** 别名来源：管理员手动映射。 */
const val ALIAS_SOURCE_MANUAL: String = "manual"
