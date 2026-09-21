package moe.lolosia.web.db

import io.github.smyrgeorge.sqlx4k.CrudRepository
import io.github.smyrgeorge.sqlx4k.QueryExecutor
import io.github.smyrgeorge.sqlx4k.annotation.Query
import io.github.smyrgeorge.sqlx4k.annotation.Repository
import moe.lolosia.web.db.model.SysUserRolesEntity
import moe.lolosia.web.util.router.Injectable
import kotlin.time.Instant

/**
 * `sys_user_roles` 的仓库。
 *
 * @author 洛洛希雅Lolosia
 */
@Repository
@Injectable
public interface SysUserRoleRepository : CrudRepository<SysUserRolesEntity> {

    @Query("select * from sys_user_roles where user_id = :userId and deleted = 0 order by id asc")
    public suspend fun findAllByUserId(context: QueryExecutor, userId: String): Result<List<SysUserRolesEntity>>

    @Query("select * from sys_user_roles where user_id = :userId and deleted = 0 order by id asc limit 1")
    public suspend fun findOneByUserId(context: QueryExecutor, userId: String): Result<SysUserRolesEntity?>

    @Query("select count(*) from sys_user_roles where user_id = :userId and deleted = 0")
    public suspend fun countByUserId(context: QueryExecutor, userId: String): Result<Long>

    /** 某角色是否还有人用（删角色前的守卫）。 */
    @Query("select count(*) from sys_user_roles where role_id = :roleId and deleted = 0")
    public suspend fun countByRoleId(context: QueryExecutor, roleId: Long): Result<Long>

    @Query(
        "insert into sys_user_roles(user_id, role_id, created_at, created_by, updated_at, updated_by, deleted) " +
            "values (:userId, :roleId, :createdAt, :createdBy, :updatedAt, :updatedBy, 0)"
    )
    public suspend fun executeInsert(
        context: QueryExecutor,
        userId: String,
        roleId: Long,
        createdAt: Instant,
        createdBy: String?,
        updatedAt: Instant,
        updatedBy: String?,
    ): Result<Long>

    @Query("update sys_user_roles set role_id = :roleId, updated_at = :updatedAt, updated_by = :updatedBy where user_id = :userId and deleted = 0")
    public suspend fun executeUpdateRoleIdByUserId(
        context: QueryExecutor,
        userId: String,
        roleId: Long,
        updatedAt: Instant,
        updatedBy: String?,
    ): Result<Long>

    /** 软删除某个用户的角色绑定（批量场景请在服务层循环调用：`@Query` 不支持 `IN (列表)`）。 */
    @Query("update sys_user_roles set deleted = 1, updated_at = :updatedAt, updated_by = :updatedBy where user_id = :userId and deleted = 0")
    public suspend fun executeSoftDeleteByUserId(
        context: QueryExecutor,
        userId: String,
        updatedAt: Instant,
        updatedBy: String?,
    ): Result<Long>
}
