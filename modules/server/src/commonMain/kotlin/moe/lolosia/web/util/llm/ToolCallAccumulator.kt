package moe.lolosia.web.util.llm

/**
 * 把流式 `tool_calls` 碎片按 `index` 拼成完整调用：
 * `id` / `name` 只在首片出现，`arguments` 需要逐片字符串拼接。
 */
internal class ToolCallAccumulator {

    private val ids = mutableMapOf<Int, String>()
    private val names = mutableMapOf<Int, String>()
    private val arguments = mutableMapOf<Int, StringBuilder>()

    fun accept(delta: ChatCompletionChunk.ToolCallDelta) {
        delta.id?.let { ids[delta.index] = it }
        delta.function?.name?.takeIf { it.isNotEmpty() }?.let { names[delta.index] = it }
        delta.function?.arguments?.let { arguments.getOrPut(delta.index) { StringBuilder() }.append(it) }
    }

    fun build(): List<ToolCall> = names.keys.sorted().map { index ->
        ToolCall(
            id = ids[index] ?: "call_$index",
            name = names.getValue(index),
            arguments = arguments[index]?.toString().orEmpty().ifBlank { "{}" },
        )
    }
}
