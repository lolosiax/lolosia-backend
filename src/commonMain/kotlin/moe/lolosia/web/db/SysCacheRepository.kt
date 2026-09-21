package moe.lolosia.web.db

import io.github.smyrgeorge.sqlx4k.CrudRepository
import io.github.smyrgeorge.sqlx4k.QueryExecutor
import io.github.smyrgeorge.sqlx4k.annotation.Query
import io.github.smyrgeorge.sqlx4k.annotation.Repository
import moe.lolosia.web.db.model.SysCacheEntity
import kotlin.time.Instant

/**
 * `sys_cache` 的仓库：CRUD 由 sqlx4k 的 KSP 生成，自定义查询写 `@Query`。
 *
 * 约定（由生成器强制）：
 * - **第一个参数必须是 `context: QueryExecutor`**（连接/事务由调用方传入，仓库不持有连接）；
 * - 方法必须 `suspend`；
 * - 方法名前缀决定返回类型：`findAllBy*` → `Result<List<T>>`、`findOneBy*` → `Result<T?>`、`execute*` → `Result<Long>`。
 *
 * 查询一律只看「未过期」的行；`deleted` 为 NULL 视为未删除。
 *
 * ⚠️ **MySQL 方言下不要用 `CrudRepository` 自带的 `insert` / `update`**：生成器把它们拼成
 * `insert ...; select ...` 这种多语句脚本（为了拿 RETURNING 等价物），而 MariaDB 的单语句
 * 预处理不接受，服务端直接报 `1064`。写入一律用下面这些单语句的 `@Query` 方法。
 * （`delete` 生成的是单语句，可用。）
 *
 * @author 洛洛希雅Lolosia
 */
@Repository
public interface SysCacheRepository : CrudRepository<SysCacheEntity> {

    @Query(
        "insert into sys_cache(path, tag, value, expires_at, created_at, updated_at, deleted) " +
            "values (:path, :tag, :value, :expiresAt, :createdAt, :updatedAt, 0)"
    )
    public suspend fun executeInsert(
        context: QueryExecutor,
        path: String,
        tag: String,
        value: String,
        expiresAt: Instant,
        createdAt: Instant,
        updatedAt: Instant,
    ): Result<Long>

    @Query(
        "select * from sys_cache where path = :path and (deleted is null or deleted = 0) " +
            "and (expires_at is null or expires_at > now()) order by id desc"
    )
    public suspend fun findAllByPath(context: QueryExecutor, path: String): Result<List<SysCacheEntity>>

    @Query(
        "select * from sys_cache where path = :path and tag = :tag and (deleted is null or deleted = 0) " +
            "and (expires_at is null or expires_at > now()) order by id desc limit 1"
    )
    public suspend fun findOneByPathAndTag(context: QueryExecutor, path: String, tag: String): Result<SysCacheEntity?>

    @Query("update sys_cache set value = :value, expires_at = :expiresAt, updated_at = now() where id = :id")
    public suspend fun executeUpdateValueById(
        context: QueryExecutor,
        id: Long,
        value: String,
        expiresAt: Instant,
    ): Result<Long>

    @Query("delete from sys_cache where path = :path and tag = :tag")
    public suspend fun executeDeleteByPathAndTag(context: QueryExecutor, path: String, tag: String): Result<Long>

    @Query("delete from sys_cache where path = :path")
    public suspend fun executeDeleteByPath(context: QueryExecutor, path: String): Result<Long>
}
