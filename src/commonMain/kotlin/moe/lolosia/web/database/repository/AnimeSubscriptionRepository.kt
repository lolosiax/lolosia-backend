package moe.lolosia.web.database.repository

import io.github.smyrgeorge.sqlx4k.CrudRepository
import io.github.smyrgeorge.sqlx4k.QueryExecutor
import io.github.smyrgeorge.sqlx4k.annotation.Query
import io.github.smyrgeorge.sqlx4k.annotation.Repository
import moe.lolosia.web.database.model.AnimeSubscriptionEntity
import moe.lolosia.web.util.router.Injectable
import kotlin.time.Instant

/**
 * `anime_subscription` 的仓库：用户关注（见 `doc/anime-timetable-design.md` §3.4）。
 *
 * 采集侧按 `series_id` 取「关注了这部番的人」（组过滤在服务层做，量级极小）；
 * 关注增删走软删，唯一性由生成列 `alive_key` 兜底。
 *
 * @author 洛洛希雅Lolosia
 */
@Repository
@Injectable
interface AnimeSubscriptionRepository : CrudRepository<AnimeSubscriptionEntity> {

    @Query("select * from anime_subscription where user_id = :userId and deleted = 0 order by id desc")
    suspend fun findAllByUserId(context: QueryExecutor, userId: String): Result<List<AnimeSubscriptionEntity>>

    @Query("select * from anime_subscription where series_id = :seriesId and deleted = 0 and enabled = 1")
    suspend fun findAllBySeriesId(context: QueryExecutor, seriesId: Long): Result<List<AnimeSubscriptionEntity>>

    @Query(
        "select * from anime_subscription where user_id = :userId and series_id = :seriesId " +
            "and group_name = :groupName and deleted = 0"
    )
    suspend fun findOneByUserIdAndSeriesIdAndGroupName(
        context: QueryExecutor,
        userId: String,
        seriesId: Long,
        groupName: String,
    ): Result<AnimeSubscriptionEntity?>

    @Query(
        "insert into anime_subscription(user_id, series_id, group_name, enabled, created_at, created_by, " +
            "updated_at, updated_by, deleted) " +
            "values (:userId, :seriesId, :groupName, :enabled, :createdAt, :createdBy, :updatedAt, :updatedBy, 0)"
    )
    suspend fun executeInsert(
        context: QueryExecutor,
        userId: String,
        seriesId: Long,
        groupName: String,
        enabled: Boolean,
        createdAt: Instant,
        createdBy: String?,
        updatedAt: Instant,
        updatedBy: String?,
    ): Result<Long>

    @Query("update anime_subscription set enabled = :enabled, updated_at = :updatedAt, updated_by = :updatedBy where id = :id")
    suspend fun executeUpdateEnabledById(
        context: QueryExecutor,
        id: Long,
        enabled: Boolean,
        updatedAt: Instant,
        updatedBy: String?,
    ): Result<Long>

    @Query(
        "update anime_subscription set deleted = 1, updated_at = :updatedAt, updated_by = :updatedBy " +
            "where id = :id and user_id = :userId and deleted = 0"
    )
    suspend fun executeSoftDeleteById(
        context: QueryExecutor,
        id: Long,
        userId: String,
        updatedAt: Instant,
        updatedBy: String?,
    ): Result<Long>
}
