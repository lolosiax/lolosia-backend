package moe.lolosia.web.controller

import moe.lolosia.web.dto.MediaFootprintOverview
import moe.lolosia.web.dto.MediaFootprintPoint
import moe.lolosia.web.dto.MediaFootprintRegionDeleteReq
import moe.lolosia.web.dto.MediaFootprintRegionReq
import moe.lolosia.web.dto.MediaFootprintTimeline
import moe.lolosia.web.dto.MessageResult
import moe.lolosia.web.service.media.MEDIA_LOCATION
import moe.lolosia.web.service.media.MEDIA_WRITE
import moe.lolosia.web.service.media.MediaFootprintService
import moe.lolosia.web.util.router.Body
import moe.lolosia.web.util.router.Controller
import moe.lolosia.web.util.router.GET
import moe.lolosia.web.util.router.POST
import moe.lolosia.web.util.router.Permission
import moe.lolosia.web.util.router.Query
import moe.lolosia.web.util.session.Context

/**
 * **足迹地图**接口：按地点 / 时间聚类看照片，并维护人工命名的区域圈。
 *
 * 读接口（`overview` · `points` · `timeline` · `period/photos`）挂 [MEDIA_LOCATION] ——
 * 它们裸奔的是 GPS；区域圈的增删挂 [MEDIA_WRITE]（写库、影响所有人的视图）。
 *
 * 聚类粒度（`eps` / `minPts` / `gapHours`）都做成入参：前端按缩放级别调整即可，
 * 后端不预设"什么算一个地点"。
 *
 * @author 洛洛希雅Lolosia
 */
@Controller("/api")
object FootprintController {

    /** 地点总览：自动热点 + 人工区域圈 + 总数 / 无 GPS 数。 */
    @GET("/media/footprint/overview")
    @Permission(MEDIA_LOCATION)
    suspend fun overview(
        ctx: Context,
        footprint: MediaFootprintService,
        @Query eps: Double?,
        @Query minPts: Int?,
        @Query maxClusters: Int?,
    ): MediaFootprintOverview =
        footprint.overview(ctx, eps ?: DEFAULT_EPS_METERS, minPts ?: DEFAULT_MIN_PTS, maxClusters ?: DEFAULT_MAX_CLUSTERS)

    /** 视图范围内（可选时间范围内）的散点，供地图放大后逐点显示。 */
    @GET("/media/footprint/points")
    @Permission(MEDIA_LOCATION)
    suspend fun points(
        ctx: Context,
        footprint: MediaFootprintService,
        @Query minLat: Double?,
        @Query maxLat: Double?,
        @Query minLon: Double?,
        @Query maxLon: Double?,
        @Query limit: Int?,
    ): List<MediaFootprintPoint> = footprint.points(ctx, minLat, maxLat, minLon, maxLon, limit ?: DEFAULT_POINTS)

    /** 时间总览：按拍摄间隔切成会话。 */
    @GET("/media/footprint/timeline")
    @Permission(MEDIA_LOCATION)
    suspend fun timeline(
        ctx: Context,
        footprint: MediaFootprintService,
        @Query gapHours: Double?,
        @Query limit: Int?,
    ): MediaFootprintTimeline = footprint.timeline(ctx, gapHours ?: DEFAULT_GAP_HOURS, limit ?: DEFAULT_PERIODS)

    /** 某个时间段内的全部图（含没有 GPS 的），供点开一个会话时出画廊。 */
    @GET("/media/footprint/period/photos")
    @Permission(MEDIA_LOCATION)
    suspend fun periodPhotos(
        ctx: Context,
        footprint: MediaFootprintService,
        @Query from: Long,
        @Query to: Long,
        @Query limit: Int?,
    ): List<MediaFootprintPoint> = footprint.periodPhotos(ctx, from, to, limit ?: DEFAULT_POINTS)

    /** 保存区域圈（`id` 为空即新建）。 */
    @POST("/media/footprint/region/save")
    @Permission(MEDIA_WRITE)
    suspend fun saveRegion(
        ctx: Context,
        footprint: MediaFootprintService,
        @Body req: MediaFootprintRegionReq,
    ): MessageResult = footprint.saveRegion(ctx, req)

    /** 删除区域圈（软删）。 */
    @POST("/media/footprint/region/delete")
    @Permission(MEDIA_WRITE)
    suspend fun deleteRegion(
        ctx: Context,
        footprint: MediaFootprintService,
        @Body req: MediaFootprintRegionDeleteReq,
    ): MessageResult = footprint.deleteRegion(ctx, req)

    /** 邻域半径默认值（米）：约等于"一个街区到一个小片区"。 */
    private const val DEFAULT_EPS_METERS: Double = 250.0

    /** 成为核心点所需的最少邻居数。 */
    private const val DEFAULT_MIN_PTS: Int = 3

    private const val DEFAULT_MAX_CLUSTERS: Int = 500

    private const val DEFAULT_POINTS: Int = 2000

    /** 时间会话切分间隔（小时）：隔夜即视作两场。 */
    private const val DEFAULT_GAP_HOURS: Double = 6.0

    private const val DEFAULT_PERIODS: Int = 400
}
