package moe.lolosia.web.database.repository

import io.github.smyrgeorge.sqlx4k.CrudRepository
import io.github.smyrgeorge.sqlx4k.QueryExecutor
import io.github.smyrgeorge.sqlx4k.annotation.Query
import io.github.smyrgeorge.sqlx4k.annotation.Repository
import moe.lolosia.web.database.model.SysUserPermissionEntity
import moe.lolosia.web.util.router.Injectable
import kotlin.time.Instant

/**
 * `sys_user_permission` 的仓库。
 *
 * @author 洛洛希雅Lolosia
 */
@Repository
@Injectable
interface SysUserPermissionRepository : CrudRepository<SysUserPermissionEntity> {

    @Query("select * from sys_user_permission where user_id = :userId and deleted = 0 order by id asc")
    suspend fun findAllByUserId(context: QueryExecutor, userId: String): Result<List<SysUserPermissionEntity>>

    @Query(
        "insert into sys_user_permission(user_id, permission, created_at, created_by, updated_at, updated_by, deleted) " +
            "values (:userId, :permission, :createdAt, :createdBy, :updatedAt, :updatedBy, 0)"
    )
    suspend fun executeInsert(
        context: QueryExecutor,
        userId: String,
        permission: String,
        createdAt: Instant,
        createdBy: String?,
        updatedAt: Instant,
        updatedBy: String?,
    ): Result<Long>

    /** 清空某用户的直授规则（`save` 是「全量替换」语义，先软删再插）。 */
    @Query(
        "update sys_user_permission set deleted = 1, updated_at = :updatedAt, updated_by = :updatedBy " +
            "where user_id = :userId and deleted = 0"
    )
    suspend fun executeSoftDeleteByUserId(
        context: QueryExecutor,
        userId: String,
        updatedAt: Instant,
        updatedBy: String?,
    ): Result<Long>
}
