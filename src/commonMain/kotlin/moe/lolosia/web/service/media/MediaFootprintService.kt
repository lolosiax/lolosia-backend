package moe.lolosia.web.service.media

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import moe.lolosia.web.database.model.MediaGeoAssetEntity
import moe.lolosia.web.database.model.MediaLocationRegionEntity
import moe.lolosia.web.database.repository.MediaAssetTagRepository
import moe.lolosia.web.database.repository.MediaGeoAssetRepository
import moe.lolosia.web.database.repository.MediaLocationRegionRepository
import moe.lolosia.web.dto.ApiException
import moe.lolosia.web.dto.MediaFootprintCluster
import moe.lolosia.web.dto.MediaFootprintOverview
import moe.lolosia.web.dto.MediaFootprintPeriod
import moe.lolosia.web.dto.MediaFootprintPoint
import moe.lolosia.web.dto.MediaFootprintRegion
import moe.lolosia.web.dto.MediaFootprintRegionDeleteReq
import moe.lolosia.web.dto.MediaFootprintRegionReq
import moe.lolosia.web.dto.MediaFootprintTimeline
import moe.lolosia.web.dto.MessageResult
import moe.lolosia.web.service.files.FileSigner
import moe.lolosia.web.util.router.Injectable
import moe.lolosia.web.util.router.Service
import moe.lolosia.web.util.session.Context
import moe.lolosia.web.util.time.nowEpochMs
import kotlin.concurrent.Volatile
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.time.Clock

/**
 * 足迹地图：把带 GPS 的图按**地点**聚成热点、按**时间**切成会话，并维护人工命名的区域圈。
 *
 * 数据来源是 `media_asset` 的一份**精简投影快照**（见 [MediaGeoAssetEntity]）：聚类要一次读完
 * 全部带 GPS / 带拍摄时间的行，故把快照在进程内缓存 [CACHE_TTL_MS]，避免前端缩放 / 切页时
 * 反复整表拉取。库里的 GPS 只由后台索引扫描写入，变化很慢，几十秒的缓存足够新鲜。
 *
 * 入参的 `eps` / `minPts` / `gapHours` 都开成接口参数，前端据此提供「聚簇粗细」滑块 ——
 * 缩放级别变了、想看得更细，不必改后端。
 *
 * @author 洛洛希雅Lolosia
 */
@Injectable
@Service
class MediaFootprintService(
    private val geo: MediaGeoAssetRepository,
    private val regions: MediaLocationRegionRepository,
    private val assetTags: MediaAssetTagRepository,
    private val signer: FileSigner,
) {

    private val mutex = Mutex()

    @Volatile
    private var geoCache: Cached? = null

    @Volatile
    private var timeCache: Cached? = null

    private class Cached(val rows: List<MediaGeoAssetEntity>, val at: Long)

    /** 地点总览：热点 + 人工区域 + 总数 / 无 GPS 数。 */
    suspend fun overview(ctx: Context, epsMeters: Double, minPts: Int, maxClusters: Int): MediaFootprintOverview {
        val rows = geoRows(ctx)
        val exp = signer.expireAt()
        val points = rows.map { FootprintClusterer.Point(it.gpsLat!!, it.gpsLon!!, it.takenAt?.toEpochMilliseconds() ?: 0L) }
        val clusters = FootprintClusterer.spatial(points, epsMeters, minPts)
            .sortedByDescending { it.count }
            .take(maxClusters.coerceIn(1, MAX_CLUSTERS))

        val saved = regionRows(ctx)
        val favorites = favoriteAssetIds(ctx)
        val total = mediaTotal(ctx)
        return MediaFootprintOverview(
            total = rows.size.toLong(),
            noGps = (total - rows.size).coerceAtLeast(0),
            minLat = rows.minOfOrNull { it.gpsLat!! },
            minLon = rows.minOfOrNull { it.gpsLon!! },
            maxLat = rows.maxOfOrNull { it.gpsLat!! },
            maxLon = rows.maxOfOrNull { it.gpsLon!! },
            clusters = clusters.map { toCluster(it, rows, exp) },
            regions = saved.map { toRegion(it, rows, favorites, exp) },
        )
    }

    /** 视图范围内（可选时间范围内）的散点，供地图放大后逐点显示。 */
    suspend fun points(
        ctx: Context,
        minLat: Double?,
        maxLat: Double?,
        minLon: Double?,
        maxLon: Double?,
        limit: Int,
    ): List<MediaFootprintPoint> {
        val rows = geoRows(ctx)
        val exp = signer.expireAt()
        val cap = limit.coerceIn(1, MAX_POINTS)
        return rows.asSequence()
            .filter { minLat == null || (it.gpsLat != null && it.gpsLat >= minLat) }
            .filter { maxLat == null || (it.gpsLat != null && it.gpsLat <= maxLat) }
            .filter { minLon == null || (it.gpsLon != null && it.gpsLon >= minLon) }
            .filter { maxLon == null || (it.gpsLon != null && it.gpsLon <= maxLon) }
            .take(cap)
            .map { MediaFootprintPoint(it.path, it.name, it.gpsLat!!, it.gpsLon!!, it.takenAt?.toEpochMilliseconds() ?: 0L, it.rating, exp, signer.sign(it.path, exp)) }
            .toList()
    }

    /** 时间总览：按相邻拍摄时刻的间隔切成会话，按图数从大到小。 */
    suspend fun timeline(ctx: Context, gapHours: Double, limit: Int): MediaFootprintTimeline {
        val rows = timeRows(ctx)
        val points = rows.map { FootprintClusterer.Point(0.0, 0.0, it.takenAt?.toEpochMilliseconds() ?: 0L) }
        val gapMillis = (gapHours.coerceIn(0.25, 24.0 * 30) * 3_600_000.0).toLong()
        val periods = FootprintClusterer.temporal(points, gapMillis)
            .sortedByDescending { it.count }
            .take(limit.coerceIn(1, MAX_PERIODS))
        val exp = signer.expireAt()
        val total = mediaTotal(ctx)
        return MediaFootprintTimeline(
            total = rows.size.toLong(),
            noTime = (total - rows.size).coerceAtLeast(0),
            periods = periods.map { toPeriod(it, rows, exp) },
        )
    }

    /** 某个时间段内的全部图（含没有 GPS 的），供点开一个会话时出画廊。 */
    suspend fun periodPhotos(ctx: Context, from: Long, to: Long, limit: Int): List<MediaFootprintPoint> {
        val rows = timeRows(ctx)
        val exp = signer.expireAt()
        val cap = limit.coerceIn(1, MAX_POINTS)
        return rows.asSequence()
            .filter { val t = it.takenAt?.toEpochMilliseconds() ?: 0L; t in from..to }
            .take(cap)
            .map {
                MediaFootprintPoint(
                    it.path, it.name, it.gpsLat ?: 0.0, it.gpsLon ?: 0.0,
                    it.takenAt?.toEpochMilliseconds() ?: 0L, it.rating, exp, signer.sign(it.path, exp),
                )
            }
            .toList()
    }

    /** 保存区域圈：`id` 为空即新建，否则更新。半径钳到 10m~5km，名字不能为空。 */
    suspend fun saveRegion(ctx: Context, req: MediaFootprintRegionReq): MessageResult {
        val name = req.name.trim()
        if (name.isEmpty()) throw ApiException(400, "区域名不能为空")
        if (req.lat !in -90.0..90.0 || req.lon !in -180.0..180.0) throw ApiException(400, "经纬度超出范围")
        val radius = req.radiusM.coerceIn(10.0, 5000.0)
        val description = req.description?.trim()?.ifEmpty { null }
        val color = req.color?.trim()?.ifEmpty { null }
        val now = Clock.System.now()
        val operator = ctx.userId
        val id = req.id
        return if (id == null) {
            regions.executeInsert(ctx.db, name, req.lat, req.lon, radius, color, description, now, operator, now, operator).getOrThrow()
            MessageResult("已新建区域「$name」")
        } else {
            regions.findOneById(ctx.db, id).getOrThrow() ?: throw ApiException(404, "区域不存在")
            regions.executeUpdateById(ctx.db, id, name, req.lat, req.lon, radius, color, description, now, operator).getOrThrow()
            MessageResult("已保存区域「$name」")
        }
    }

    /** 删除区域圈（软删）。 */
    suspend fun deleteRegion(ctx: Context, req: MediaFootprintRegionDeleteReq): MessageResult {
        val existing = regions.findOneById(ctx.db, req.id).getOrThrow() ?: throw ApiException(404, "区域不存在")
        regions.executeSoftDeleteById(ctx.db, existing.id, Clock.System.now(), ctx.userId).getOrThrow()
        return MessageResult("已删除区域「${existing.name}」")
    }

    /* ---------- 快照与拼装 ---------- */

    /** 带 GPS 的快照（TTL 缓存）。 */
    private suspend fun geoRows(ctx: Context): List<MediaGeoAssetEntity> {
        geoCache?.takeIf { fresh(it) }?.let { return it.rows }
        return mutex.withLock {
            geoCache?.takeIf { fresh(it) }?.let { return@withLock it.rows }
            val rows = geo.findAllByGpsIsNotNull(ctx.db).getOrThrow()
            geoCache = Cached(rows, nowEpochMs())
            rows
        }
    }

    /** 带拍摄时间的快照（TTL 缓存；按下标与 SQL 的 `order by taken_at asc` 对齐）。 */
    private suspend fun timeRows(ctx: Context): List<MediaGeoAssetEntity> {
        timeCache?.takeIf { fresh(it) }?.let { return it.rows }
        return mutex.withLock {
            timeCache?.takeIf { fresh(it) }?.let { return@withLock it.rows }
            val rows = geo.findAllByTakenAtIsNotNull(ctx.db).getOrThrow()
            timeCache = Cached(rows, nowEpochMs())
            rows
        }
    }

    private fun fresh(cache: Cached): Boolean = nowEpochMs() - cache.at < CACHE_TTL_MS

    private suspend fun regionRows(ctx: Context): List<MediaLocationRegionEntity> {
        val rows = runCatching { regions.findAllByDeletedFalse(ctx.db).getOrThrow() }.getOrNull() ?: return emptyList()
        return rows
    }

    /** 图片 / 视频总数（无论有无 GPS / 拍摄时间）。 */
    private suspend fun mediaTotal(ctx: Context): Long =
        runCatching { geo.countByKindMedia(ctx.db).getOrThrow() }.getOrNull() ?: 0L

    private fun toCluster(cluster: FootprintClusterer.Cluster, rows: List<MediaGeoAssetEntity>, exp: Long): MediaFootprintCluster {
        val rep = rows[cluster.representative]
        return MediaFootprintCluster(
            id = "c:${(cluster.lat * 10_000).roundToInt()}:${(cluster.lon * 10_000).roundToInt()}",
            lat = cluster.lat,
            lon = cluster.lon,
            count = cluster.count.toLong(),
            radiusM = cluster.radiusM,
            firstAt = cluster.firstAt,
            lastAt = cluster.lastAt,
            samplePath = rep.path,
            sampleName = rep.name,
            sampleRating = rep.rating,
            exp = exp,
            sig = signer.sign(rep.path, exp),
        )
    }

    /**
     * 拼一个区域圈：现算圈内图数，并挑一张代表图 —— 圈内**带「喜欢」标签且离圆心最近**的优先，
     * 没有喜欢的再退回离圆心最近的一张。圈里一张图都没有时代表图留空。
     */
    private fun toRegion(
        region: MediaLocationRegionEntity,
        rows: List<MediaGeoAssetEntity>,
        favorites: Set<Long>,
        exp: Long,
    ): MediaFootprintRegion {
        val kx = cos(region.centerLat * PI / 180.0) * METERS_PER_DEGREE
        val r2 = region.radiusM * region.radiusM
        var count = 0L
        var bestAny: MediaGeoAssetEntity? = null
        var bestAnyD2 = Double.MAX_VALUE
        var bestFavorite: MediaGeoAssetEntity? = null
        var bestFavoriteD2 = Double.MAX_VALUE
        for (row in rows) {
            val lat = row.gpsLat ?: continue
            val lon = row.gpsLon ?: continue
            val dx = (lon - region.centerLon) * kx
            val dy = (lat - region.centerLat) * METERS_PER_DEGREE
            val d2 = dx * dx + dy * dy
            if (d2 > r2) continue
            count++
            if (d2 < bestAnyD2) {
                bestAnyD2 = d2
                bestAny = row
            }
            if (row.id in favorites && d2 < bestFavoriteD2) {
                bestFavoriteD2 = d2
                bestFavorite = row
            }
        }
        val rep = bestFavorite ?: bestAny
        return MediaFootprintRegion(
            id = region.id,
            name = region.name,
            lat = region.centerLat,
            lon = region.centerLon,
            radiusM = region.radiusM,
            color = region.color,
            description = region.description,
            count = count,
            samplePath = rep?.path ?: "",
            sampleName = rep?.name ?: "",
            sampleRating = rep?.rating ?: "unknown",
            exp = exp,
            sig = rep?.let { signer.sign(it.path, exp) } ?: "",
        )
    }

    /** 带「喜欢」功能标签（未否决）的资产 id 集合；查不到时返回空集（只影响代表图的挑法）。 */
    private suspend fun favoriteAssetIds(ctx: Context): Set<Long> =
        runCatching { assetTags.findAllByNamespaceAndName(ctx.db, NAMESPACE_FEATURE, TAG_FAVORITE).getOrThrow() }
            .getOrNull()
            ?.mapTo(HashSet()) { it.assetId }
            ?: emptySet()

    private fun toPeriod(period: FootprintClusterer.Period, rows: List<MediaGeoAssetEntity>, exp: Long): MediaFootprintPeriod {
        val rep = rows[period.representative]
        return MediaFootprintPeriod(
            id = "t:${period.startAt}:${period.endAt}",
            startAt = period.startAt,
            endAt = period.endAt,
            count = period.count.toLong(),
            samplePath = rep.path,
            sampleName = rep.name,
            sampleRating = rep.rating,
            exp = exp,
            sig = signer.sign(rep.path, exp),
        )
    }

    private companion object {
        /** 快照缓存时长：库里的 GPS 由后台扫描写入，变化很慢。 */
        const val CACHE_TTL_MS: Long = 30_000

        const val METERS_PER_DEGREE: Double = 111_320.0

        /** 热点数量上限（防极端数据下前端画不动）。 */
        const val MAX_CLUSTERS: Int = 500

        /** 时间会话上限。 */
        const val MAX_PERIODS: Int = 400

        /** 单次散点查询上限。 */
        const val MAX_POINTS: Int = 2000
    }
}
