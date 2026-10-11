package moe.lolosia.web.util.llm

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * 会话消息。系统提示词由每次请求现给，**不进记忆**（见 [ChatMemory]）。
 */
@Serializable
sealed interface ChatMessage {

    /** 角色名，与 OpenAI wire 一致。 */
    val role: String

    /** 系统提示词。 */
    @Serializable
    @SerialName("system")
    data class System(val text: String) : ChatMessage {
        override val role: String get() = "system"
    }

    /** 用户消息，内容为若干 [ContentPart]（图文混排）。 */
    @Serializable
    @SerialName("user")
    data class User(val parts: List<ContentPart>) : ChatMessage {
        override val role: String get() = "user"

        constructor(text: String) : this(listOf(ContentPart.Text(text)))
    }

    /** 助手消息：可能带文本、也可能带工具调用请求。 */
    @Serializable
    @SerialName("assistant")
    data class Assistant(
        val text: String? = null,
        val toolCalls: List<ToolCall> = emptyList(),
    ) : ChatMessage {
        override val role: String get() = "assistant"
    }

    /** 工具执行结果，[toolCallId] 对应 [Assistant.toolCalls] 里的 id。 */
    @Serializable
    @SerialName("tool")
    data class ToolResult(
        val toolCallId: String,
        val name: String,
        val content: String,
    ) : ChatMessage {
        override val role: String get() = "tool"
    }
}
