package moe.lolosia.web.database.model

import io.github.smyrgeorge.sqlx4k.annotation.Id
import io.github.smyrgeorge.sqlx4k.annotation.Table
import kotlin.time.Instant

/**
 * `sys_metric_sample`：仪表板的系统指标采样，一行 = 一次采样（宽表）。
 *
 * [cpu] / [mem] / [disk] 是百分比（0~100）；[netIn] / [netOut] / [diskRead] / [diskWrite]
 * 是速率（字节/秒）。数据由后台采样服务写入，纯派生、可清空。
 *
 * @author 洛洛希雅Lolosia
 */
@Table("sys_metric_sample")
data class SysMetricSampleEntity(
    @Id
    val id: Long = 0L,
    /** CPU 使用率（%）。 */
    val cpu: Double = 0.0,
    /** 内存使用率（%）。 */
    val mem: Double = 0.0,
    /** 根分区使用率（%）。 */
    val disk: Double = 0.0,
    /** 入站速率（字节/秒）。 */
    val netIn: Double = 0.0,
    /** 出站速率（字节/秒）。 */
    val netOut: Double = 0.0,
    /** 磁盘读速率（字节/秒）。 */
    val diskRead: Double = 0.0,
    /** 磁盘写速率（字节/秒）。 */
    val diskWrite: Double = 0.0,
    /** 采样时刻（UTC）。 */
    val sampledAt: Instant,
    val createdAt: Instant? = null,
    val deleted: Boolean = false,
)
