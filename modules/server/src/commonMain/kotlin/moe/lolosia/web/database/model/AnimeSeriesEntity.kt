package moe.lolosia.web.database.model

import io.github.smyrgeorge.sqlx4k.annotation.Id
import io.github.smyrgeorge.sqlx4k.annotation.Table
import kotlin.time.Instant

/**
 * `anime_series`：**番剧目录**里的一部番（见 `doc/anime-timetable-design.md` §3.1）。
 *
 * 元数据来自 Bangumi（[bangumiId] 唯一），也可由管理员手工新建（[bangumiId] 为 `null`）。
 * [airWeekday] 口径同 Bangumi calendar：1=周一 … 7=周日，未定档为 `null`。
 *
 * @author 洛洛希雅Lolosia
 */
@Table("anime_series")
data class AnimeSeriesEntity(
    @Id
    val id: Long = 0L,
    /** Bangumi subject id；手工建的番为 `null`。 */
    val bangumiId: Long? = null,
    /** 展示名（中文优先）。 */
    val title: String,
    /** 原文名（日文 / 罗马字）。 */
    val titleOriginal: String? = null,
    val cover: String? = null,
    /** 放送星期 1..7。 */
    val airWeekday: Int? = null,
    /** 放送时刻（JST），如 `23:30`。 */
    val airTime: String? = null,
    /** 首播日期，如 `2026-10-05`。 */
    val airDate: String? = null,
    /** 季度，如 `2026秋`。 */
    val season: String? = null,
    /** Bangumi 条目页。 */
    val siteUrl: String? = null,
    val rating: Double? = null,
    val summary: String? = null,
    /** `airing` / `finished` / `unknown`。 */
    val status: String = STATUS_AIRING,
    val createdAt: Instant? = null,
    val createdBy: String? = null,
    val updatedAt: Instant? = null,
    val updatedBy: String? = null,
    val deleted: Boolean = false,
)

/** 番剧状态：在播。 */
const val STATUS_AIRING: String = "airing"

/** 番剧状态：已完结。 */
const val STATUS_FINISHED: String = "finished"

/** 番剧状态：未知。 */
const val STATUS_UNKNOWN: String = "unknown"
