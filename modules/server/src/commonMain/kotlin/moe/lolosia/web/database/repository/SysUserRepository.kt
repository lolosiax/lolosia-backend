package moe.lolosia.web.database.repository

import io.github.smyrgeorge.sqlx4k.CrudRepository
import io.github.smyrgeorge.sqlx4k.QueryExecutor
import io.github.smyrgeorge.sqlx4k.annotation.Query
import io.github.smyrgeorge.sqlx4k.annotation.Repository
import moe.lolosia.web.database.model.SysUserEntity
import moe.lolosia.web.util.router.Injectable
import kotlin.time.Instant

/**
 * `sys_user` 的仓库。
 *
 * `deleted = 0` 的过滤写在每条查询里，不做隐式过滤。
 *
 * @author 洛洛希雅Lolosia
 */
@Repository
@Injectable
interface SysUserRepository : CrudRepository<SysUserEntity> {

    @Query("select * from sys_user where id = :id and deleted = 0")
    suspend fun findOneById(context: QueryExecutor, id: String): Result<SysUserEntity?>

    @Query("select * from sys_user where user_name = :userName and deleted = 0")
    suspend fun findOneByUserName(context: QueryExecutor, userName: String): Result<SysUserEntity?>

    /** 登录用：账号既可以是 `user_name` 也可以是 `phone`（旧版 `or { userName.eq … phone.eq … }`）。 */
    @Query("select * from sys_user where (user_name = :account or phone = :account) and deleted = 0 limit 1")
    suspend fun findOneByAccount(context: QueryExecutor, account: String): Result<SysUserEntity?>

    /** 关键字模糊搜索（用户名 / 真名 / 手机 / 邮箱），供「新建用户查重」与「搜索用户」共用。 */
    @Query(
        "select * from sys_user where deleted = 0 and (user_name like :keys or real_name like :keys " +
            "or phone like :keys or email like :keys) order by id asc limit :limit"
    )
    suspend fun findAllByKeys(context: QueryExecutor, keys: String, limit: Int): Result<List<SysUserEntity>>

    /**
     * 用户名 / 手机 / 邮箱是否已被占用（三者 OR）。
     *
     * [phone]、[email] 传 `null` 表示这一列不参与判重。
     */
    @Query(
        "select count(*) from sys_user where deleted = 0 and (user_name = :userName " +
            "or (:phone is not null and phone = :phone) or (:email is not null and email = :email))"
    )
    suspend fun countByUserNameOrPhoneOrEmail(
        context: QueryExecutor,
        userName: String,
        phone: String?,
        email: String?,
    ): Result<Long>

    /**
     * 插入用户。`created_at` / `updated_at` 必须显式给值 —— 库端默认值是
     * `current_timestamp()` 与 `'0000-00-00 00:00:00'`，靠默认值会写出脏数据。
     */
    @Query(
        "insert into sys_user(id, user_name, password, real_name, phone, email, avatar, is_use, " +
            "created_at, created_by, updated_at, updated_by, deleted) values (:id, :userName, :password, " +
            ":realName, :phone, :email, :avatar, :isUse, :createdAt, :createdBy, :updatedAt, :updatedBy, 0)"
    )
    suspend fun executeInsert(
        context: QueryExecutor,
        id: String,
        userName: String,
        password: String?,
        realName: String?,
        phone: String?,
        email: String?,
        avatar: String?,
        isUse: Boolean,
        createdAt: Instant,
        createdBy: String?,
        updatedAt: Instant,
        updatedBy: String?,
    ): Result<Long>

    @Query("update sys_user set avatar = :avatar, updated_at = :updatedAt, updated_by = :updatedBy where id = :id")
    suspend fun executeUpdateAvatarById(
        context: QueryExecutor,
        id: String,
        avatar: String?,
        updatedAt: Instant,
        updatedBy: String?,
    ): Result<Long>

    /**
     * 整行更新「业务列」（不含 `created_at` / `created_by`）。
     *
     * 供服务层做**读-改-写**的部分更新用：先读出实体，把要改的字段覆盖掉，再整行写回 ——
     * 这样 `null = 不修改` 的语义不会有「不小心把 null 写进库」的风险，
     * 也不必为每个字段组合单开一条 SQL。`password` 由调用方决定（不改就传回原值）。
     */
    @Query(
        "update sys_user set user_name = :userName, password = :password, real_name = :realName, " +
            "phone = :phone, email = :email, avatar = :avatar, is_use = :isUse, " +
            "updated_at = :updatedAt, updated_by = :updatedBy where id = :id and deleted = 0"
    )
    suspend fun executeUpdateById(
        context: QueryExecutor,
        id: String,
        userName: String,
        password: String?,
        realName: String?,
        phone: String?,
        email: String?,
        avatar: String?,
        isUse: Boolean?,
        updatedAt: Instant,
        updatedBy: String?,
    ): Result<Long>

    /** 软删除用户（`@Query` 不支持批量，多 id 请在服务层循环）。 */
    @Query("update sys_user set deleted = 1, updated_at = :updatedAt, updated_by = :updatedBy where id = :id and deleted = 0")
    suspend fun executeSoftDeleteById(
        context: QueryExecutor,
        id: String,
        updatedAt: Instant,
        updatedBy: String?,
    ): Result<Long>
}
