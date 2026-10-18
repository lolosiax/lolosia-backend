package moe.lolosia.web.util.llm

/**
 * 滑动窗口记忆：在委托之上只保留最近 [maxMessages] 条（读取时裁剪，不删底层数据）。
 *
 * 窗口起点若落在 `tool` 结果上，会向前扩到承载它的 `assistant(tool_calls)` 之前，
 * 避免把「工具调用请求」与其结果拆散导致协议非法。
 */
class WindowChatMemory(
    private val delegate: ChatMemory,
    private val maxMessages: Int = 20,
) : ChatMemory {

    override suspend fun load(conversationId: String): List<ChatMessage> {
        val all = delegate.load(conversationId)
        if (maxMessages <= 0 || all.size <= maxMessages) return all
        var start = all.size - maxMessages
        while (start > 0 && all[start] is ChatMessage.ToolResult) start--
        return all.subList(start, all.size).toList()
    }

    override suspend fun append(conversationId: String, messages: List<ChatMessage>) =
        delegate.append(conversationId, messages)
}
