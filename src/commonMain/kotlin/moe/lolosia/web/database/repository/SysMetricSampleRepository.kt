package moe.lolosia.web.database.repository

import io.github.smyrgeorge.sqlx4k.CrudRepository
import io.github.smyrgeorge.sqlx4k.QueryExecutor
import io.github.smyrgeorge.sqlx4k.annotation.Query
import io.github.smyrgeorge.sqlx4k.annotation.Repository
import moe.lolosia.web.database.model.SysMetricSampleEntity
import moe.lolosia.web.util.router.Injectable
import kotlin.time.Instant

/**
 * `sys_metric_sample` 的仓库。
 *
 * 全部是**单语句**（`CrudRepository` 自带的 `insert` / `update` 会被拼成多语句脚本，MariaDB 预处理报 1064）。
 * 写入只走 [executeInsert]，清理只走 [executeDeleteBySampledAtBefore]；读取按时间窗口取。
 *
 * @author 洛洛希雅Lolosia
 */
@Repository
@Injectable
interface SysMetricSampleRepository : CrudRepository<SysMetricSampleEntity> {

    @Query(
        "insert into sys_metric_sample(sampled_at, cpu, mem, mem_used, mem_total, disk, disk_used, disk_total, " +
            "net_in, net_out, disk_read, disk_write) " +
            "values (:sampledAt, :cpu, :mem, :memUsed, :memTotal, :disk, :diskUsed, :diskTotal, " +
            ":netIn, :netOut, :diskRead, :diskWrite)"
    )
    suspend fun executeInsert(
        context: QueryExecutor,
        sampledAt: Instant,
        cpu: Double,
        mem: Double,
        memUsed: Long,
        memTotal: Long,
        disk: Double,
        diskUsed: Long,
        diskTotal: Long,
        netIn: Double,
        netOut: Double,
        diskRead: Double,
        diskWrite: Double,
    ): Result<Long>

    /** 取窗口内的样本（升序），用于曲线历史回填。 */
    @Query("select * from sys_metric_sample where sampled_at >= :since order by sampled_at asc")
    suspend fun findAllBySampledAtAfter(context: QueryExecutor, since: Instant): Result<List<SysMetricSampleEntity>>

    /** 清理早于 [before] 的旧行（按时间窗滚动保留）。 */
    @Query("delete from sys_metric_sample where sampled_at < :before")
    suspend fun executeDeleteBySampledAtBefore(context: QueryExecutor, before: Instant): Result<Long>
}
