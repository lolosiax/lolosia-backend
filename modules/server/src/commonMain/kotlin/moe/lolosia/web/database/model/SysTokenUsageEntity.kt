package moe.lolosia.web.database.model

import io.github.smyrgeorge.sqlx4k.annotation.Id
import io.github.smyrgeorge.sqlx4k.annotation.Table
import kotlin.time.Instant

/**
 * `sys_token_usage`：按「北京时间小时桶」聚合的 LLM token 用量。
 *
 * 纯派生数据，随时可清空。[hourBucket] 形如 `2026-10-11T14`，字典序等价于时间序。
 * 唯一键 (`hour_bucket`) 支撑 upsert 累加，见 `SysTokenUsageRepository.executeUpsert`。
 * 成本由 `LlmPricing.cost` 按桶的峰谷档位折算，不落库。
 *
 * @author 洛洛希雅Lolosia
 */
@Table("sys_token_usage")
data class SysTokenUsageEntity(
    @Id
    val id: Long = 0L,
    /** 北京时间小时桶，形如 `2026-10-11T14`。 */
    val hourBucket: String,
    /** 该桶内的请求次数。 */
    val requests: Long = 0L,
    /** 输入 token。 */
    val promptTokens: Long = 0L,
    /** 输入 token 中缓存命中的部分。 */
    val cacheHitTokens: Long = 0L,
    /** 输出 token。 */
    val completionTokens: Long = 0L,
    val updatedAt: Instant? = null,
)
