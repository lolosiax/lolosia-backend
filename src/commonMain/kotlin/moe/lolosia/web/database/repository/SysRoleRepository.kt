package moe.lolosia.web.database.repository

import io.github.smyrgeorge.sqlx4k.CrudRepository
import io.github.smyrgeorge.sqlx4k.QueryExecutor
import io.github.smyrgeorge.sqlx4k.annotation.Query
import io.github.smyrgeorge.sqlx4k.annotation.Repository
import moe.lolosia.web.database.model.SysRoleEntity
import moe.lolosia.web.util.router.Injectable
import kotlin.time.Instant

/**
 * `sys_role` 的仓库。
 *
 * @author 洛洛希雅Lolosia
 */
@Repository
@Injectable
public interface SysRoleRepository : CrudRepository<SysRoleEntity> {

    /** 全部未删除角色，按 id 升序。 */
    @Query("select * from sys_role where deleted = 0 order by id asc")
    public suspend fun findAllByDeletedFalse(context: QueryExecutor): Result<List<SysRoleEntity>>

    @Query("select * from sys_role where id = :id and deleted = 0")
    public suspend fun findOneById(context: QueryExecutor, id: Long): Result<SysRoleEntity?>

    @Query("select * from sys_role where type = :type and deleted = 0")
    public suspend fun findAllByType(context: QueryExecutor, type: String): Result<List<SysRoleEntity>>

    /** 分页列表（角色名 / 类型模糊匹配），`keys` 传空串即不过滤。 */
    @Query(
        "select * from sys_role where deleted = 0 and (role_name like :keys or type like :keys) " +
            "order by id asc limit :limit offset :offset"
    )
    public suspend fun findAllByKeys(
        context: QueryExecutor,
        keys: String,
        limit: Int,
        offset: Int,
    ): Result<List<SysRoleEntity>>

    @Query("select count(*) from sys_role where deleted = 0 and (role_name like :keys or type like :keys)")
    public suspend fun countByKeys(context: QueryExecutor, keys: String): Result<Long>

    @Query(
        "insert into sys_role(role_name, type, created_at, created_by, updated_at, updated_by, deleted) " +
            "values (:roleName, :type, :createdAt, :createdBy, :updatedAt, :updatedBy, 0)"
    )
    public suspend fun executeInsert(
        context: QueryExecutor,
        roleName: String?,
        type: String?,
        createdAt: Instant,
        createdBy: String?,
        updatedAt: Instant,
        updatedBy: String?,
    ): Result<Long>

    @Query("update sys_role set role_name = :roleName, type = :type, updated_at = :updatedAt, updated_by = :updatedBy where id = :id")
    public suspend fun executeUpdateById(
        context: QueryExecutor,
        id: Long,
        roleName: String?,
        type: String?,
        updatedAt: Instant,
        updatedBy: String?,
    ): Result<Long>

    @Query("update sys_role set deleted = 1, updated_at = :updatedAt, updated_by = :updatedBy where id = :id")
    public suspend fun executeSoftDeleteById(
        context: QueryExecutor,
        id: Long,
        updatedAt: Instant,
        updatedBy: String?,
    ): Result<Long>
}
