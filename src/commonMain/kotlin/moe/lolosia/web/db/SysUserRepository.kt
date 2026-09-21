package moe.lolosia.web.db

import io.github.smyrgeorge.sqlx4k.CrudRepository
import io.github.smyrgeorge.sqlx4k.QueryExecutor
import io.github.smyrgeorge.sqlx4k.annotation.Query
import io.github.smyrgeorge.sqlx4k.annotation.Repository
import moe.lolosia.web.db.model.SysUserEntity
import moe.lolosia.web.util.router.Injectable
import kotlin.time.Instant

/**
 * `sys_user` 的仓库。
 *
 * 约定见 [SysCacheRepository]：首参 `context: QueryExecutor`、方法 `suspend`、方法名前缀决定返回类型；
 * **写入不用生成的 `insert`/`update`**（MySQL 方言下是多语句脚本），改用单语句 `@Query`。
 *
 * `deleted` 的过滤由我们自己写（旧版 Ebean 的 `@SoftDelete` 是自动追加的，这里要显式）。
 *
 * @author 洛洛希雅Lolosia
 */
@Repository
@Injectable
public interface SysUserRepository : CrudRepository<SysUserEntity> {

    @Query("select * from sys_user where id = :id and deleted = 0")
    public suspend fun findOneById(context: QueryExecutor, id: String): Result<SysUserEntity?>

    @Query("select * from sys_user where user_name = :userName and deleted = 0")
    public suspend fun findOneByUserName(context: QueryExecutor, userName: String): Result<SysUserEntity?>

    /** 关键字模糊搜索（用户名 / 真名 / 手机 / 邮箱），供「新建用户查重」与「搜索用户」共用。 */
    @Query(
        "select * from sys_user where deleted = 0 and (user_name like :keys or real_name like :keys " +
            "or phone like :keys or email like :keys) order by id asc limit :limit"
    )
    public suspend fun findAllByKeys(context: QueryExecutor, keys: String, limit: Int): Result<List<SysUserEntity>>

    /**
     * 用户名 / 手机 / 邮箱是否已被占用（三者 OR）。
     *
     * [phone]、[email] 传 `null` 表示「这一列不参与判重」（对应旧版「手机号或邮箱为空就不加入条件」）。
     */
    @Query(
        "select count(*) from sys_user where deleted = 0 and (user_name = :userName " +
            "or (:phone is not null and phone = :phone) or (:email is not null and email = :email))"
    )
    public suspend fun countByUserNameOrPhoneOrEmail(
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
    public suspend fun executeInsert(
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
    public suspend fun executeUpdateAvatarById(
        context: QueryExecutor,
        id: String,
        avatar: String?,
        updatedAt: Instant,
        updatedBy: String?,
    ): Result<Long>
}
