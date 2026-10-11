package moe.lolosia.web.database.model

import io.github.smyrgeorge.sqlx4k.annotation.Id
import io.github.smyrgeorge.sqlx4k.annotation.Table
import kotlin.time.Instant

/**
 * `sys_access_stat`：仪表板的访问统计，一行 = 某个「小时桶 + IP + Host」上的累计请求数。
 *
 * 由 `AccessStatService` 在内存里按小时聚合、周期落库（请求链路本身不写库）。
 * 纯派生数据，可清空。
 *
 * @author 洛洛希雅Lolosia
 */
@Table("sys_access_stat")
data class SysAccessStatEntity(
    @Id
    val id: Long = 0L,
    /** 小时对齐的统计桶（UTC）。 */
    val bucketAt: Instant,
    /** 客户端 IP（优先取 `X-Forwarded-For` 首个）。 */
    val ip: String,
    /** Host 头（Origin 维度的近似）。 */
    val host: String = "",
    /** 该桶内累计请求数。 */
    val hits: Long = 0L,
    val createdAt: Instant? = null,
    val updatedAt: Instant? = null,
    val deleted: Boolean = false,
)
