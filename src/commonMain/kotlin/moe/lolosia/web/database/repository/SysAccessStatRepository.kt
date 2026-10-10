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
 * 写入是**累加式 upsert**：唯一键 (`hour_bucket`, `ip`, `host`) 命中时把 `hits` 加上本次增量
 * （服务层在内存里按桶聚合后一次性落库，见 `AccessStatService`）。
 *
 * 聚合（top-ip / top-host）**不写进 `@Query`**（返回会被当实体映射、`group by` 的列对不上表结构），
 * 走服务层的原生 SQL 逃生口；这里只按小时桶范围取原始行，聚合在 Kotlin 侧做。
 *
 * @author 洛洛希雅Lolosia
 */
@Repository
@Injectable
interface SysAccessStatRepository : CrudRepository<SysAccessStatEntity> {

    @Query(
        "insert into sys_access_stat(hour_bucket, ip, host, hits, updated_at) " +
            "values (:hourBucket, :ip, :host, :delta, :updatedAt) " +
            "on duplicate key update hits = hits + :delta, updated_at = :updatedAt"
    )
    suspend fun executeUpsert(
        context: QueryExecutor,
        hourBucket: String,
        ip: String,
        host: String,
        delta: Long,
        updatedAt: Instant,
    ): Result<Long>

    /** 取某小时桶之后（含）的所有行，聚合在 Kotlin 侧做（小时桶字典序等价于时间序）。 */
    @Query("select * from sys_access_stat where hour_bucket >= :hourBucket order by hour_bucket asc")
    suspend fun findAllByHourBucketAfter(
        context: QueryExecutor,
        hourBucket: String,
    ): Result<List<SysAccessStatEntity>>
}
