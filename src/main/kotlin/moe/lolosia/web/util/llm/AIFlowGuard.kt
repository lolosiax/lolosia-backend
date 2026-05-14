package moe.lolosia.web.util.llm

import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.ai.chat.client.ChatClient
import org.springframework.ai.tool.ToolCallback
import org.springframework.ai.tool.definition.DefaultToolDefinition
import org.springframework.ai.tool.definition.ToolDefinition

inline fun <R> withAIFlowGuard(
    spec: ChatClient.ChatClientRequestSpec,
    block: ChatClient.ChatClientRequestSpec.() -> R
): R {
    val callback = AIFlowGuardToolCallback()
    spec.toolCallbacks(callback)

    val rs = block(spec)
    callback.check()
    return rs
}

class AIFlowGuardException(message: String) : RuntimeException(message)
class AIFlowGuardToolCallback : ToolCallback {
    private val inputSchema = """
        {"type": "object","properties": {"msg": {"title": "错误原因","type": "string"}},"required": ["msg"]}
    """.trimIndent()

    var error: String? = null

    override fun getToolDefinition(): ToolDefinition {
        return DefaultToolDefinition.builder()
            .name("report_failed")
            .inputSchema(inputSchema)
            .description("报告任务失败")
            .build()
    }

    override fun call(toolInput: String): String {
        error = toolInput
        return "报告成功，你可以直接中断当前对话了"
    }

    @Suppress("UNCHECKED_CAST")
    fun check() {
        error?.let {
            try {
                val obj = ObjectMapper().readValue(it, Map::class.java) as Map<String, String>
                throw AIFlowGuardException(obj["msg"] ?: it)
            } catch (e: Throwable) {
                throw AIFlowGuardException(it)
            }
        }
    }
}