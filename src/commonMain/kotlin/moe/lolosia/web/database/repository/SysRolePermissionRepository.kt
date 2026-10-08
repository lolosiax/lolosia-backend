package moe.lolosia.web.database.repository

import io.github.smyrgeorge.sqlx4k.CrudRepository
import io.github.smyrgeorge.sqlx4k.QueryExecutor
import io.github.smyrgeorge.sqlx4k.annotation.Query
import io.github.smyrgeorge.sqlx4k.annotation.Repository
import moe.lolosia.web.database.model.SysRolePermissionEntity
import moe.lolosia.web.util.router.Injectable
import kotlin.time.Instant

/**
 * `sys_role_permission` 的仓库。
 *
 * @author 洛洛希雅Lolosia
 */
@Repository
@Injectable
interface SysRolePermissionRepository : CrudRepository<SysRolePermissionEntity> {

    /** 某组的全部规则（管理界面用）。 */
    @Query("select * from sys_role_permission where role_id = :roleId and deleted = 0 order by id asc")
    suspend fun findAllByRoleId(context: QueryExecutor, roleId: Long): Result<List<SysRolePermissionEntity>>

    /**
     * 全部已生效规则（判定用）。
     *
     * 表很小（每个组只有个位数条），一次取回后在 Kotlin 里按 `role_id` 分组，
     * 省掉「按组链逐组查一次」的往返（组继承后组数不固定）。
     */
    @Query("select * from sys_role_permission where deleted = 0 order by id asc")
    suspend fun findAllByDeletedFalse(context: QueryExecutor): Result<List<SysRolePermissionEntity>>

    @Query(
        "insert into sys_role_permission(role_id, permission, created_at, created_by, updated_at, updated_by, deleted) " +
            "values (:roleId, :permission, :createdAt, :createdBy, :updatedAt, :updatedBy, 0)"
    )
    suspend fun executeInsert(
        context: QueryExecutor,
        roleId: Long,
        permission: String,
        createdAt: Instant,
        createdBy: String?,
        updatedAt: Instant,
        updatedBy: String?,
    ): Result<Long>

    /** 清空某组的全部规则（`save` 是「全量替换」语义，先软删再插）。 */
    @Query(
        "update sys_role_permission set deleted = 1, updated_at = :updatedAt, updated_by = :updatedBy " +
            "where role_id = :roleId and deleted = 0"
    )
    suspend fun executeSoftDeleteByRoleId(
        context: QueryExecutor,
        roleId: Long,
        updatedAt: Instant,
        updatedBy: String?,
    ): Result<Long>
}
