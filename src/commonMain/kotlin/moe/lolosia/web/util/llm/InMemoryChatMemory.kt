package moe.lolosia.web.util.llm

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 进程内记忆。
 *
 * Kotlin/Native 上共享可变状态必须串行访问（同 `SseService` / `SessionManager` 的做法），
 * 故用 [Mutex] 保护。
 */
class InMemoryChatMemory : ChatMemory {

    private val mutex = Mutex()
    private val store = mutableMapOf<String, MutableList<ChatMessage>>()

    override suspend fun load(conversationId: String): List<ChatMessage> =
        mutex.withLock { store[conversationId]?.toList() ?: emptyList() }

    override suspend fun append(conversationId: String, messages: List<ChatMessage>) {
        if (messages.isEmpty()) return
        mutex.withLock { store.getOrPut(conversationId) { mutableListOf() } += messages }
    }
}
