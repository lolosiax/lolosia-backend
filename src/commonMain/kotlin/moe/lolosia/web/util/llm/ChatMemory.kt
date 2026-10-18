package moe.lolosia.web.util.llm

/**
 * 会话记忆：按会话 id 读写历史消息。
 *
 * 系统提示词不在此存取（每次请求现给）。实现由业务侧决定（内存 / 数据库）。
 */
interface ChatMemory {

    /** 读取会话历史。 */
    suspend fun load(conversationId: String): List<ChatMessage>

    /**
     * 追加一段消息。整轮对话**结束或报错中断**时由 [ChatRequestSpec] 调用一次。
     */
    suspend fun append(conversationId: String, messages: List<ChatMessage>)
}
