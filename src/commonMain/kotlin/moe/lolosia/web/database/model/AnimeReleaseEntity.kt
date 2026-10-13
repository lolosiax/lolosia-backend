package moe.lolosia.web.database.model

import io.github.smyrgeorge.sqlx4k.annotation.Id
import io.github.smyrgeorge.sqlx4k.annotation.Table
import kotlin.time.Instant

/**
 * `anime_release`：**一次发布一行**（见 `doc/anime-timetable-design.md` §3.3）。
 *
 * 来自 MioBT 系发布站的 RSS；[guid] 是全局唯一键，跨轮去重靠它。[groupName] 是 RSS 的
 * `author`（发布组），[seriesId] 是匹配到的番（`null` = 未匹配）。
 *
 * [matchState]：`pending` 刚入库 / `matched` 已匹配 / `unmatched` 匹配失败（可人工映射）/
 * `ignored` 人工判噪音。[source] 预留换源（`miobt` / `mikan` …）。
 *
 * @author 洛洛希雅Lolosia
 */
@Table("anime_release")
data class AnimeReleaseEntity(
    @Id
    val id: Long = 0L,
    /** RSS guid。 */
    val guid: String,
    /** 数据源代号。 */
    val source: String = RELEASE_SOURCE_MIOBT,
    /** 匹配到的番；`null` = 未匹配。 */
    val seriesId: Long? = null,
    /** 发布组（RSS `author`）。 */
    val groupName: String = "",
    /** RSS 标题原文。 */
    val title: String,
    /** 解析出的集数；解析失败为 `null`。 */
    val episodeNo: Int? = null,
    /** RSS category。 */
    val category: String = "",
    /** enclosure 种子直链。 */
    val torrentUrl: String? = null,
    /** 详情页。 */
    val detailUrl: String? = null,
    /** `pending` / `matched` / `unmatched` / `ignored`。 */
    val matchState: String = MATCH_PENDING,
    /** 发布时间（UTC）。 */
    val publishedAt: Instant? = null,
    val createdAt: Instant? = null,
)

/** 数据源：MioBT 系发布站。 */
const val RELEASE_SOURCE_MIOBT: String = "miobt"

/** 匹配状态：刚入库，尚未匹配。 */
const val MATCH_PENDING: String = "pending"

/** 匹配状态：已匹配到番剧。 */
const val MATCH_MATCHED: String = "matched"

/** 匹配状态：匹配失败（可人工映射）。 */
const val MATCH_UNMATCHED: String = "unmatched"

/** 匹配状态：人工判噪音。 */
const val MATCH_IGNORED: String = "ignored"
