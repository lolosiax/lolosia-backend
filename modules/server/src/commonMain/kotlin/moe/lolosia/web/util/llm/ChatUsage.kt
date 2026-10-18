package moe.lolosia.web.util.llm

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * 一次请求的 token 用量。
 *
 * 只在流式请求带上 `stream_options.include_usage=true` 时，才会在**末尾帧**（`choices` 为空）
 * 随 `usage` 字段返回。它是成本台账的**权威口径** —— 请求前的分词器估算只用于预算拦截。
 *
 * @author 洛洛希雅Lolosia
 */
@Serializable
data class ChatUsage(
    @SerialName("prompt_tokens") val promptTokens: Int = 0,
    @SerialName("completion_tokens") val completionTokens: Int = 0,
    @SerialName("total_tokens") val totalTokens: Int = 0,
    /** 命中前缀缓存的输入 token；DeepSeek 独有，缺省 0。 */
    @SerialName("prompt_cache_hit_tokens") val cacheHitTokens: Int = 0,
    /** 未命中缓存的输入 token；缺省 0。 */
    @SerialName("prompt_cache_miss_tokens") val cacheMissTokens: Int = 0,
)
