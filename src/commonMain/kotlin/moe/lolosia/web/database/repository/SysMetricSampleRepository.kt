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
 * 写入与清理都是**单语句**（`@Query` 不支持多语句脚本）；读取按时间区间一次取回后在服务层降采样。
 *
 * @author 洛洛希雅Lolosia
 */
@Repository
@Injectable
interface SysMetricSampleRepository : CrudRepository<SysMetricSampleEntity> {

    /** 取某时刻之后的全部采样，按时间升序（服务层据此降采样成曲线）。 */
    @Query("select * from sys_metric_sample where deleted = 0 and sampled_at >= :since order by sampled_at asc")
    suspend fun findAllBySampledAtAfterOrderBySampledAtAsc(
        context: QueryExecutor,
        since: Instant,
    ): Result<List<SysMetricSampleEntity>>

    @Query(
        "insert into sys_metric_sample(cpu, mem, disk, net_in, net_out, disk_read, disk_write, " +
            "sampled_at, created_at, deleted) " +
            "values (:cpu, :mem, :disk, :netIn, :netOut, :diskRead, :diskWrite, :sampledAt, :createdAt, 0)"
    )
    suspend fun executeInsert(
        context: QueryExecutor,
        cpu: Double,
        mem: Double,
        disk: Double,
        netIn: Double,
        netOut: Double,
        diskRead: Double,
        diskWrite: Double,
        sampledAt: Instant,
        createdAt: Instant,
    ): Result<Long>

    /** 清理早于 [before] 的采样（保留窗口外的直接物理删除）。 */
    @Query("delete from sys_metric_sample where sampled_at < :before")
    suspend fun deleteBySampledAtBefore(context: QueryExecutor, before: Instant): Result<Long>
}
