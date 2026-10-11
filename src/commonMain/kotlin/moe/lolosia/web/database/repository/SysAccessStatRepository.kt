package moe.lolosia.web.database.repository

import io.github.smyrgeorge.sqlx4k.CrudRepository
import io.github.smyrgeorge.sqlx4k.QueryExecutor
import io.github.smyrgeorge.sqlx4k.annotation.Query
import io.github.smyrgeorge.sqlx4k.annotation.Repository
import moe.lolosia.web.database.model.SysAccessStatEntity
import moe.lolosia.web.util.router.Injectable
import kotlin.time.Instant

/**
 * `sys_access_stat` 的仓库。
 *
 * 聚合（IP / Origin 排行）不写在这里：`@Query` 的返回必须映射成实体、且校验不通过聚合列，
 * 所以排行取回窗口内的计数行后在服务层用 Kotlin 合并（窗口是小时桶，行数可控）。
 *
 * 累加走**读-改-写**而不是 `on duplicate key update`：写入只在服务层的单个后台协程里串行发生，
 * 不存在并发冲突，也就不需要那条 KSP 解析没被验证过的 MySQL 专有语法。
 *
 * @author 洛洛希雅Lolosia
 */
@Repository
@Injectable
interface SysAccessStatRepository : CrudRepository<SysAccessStatEntity> {

    /** 取窗口内的计数行（服务层据此在内存里做 IP / Host 排行）。 */
    @Query("select * from sys_access_stat where deleted = 0 and bucket_at >= :since order by bucket_at asc")
    suspend fun findAllByBucketAtAfter(context: QueryExecutor, since: Instant): Result<List<SysAccessStatEntity>>

    /** 定位某个「桶 + IP + Host」的既有行。 */
    @Query(
        "select * from sys_access_stat where bucket_at = :bucketAt and ip = :ip and host = :host " +
            "and deleted = 0 limit 1"
    )
    suspend fun findOneByBucketAtAndIpAndHost(
        context: QueryExecutor,
        bucketAt: Instant,
        ip: String,
        host: String,
    ): Result<SysAccessStatEntity?>

    @Query(
        "insert into sys_access_stat(bucket_at, ip, host, hits, created_at, updated_at, deleted) " +
            "values (:bucketAt, :ip, :host, :hits, :createdAt, :updatedAt, 0)"
    )
    suspend fun executeInsert(
        context: QueryExecutor,
        bucketAt: Instant,
        ip: String,
        host: String,
        hits: Long,
        createdAt: Instant,
        updatedAt: Instant,
    ): Result<Long>

    /** 累加后的总命中数回写（读-改-写的第二半）。 */
    @Query("update sys_access_stat set hits = :hits, updated_at = :updatedAt where id = :id")
    suspend fun executeUpdateHitsById(
        context: QueryExecutor,
        id: Long,
        hits: Long,
        updatedAt: Instant,
    ): Result<Long>

    /** 清理早于 [before] 的统计桶。 */
    @Query("delete from sys_access_stat where bucket_at < :before")
    suspend fun deleteByBucketAtBefore(context: QueryExecutor, before: Instant): Result<Long>
}
