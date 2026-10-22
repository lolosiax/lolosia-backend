package moe.lolosia.web.database.repository

import io.github.smyrgeorge.sqlx4k.CrudRepository
import io.github.smyrgeorge.sqlx4k.QueryExecutor
import io.github.smyrgeorge.sqlx4k.annotation.Query
import io.github.smyrgeorge.sqlx4k.annotation.Repository
import moe.lolosia.web.database.model.SysTokenUsageEntity
import moe.lolosia.web.util.router.Injectable
import kotlin.time.Instant

/**
 * `sys_token_usage` 的仓库。
 *
 * 写入是**累加式 upsert**：唯一键 (`hour_bucket`) 命中时把各列加上本次增量
 * （服务层在内存里按桶聚合后落库，见 `TokenUsageService`）。
 *
 * 成本不在这里算：按小时桶的峰谷档位逐桶折算在服务层用 `LlmPricing.cost` 做。
 *
 * @author 洛洛希雅Lolosia
 */
@Repository
@Injectable
interface SysTokenUsageRepository : CrudRepository<SysTokenUsageEntity> {

    @Query(
        "insert into sys_token_usage(hour_bucket, requests, prompt_tokens, cache_hit_tokens, completion_tokens, updated_at) " +
            "values (:hourBucket, :requests, :promptTokens, :cacheHitTokens, :completionTokens, :updatedAt) " +
            "on duplicate key update requests = requests + :requests, " +
            "prompt_tokens = prompt_tokens + :promptTokens, " +
            "cache_hit_tokens = cache_hit_tokens + :cacheHitTokens, " +
            "completion_tokens = completion_tokens + :completionTokens, " +
            "updated_at = :updatedAt"
    )
    suspend fun executeUpsert(
        context: QueryExecutor,
        hourBucket: String,
        requests: Long,
        promptTokens: Long,
        cacheHitTokens: Long,
        completionTokens: Long,
        updatedAt: Instant,
    ): Result<Long>

    /** 取全部小时桶（量级是「一天 24 行」，全量取回后在 Kotlin 侧聚合）。 */
    @Query("select * from sys_token_usage order by hour_bucket asc")
    suspend fun findAll(context: QueryExecutor): Result<List<SysTokenUsageEntity>>
}
