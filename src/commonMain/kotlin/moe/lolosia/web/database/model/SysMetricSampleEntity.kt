package moe.lolosia.web.database.model

import io.github.smyrgeorge.sqlx4k.annotation.Id
import io.github.smyrgeorge.sqlx4k.annotation.Table
import kotlin.time.Instant

/**
 * `sys_metric_sample`：系统指标时序，一次采样一行。
 *
 * 纯派生数据，随时可清空。服务层从系统计数器算出占用率与速率后按分钟降采样写入
 * （高频样本只留在内存环形缓冲里），旧行按 `dashboard.retainHours` 周期清理。
 *
 * @author 洛洛希雅Lolosia
 */
@Table("sys_metric_sample")
data class SysMetricSampleEntity(
    @Id
    val id: Long = 0L,
    /** 采样时刻。 */
    val sampledAt: Instant,
    /** CPU 占用率（0~100）。 */
    val cpu: Double = 0.0,
    /** 内存占用率（0~100）。 */
    val mem: Double = 0.0,
    /** 已用内存（字节）。 */
    val memUsed: Long = 0L,
    /** 总内存（字节）。 */
    val memTotal: Long = 0L,
    /** 磁盘占用率（0~100）。 */
    val disk: Double = 0.0,
    /** 已用磁盘（字节）。 */
    val diskUsed: Long = 0L,
    /** 总磁盘（字节）。 */
    val diskTotal: Long = 0L,
    /** 入站速率（MB/s）。 */
    val netIn: Double = 0.0,
    /** 出站速率（MB/s）。 */
    val netOut: Double = 0.0,
    /** 读速率（MB/s）。 */
    val diskRead: Double = 0.0,
    /** 写速率（MB/s）。 */
    val diskWrite: Double = 0.0,
)
