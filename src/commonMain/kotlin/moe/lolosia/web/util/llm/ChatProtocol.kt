package moe.lolosia.web.util.llm

import kotlinx.serialization.json.*

/** OpenAI 兼容协议：把会话消息 / 工具编成 `chat/completions` 请求体。 */
internal object ChatProtocol {

    /** 构建流式请求体。 */
    fun buildRequest(
        settings: LlmSettings,
        messages: List<ChatMessage>,
        tools: List<ChatTool>,
        toolChoice: ToolChoice,
    ): JsonObject = buildJsonObject {
        put("model", settings.model)
        settings.temperature?.let { put("temperature", it) }
        put("stream", true)
        put("messages", buildJsonArray { messages.forEach { add(messageJson(it)) } })
        if (tools.isNotEmpty()) {
            put("tools", buildJsonArray { tools.forEach { add(toolJson(it)) } })
            put("tool_choice", toolChoiceJson(toolChoice))
        }
    }

    private fun messageJson(message: ChatMessage): JsonObject = when (message) {
        is ChatMessage.System -> buildJsonObject {
            put("role", "system")
            put("content", message.text)
        }

        is ChatMessage.User -> buildJsonObject {
            put("role", "user")
            put("content", userContent(message.parts))
        }

        is ChatMessage.Assistant -> buildJsonObject {
            put("role", "assistant")
            put("content", message.text ?: "")
            if (message.toolCalls.isNotEmpty()) {
                put("tool_calls", buildJsonArray { message.toolCalls.forEach { add(toolCallJson(it)) } })
            }
        }

        is ChatMessage.ToolResult -> buildJsonObject {
            put("role", "tool")
            put("tool_call_id", message.toolCallId)
            put("content", message.content)
        }
    }

    private fun userContent(parts: List<ContentPart>): JsonElement {
        // 纯文本时用字符串（兼容性最好），否则用 content 数组
        if (parts.all { it is ContentPart.Text }) {
            return JsonPrimitive(parts.filterIsInstance<ContentPart.Text>().joinToString("\n") { it.text })
        }
        return buildJsonArray {
            parts.forEach { part ->
                when (part) {
                    is ContentPart.Text -> add(buildJsonObject {
                        put("type", "text")
                        put("text", part.text)
                    })

                    is ContentPart.Image -> add(buildJsonObject {
                        put("type", "image_url")
                        put("image_url", buildJsonObject { put("url", part.url) })
                    })
                }
            }
        }
    }

    private fun toolCallJson(call: ToolCall): JsonObject = buildJsonObject {
        put("id", call.id)
        put("type", "function")
        put("function", buildJsonObject {
            put("name", call.name)
            put("arguments", call.arguments)
        })
    }

    private fun toolJson(tool: ChatTool): JsonObject = buildJsonObject {
        put("type", "function")
        put("function", buildJsonObject {
            put("name", tool.name)
            put("description", tool.description)
            put("parameters", tool.parameters)
        })
    }

    private fun toolChoiceJson(choice: ToolChoice): JsonElement = when (choice) {
        ToolChoice.Auto -> JsonPrimitive("auto")
        ToolChoice.None -> JsonPrimitive("none")
        ToolChoice.Required -> JsonPrimitive("required")
        is ToolChoice.Named -> buildJsonObject {
            put("type", "function")
            put("function", buildJsonObject { put("name", choice.name) })
        }
    }
}
