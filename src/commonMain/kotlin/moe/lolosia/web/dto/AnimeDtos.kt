package moe.lolosia.web.dto

import kotlinx.serialization.Serializable

/**
 * **番组表**（订阅 MioBT 系发布站更新）的接口 DTO（见 `doc/anime-timetable-design.md` §6）。
 *
 * 读接口挂 `lolosia.anime.read`，关注增删挂 `lolosia.anime.write`，目录维护 / 手动映射挂
 * `lolosia.anime.admin`。时间一律毫秒（`Instant.toEpochMilliseconds()`）。
 *
 * @author 洛洛希雅Lolosia
 */

/** 番剧目录里的一部番。 */
@Serializable
data class AnimeSeriesDto(
    val id: Long,
    val bangumiId: Long? = null,
    val title: String,
    val titleOriginal: String? = null,
    val cover: String? = null,
    /** 放送星期 1..7；未定档为 `null`。 */
    val airWeekday: Int? = null,
    val airTime: String? = null,
    val airDate: String? = null,
    val season: String? = null,
    val rating: Double? = null,
    val summary: String? = null,
    val status: String = "airing",
    /** 当前用户是否关注了这部番（任意发布组）。 */
    val followed: Boolean = false,
    /** 当前用户关注了哪些发布组（空串表示「任意组」，见 [AnimeSubscriptionDto.groupName]）。 */
    val followedGroups: List<String> = emptyList(),
)

/** 某番下一个发布组的更新概况（详情面板用）。 */
@Serializable
data class AnimeGroupDto(
    val groupName: String,
    val count: Long,
    val latestEpisodeNo: Int? = null,
    val latestPublishedAt: Long = 0,
)

/** 番剧详情：本体 + 各发布组的更新概况。 */
@Serializable
data class AnimeSeriesDetailDto(
    val series: AnimeSeriesDto,
    val groups: List<AnimeGroupDto> = emptyList(),
)

/** 一条发布记录。 */
@Serializable
data class AnimeReleaseDto(
    val id: Long,
    val guid: String,
    val seriesId: Long? = null,
    val seriesTitle: String = "",
    val groupName: String,
    val title: String,
    val episodeNo: Int? = null,
    val category: String = "",
    val torrentUrl: String? = null,
    val detailUrl: String? = null,
    val matchState: String = "pending",
    val publishedAt: Long = 0,
)

/** 一条关注。 */
@Serializable
data class AnimeSubscriptionDto(
    val id: Long,
    val seriesId: Long,
    val seriesTitle: String,
    val cover: String? = null,
    /** 空串 = 该番任意发布组。 */
    val groupName: String = "",
    val enabled: Boolean = true,
)

/** 新建 / 更新关注；`lolosia.anime.write`。 */
@Serializable
data class AnimeSubscriptionSaveReq(
    val seriesId: Long,
    val groupName: String? = null,
    val enabled: Boolean? = null,
)

/** 取消关注（按 id 或按 (番, 组) 定位）；`lolosia.anime.write`。 */
@Serializable
data class AnimeSubscriptionDeleteReq(
    val id: Long? = null,
    val seriesId: Long? = null,
    val groupName: String? = null,
)

/** 手动把一条发布映射到番剧（可选追加一条手动别名，形成匹配学习闭环）；`lolosia.anime.admin`。 */
@Serializable
data class AnimeReleaseMapReq(
    val releaseId: Long,
    val seriesId: Long,
    /** 非空时把这条文本记为该番的手动别名（同类标题下次可自动命中）。 */
    val alias: String? = null,
)
