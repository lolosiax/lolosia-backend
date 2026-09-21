package moe.lolosia.web.db

import io.github.smyrgeorge.sqlx4k.CrudRepository
import io.github.smyrgeorge.sqlx4k.QueryExecutor
import io.github.smyrgeorge.sqlx4k.annotation.Query
import io.github.smyrgeorge.sqlx4k.annotation.Repository
import moe.lolosia.web.db.model.SessionEntity
import kotlinx.datetime.LocalDateTime

/**
 * `sys_sessions` 的仓库。
 *
 * 会话读多写少、且整条记录就是一份 JSON，故只用少量 `@Query`。
 *
 * ⚠️ **MySQL 方言下不要用 `CrudRepository` 自带的 `insert` / `update`**（生成器拼成多语句脚本，
 * MariaDB 报 `1064`），写入用下面的单语句方法。
 *
 * @author 洛洛希雅Lolosia
 */
@Repository
public interface SessionRepository : CrudRepository<SessionEntity> {

    @Query("insert into sys_sessions(id, data, created_at, updated_at, deleted) values (:id, :data, :createdAt, :updatedAt, 0)")
    public suspend fun executeInsert(
        context: QueryExecutor,
        id: String,
        data: String,
        createdAt: LocalDateTime,
        updatedAt: LocalDateTime,
    ): Result<Long>

    @Query("update sys_sessions set data = :data, updated_at = :updatedAt where id = :id")
    public suspend fun executeUpdateDataById(
        context: QueryExecutor,
        id: String,
        data: String,
        updatedAt: LocalDateTime,
    ): Result<Long>

    /** 取一条未删除的会话。 */
    @Query("select * from sys_sessions where id = :id and deleted = 0")
    public suspend fun findOneById(context: QueryExecutor, id: String): Result<SessionEntity?>

    /** 物理删除一条会话。 */
    @Query("delete from sys_sessions where id = :id")
    public suspend fun executeDeleteById(context: QueryExecutor, id: String): Result<Long>
}
