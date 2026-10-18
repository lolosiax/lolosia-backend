package moe.lolosia.web.util.llm

import kotlinx.serialization.Serializable

/** 模型请求的一次工具调用；[arguments] 是 JSON 字符串。 */
@Serializable
data class ToolCall(val id: String, val name: String, val arguments: String)
