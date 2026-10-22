package moe.lolosia.web.util.llm

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** OpenAI 流式响应体（`chat.completion.chunk`）里我们关心的字段。 */
@Serializable
data class ChatCompletionChunk(
    val choices: List<Choice> = emptyList(),
    /** 末尾用量帧才带；需请求里开 `stream_options.include_usage`。 */
    val usage: ChatUsage? = null,
) {

    /** 单个候选。 */
    @Serializable
    data class Choice(
        val delta: Delta = Delta(),
        @SerialName("finish_reason") val finishReason: String? = null,
    )

    /** 增量内容。 */
    @Serializable
    data class Delta(
        val content: String? = null,
        @SerialName("tool_calls") val toolCalls: List<ToolCallDelta>? = null,
    )

    /** 工具调用的增量碎片；靠 [index] 归组。 */
    @Serializable
    data class ToolCallDelta(
        val index: Int = 0,
        val id: String? = null,
        val function: FunctionDelta? = null,
    )

    /** 工具名与参数；名称只出现在首片，[arguments] 逐片拼接。 */
    @Serializable
    data class FunctionDelta(
        val name: String? = null,
        val arguments: String? = null,
    )
}
