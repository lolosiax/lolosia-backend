package moe.lolosia.web.util.llm

import com.fasterxml.jackson.databind.ObjectMapper
import moe.lolosia.web.util.packageLogger
import kotlinx.coroutines.reactive.asFlow
import org.springframework.ai.chat.client.ChatClient
import org.springframework.ai.chat.client.DefaultChatClient.DefaultChatClientRequestSpec
import org.springframework.ai.tool.ToolCallback
import org.springframework.ai.tool.definition.ToolDefinition

val logger = packageLogger("moe.lolosia.web.util.llm.AIGetResultKt")

private val inputSchema = """
    {"type": "object","properties": {"value": {"title": "值","type": "string"}},"required": ["value"]}
""".trimIndent()

suspend fun ChatClient.request(system: String, question: String): List<String> {
    val result = mutableListOf<String>()
    val spec = prompt()
        .system("${system}\n若成功，**请使用FunctionCall设置返回结果**；否则，报告任务失败。")
        .user(question)
    spec as DefaultChatClientRequestSpec
    spec.toolCallbacks.clear()
    spec.toolCallbacks(
        object : ToolCallback {
            @Suppress("UNCHECKED_CAST")
            override fun call(toolInput: String): String {
                try {
                    val obj = ObjectMapper().readValue(toolInput, Map::class.java) as Map<String, String>
                    result.add(obj["value"] ?: "")
                }
                catch (e: Throwable) {
                    logger.error("发生异常", e)
                }
                return "第${result.size - 1}个结果设置成功"
            }

            override fun getToolDefinition(): ToolDefinition {
                return ToolDefinition.builder()
                    .name("set_result")
                    .description("设置这次系统请求的处理结果，多个结果请多次调用")
                    .inputSchema(inputSchema)
                    .build()
            }

        }
    )
    withAIFlowGuard(spec) {
        var rs = ""
        stream().content().asFlow().collect { rs += it }
        logger.info(rs)
        if (result.isEmpty()) {
            result += rs
            logger.warn("大模型没有使用Function Call设置结果，采用对话作为回答")
        }
    }
    return result
}