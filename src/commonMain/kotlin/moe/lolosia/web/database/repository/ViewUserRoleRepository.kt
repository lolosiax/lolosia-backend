package moe.lolosia.web.database.repository

import io.github.smyrgeorge.sqlx4k.CrudRepository
import io.github.smyrgeorge.sqlx4k.QueryExecutor
import io.github.smyrgeorge.sqlx4k.annotation.Query
import io.github.smyrgeorge.sqlx4k.annotation.Repository
import moe.lolosia.web.database.model.ViewUserRoleEntity
import moe.lolosia.web.util.router.Injectable

/**
 * `view_user_role`（用户 + 角色联表视图）的仓库 —— 只读。
 *
 * 视图本身已过滤掉已删除的用户与角色。
 *
 * @author 洛洛希雅Lolosia
 */
@Repository
@Injectable
public interface ViewUserRoleRepository : CrudRepository<ViewUserRoleEntity> {

    @Query("select * from view_user_role where user_id = :userId and deleted = 0 order by role_id asc")
    public suspend fun findAllByUserId(context: QueryExecutor, userId: String): Result<List<ViewUserRoleEntity>>

    @Query("select * from view_user_role where user_id = :userId and deleted = 0 order by role_id asc limit 1")
    public suspend fun findOneByUserId(context: QueryExecutor, userId: String): Result<ViewUserRoleEntity?>

    /**
     * 分页列表：`keys` 匹配用户名 / 真名 / 手机 / 邮箱 / 角色名（传空串即不过滤）。
     *
     * 排序：`role_id asc, user_name asc`。
     */
    @Query(
        "select * from view_user_role where deleted = 0 and (user_name like :keys or real_name like :keys " +
            "or phone like :keys or email like :keys or role_name like :keys) " +
            "order by role_id asc, user_name asc limit :limit offset :offset"
    )
    public suspend fun findAllByKeys(
        context: QueryExecutor,
        keys: String,
        limit: Int,
        offset: Int,
    ): Result<List<ViewUserRoleEntity>>

    @Query(
        "select count(*) from view_user_role where deleted = 0 and (user_name like :keys or real_name like :keys " +
            "or phone like :keys or email like :keys or role_name like :keys)"
    )
    public suspend fun countByKeys(context: QueryExecutor, keys: String): Result<Long>
}
