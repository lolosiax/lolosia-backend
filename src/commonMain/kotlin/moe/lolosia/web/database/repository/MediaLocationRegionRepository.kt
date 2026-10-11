package moe.lolosia.web.database.repository

import io.github.smyrgeorge.sqlx4k.CrudRepository
import io.github.smyrgeorge.sqlx4k.QueryExecutor
import io.github.smyrgeorge.sqlx4k.annotation.Query
import io.github.smyrgeorge.sqlx4k.annotation.Repository
import moe.lolosia.web.database.model.MediaLocationRegionEntity
import moe.lolosia.web.util.router.Injectable
import kotlin.time.Instant

/**
 * `media_location_region` 的仓库：足迹地图里人工命名的区域圈。
 *
 * 全表按 `deleted = 0` 取（量级很小 —— 人手工画的那几十个圈），写入只有「插入 / 更新 / 软删」三种。
 *
 * @author 洛洛希雅Lolosia
 */
@Repository
@Injectable
interface MediaLocationRegionRepository : CrudRepository<MediaLocationRegionEntity> {

    @Query("select * from media_location_region where deleted = 0 order by id asc")
    suspend fun findAllByDeletedFalse(context: QueryExecutor): Result<List<MediaLocationRegionEntity>>

    @Query("select * from media_location_region where id = :id and deleted = 0")
    suspend fun findOneById(context: QueryExecutor, id: Long): Result<MediaLocationRegionEntity?>

    @Query(
        "insert into media_location_region(name, center_lat, center_lon, radius_m, color, description, " +
            "created_at, created_by, updated_at, updated_by, deleted) " +
            "values (:name, :centerLat, :centerLon, :radiusM, :color, :description, " +
            ":createdAt, :createdBy, :updatedAt, :updatedBy, 0)"
    )
    suspend fun executeInsert(
        context: QueryExecutor,
        name: String,
        centerLat: Double,
        centerLon: Double,
        radiusM: Double,
        color: String?,
        description: String?,
        createdAt: Instant,
        createdBy: String?,
        updatedAt: Instant,
        updatedBy: String?,
    ): Result<Long>

    @Query(
        "update media_location_region set name = :name, center_lat = :centerLat, center_lon = :centerLon, " +
            "radius_m = :radiusM, color = :color, description = :description, " +
            "updated_at = :updatedAt, updated_by = :updatedBy where id = :id"
    )
    suspend fun executeUpdateById(
        context: QueryExecutor,
        id: Long,
        name: String,
        centerLat: Double,
        centerLon: Double,
        radiusM: Double,
        color: String?,
        description: String?,
        updatedAt: Instant,
        updatedBy: String?,
    ): Result<Long>

    @Query("update media_location_region set deleted = 1, updated_at = :updatedAt, updated_by = :updatedBy where id = :id")
    suspend fun executeSoftDeleteById(
        context: QueryExecutor,
        id: Long,
        updatedAt: Instant,
        updatedBy: String?,
    ): Result<Long>
}
