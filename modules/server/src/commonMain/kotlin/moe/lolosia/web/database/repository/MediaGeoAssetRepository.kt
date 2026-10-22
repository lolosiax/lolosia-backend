package moe.lolosia.web.database.repository

import io.github.smyrgeorge.sqlx4k.CrudRepository
import io.github.smyrgeorge.sqlx4k.QueryExecutor
import io.github.smyrgeorge.sqlx4k.annotation.Query
import io.github.smyrgeorge.sqlx4k.annotation.Repository
import moe.lolosia.web.database.model.MediaGeoAssetEntity
import moe.lolosia.web.util.router.Injectable

/**
 * 足迹地图的只读投影仓库：只取聚类与统计要用的列（见 [MediaGeoAssetEntity]）。
 *
 * 一律**显式列名**、不写 `select *`；写方法不用（表的写入都归 [MediaAssetRepository]）。
 * `kind in ('image','video')` 是 SQL 里的字面量列表（不是参数绑定），不触及 sqlx4k「不支持 `IN` 参数」的限制。
 *
 * @author 洛洛希雅Lolosia
 */
@Repository
@Injectable
interface MediaGeoAssetRepository : CrudRepository<MediaGeoAssetEntity> {

    /** 全部**带经纬度**的图片 / 视频，供空间聚类。 */
    @Query(
        "select id, path, name, kind, gps_lat, gps_lon, taken_at, rating, deleted from media_asset " +
            "where deleted = 0 and kind in ('image', 'video') and gps_lat is not null and gps_lon is not null"
    )
    suspend fun findAllByGpsIsNotNull(context: QueryExecutor): Result<List<MediaGeoAssetEntity>>

    /** 全部**带拍摄时间**的图片 / 视频（含没有 GPS 的），按时间升序，供时间聚类。 */
    @Query(
        "select id, path, name, kind, gps_lat, gps_lon, taken_at, rating, deleted from media_asset " +
            "where deleted = 0 and kind in ('image', 'video') and taken_at is not null order by taken_at asc"
    )
    suspend fun findAllByTakenAtIsNotNull(context: QueryExecutor): Result<List<MediaGeoAssetEntity>>

    /** 图片 / 视频总数（分母，用来算「没有 GPS / 没有拍摄时间」的差集）。 */
    @Query("select count(*) from media_asset where deleted = 0 and kind in ('image', 'video')")
    suspend fun countByKindMedia(context: QueryExecutor): Result<Long>
}
