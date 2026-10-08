package moe.lolosia.web.database.repository

import io.github.smyrgeorge.sqlx4k.CrudRepository
import io.github.smyrgeorge.sqlx4k.QueryExecutor
import io.github.smyrgeorge.sqlx4k.annotation.Query
import io.github.smyrgeorge.sqlx4k.annotation.Repository
import moe.lolosia.web.database.model.MediaTagRuleEntity
import moe.lolosia.web.util.router.Injectable
import kotlin.time.Instant

/**
 * `media_tag_rule` 的仓库。
 *
 * 规则数量是几十条级，服务层一次全取回再在 Kotlin 里匹配（与 `PermissionResolver` 的
 * 「全量取回 + Kotlin 合并」同一路子），不做逐条查库。
 *
 * @author 洛洛希雅Lolosia
 */
@Repository
@Injectable
interface MediaTagRuleRepository : CrudRepository<MediaTagRuleEntity> {

    @Query("select * from media_tag_rule where deleted = 0 order by priority asc, id asc")
    suspend fun findAllByDeletedFalse(context: QueryExecutor): Result<List<MediaTagRuleEntity>>

    @Query("select * from media_tag_rule where id = :id and deleted = 0")
    suspend fun findOneById(context: QueryExecutor, id: Long): Result<MediaTagRuleEntity?>

    /** 库里有没有规则：一条都没有时回退内嵌资源 `media-rules.yaml`（§4.5）。 */
    @Query("select count(*) from media_tag_rule where deleted = 0")
    suspend fun countByDeletedFalse(context: QueryExecutor): Result<Long>

    @Query(
        "insert into media_tag_rule(match_type, pattern, tag_id, rating, priority, enabled, " +
            "created_at, created_by, updated_at, updated_by, deleted) " +
            "values (:matchType, :pattern, :tagId, :rating, :priority, :enabled, " +
            ":createdAt, :createdBy, :updatedAt, :updatedBy, 0)"
    )
    suspend fun executeInsert(
        context: QueryExecutor,
        matchType: String,
        pattern: String,
        tagId: Long?,
        rating: String?,
        priority: Int,
        enabled: Boolean,
        createdAt: Instant,
        createdBy: String?,
        updatedAt: Instant,
        updatedBy: String?,
    ): Result<Long>

    @Query("update media_tag_rule set deleted = 1, updated_at = :updatedAt, updated_by = :updatedBy where id = :id")
    suspend fun executeSoftDeleteById(
        context: QueryExecutor,
        id: Long,
        updatedAt: Instant,
        updatedBy: String?,
    ): Result<Long>
}
