package moe.lolosia.web.database.model

import io.github.smyrgeorge.sqlx4k.annotation.Id
import io.github.smyrgeorge.sqlx4k.annotation.Table
import kotlin.time.Instant

/**
 * `sys_access_stat`：按「小时桶 + ip + host」聚合的访问命中次数。
 *
 * 纯派生数据，随时可清空。[hourBucket] 是本地时区的 `yyyy-MM-ddTHH`，字典序等价于时间序。
 * 唯一键 (`hour_bucket`, `ip`, `host`) 支撑 upsert 累加，见 `SysAccessStatRepository.executeUpsert`。
 *
 * @author 洛洛希雅Lolosia
 */
@Table("sys_access_stat")
data class SysAccessStatEntity(
    @Id
    val id: Long = 0L,
    /** 本地时区小时桶，形如 `2026-10-10T14`。 */
    val hourBucket: String,
    /** 来源 IP。 */
    val ip: String,
    /** Host 头（含端口）。 */
    val host: String,
    /** 该桶内的命中次数。 */
    val hits: Long = 0L,
    val updatedAt: Instant? = null,
)
