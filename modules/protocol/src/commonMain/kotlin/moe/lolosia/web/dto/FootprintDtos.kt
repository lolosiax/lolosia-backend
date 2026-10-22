package moe.lolosia.web.dto

import kotlinx.serialization.Serializable

/**
 * **足迹地图**（地点 / 时间聚类）的接口 DTO。
 *
 * 只读接口（`footprint/overview` · `points` · `timeline`）需要 `lolosia.media.location`
 * 权限 —— 它们裸奔的是 GPS（精确到米 + 时间等于行程轨迹，见 `doc/media-index-design.md` §6.4）。
 * 区域圈的增删需要 `lolosia.media.write`。
 *
 * 所有缩略图直链靠 [MediaFootprintCluster.exp] / [MediaFootprintCluster.sig]（与 [FileEntry] 同口径），
 * 前端拼 `files/thumb` 即可取图，不必再回一次列表接口。
 *
 * @author 洛洛希雅Lolosia
 */

/** 一个**空间聚类**（热点）：自动从带 GPS 的图里聚出来的密集区域。 */
@Serializable
data class MediaFootprintCluster(
    /** 稳定 id（网格坐标派生），同一批数据重复请求保持一致。 */
    val id: String,
    val lat: Double,
    val lon: Double,
    /** 这个热点里有多少张图。 */
    val count: Long,
    /** 覆盖半径（米）：成员到中心的最大距离。 */
    val radiusM: Double = 0.0,
    /** 最早 / 最晚拍摄时刻（毫秒）；没有拍摄时间为 0。 */
    val firstAt: Long = 0,
    val lastAt: Long = 0,
    /** 代表图（离中心最近的一张），用来在侧栏出缩略图。 */
    val samplePath: String = "",
    val sampleName: String = "",
    val sampleRating: String = "unknown",
    val exp: Long = 0,
    val sig: String = "",
)

/** 一个**人工命名的区域圈**（`media_location_region`），[count] 是圈内图数（现算）。 */
@Serializable
data class MediaFootprintRegion(
    val id: Long,
    val name: String,
    val lat: Double,
    val lon: Double,
    val radiusM: Double = 0.0,
    val color: String? = null,
    val description: String? = null,
    val count: Long = 0,
    /**
     * 代表图：圈内**优先取带「喜欢」标签且离圆心最近**的一张，没有喜欢的再退回离圆心最近的一张；
     * 圈里一张图都没有时全为空。用来在侧栏出缩略图。
     */
    val samplePath: String = "",
    val sampleName: String = "",
    val sampleRating: String = "unknown",
    val exp: Long = 0,
    val sig: String = "",
)

/** 空间视图下地图上的一颗点（放大到一定程度才拉）。 */
@Serializable
data class MediaFootprintPoint(
    val path: String,
    val name: String,
    val lat: Double,
    val lon: Double,
    /** 拍摄时刻（毫秒）；没有为 0。 */
    val takenAt: Long = 0,
    val rating: String = "unknown",
    val exp: Long = 0,
    val sig: String = "",
)

/** 地点聚类的总览：地图一次画完所需的一切。 */
@Serializable
data class MediaFootprintOverview(
    /** 带 GPS 的图片 / 视频数。 */
    val total: Long = 0,
    /** 没有 GPS 的图片 / 视频数（时间聚类要覆盖的那批）。 */
    val noGps: Long = 0,
    /** 所有带 GPS 点的外接矩形；一张都没有时为 null。 */
    val minLat: Double? = null,
    val minLon: Double? = null,
    val maxLat: Double? = null,
    val maxLon: Double? = null,
    /** 自动热点，按图数从大到小。 */
    val clusters: List<MediaFootprintCluster> = emptyList(),
    /** 人工命名的区域圈。 */
    val regions: List<MediaFootprintRegion> = emptyList(),
)

/** 一个**时间聚类**（一次拍摄会话）：按相邻照片的时间间隔切段。 */
@Serializable
data class MediaFootprintPeriod(
    val id: String,
    /** 起止时刻（毫秒）。 */
    val startAt: Long,
    val endAt: Long,
    val count: Long,
    val samplePath: String = "",
    val sampleName: String = "",
    val sampleRating: String = "unknown",
    val exp: Long = 0,
    val sig: String = "",
)

/** 时间聚类的总览。 */
@Serializable
data class MediaFootprintTimeline(
    /** 带拍摄时间的图片 / 视频数。 */
    val total: Long = 0,
    /** 没有拍摄时间的图片 / 视频数。 */
    val noTime: Long = 0,
    /** 会话段，按图数从大到小。 */
    val periods: List<MediaFootprintPeriod> = emptyList(),
)

/** 保存区域圈（`id` 为空 = 新建）；`lolosia.media.write`。 */
@Serializable
data class MediaFootprintRegionReq(
    val id: Long? = null,
    val name: String = "",
    val lat: Double = 0.0,
    val lon: Double = 0.0,
    val radiusM: Double = 0.0,
    val color: String? = null,
    val description: String? = null,
)

/** 删除区域圈（软删）。 */
@Serializable
data class MediaFootprintRegionDeleteReq(val id: Long = 0L)
