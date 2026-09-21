package moe.lolosia.web.util.sse

import kotlinx.coroutines.suspendCancellableCoroutine
import moe.lolosia.web.dto.response.toJsonElement
import moe.lolosia.web.util.event.Event
import moe.lolosia.web.util.event.EventHandle
import moe.lolosia.web.util.event.IEventRunnable
import kotlinx.coroutines.channels.Channel
import java.util.*
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.resume

/**
 * Ktor 版 EventSource 实现：基于 Channel 队列，业务协程与写响应协程解耦。
 *
 * @author 洛洛希雅Lolosia
 * @since 2026-08-30 15:30
 */
class KtorEventSource(override val sseId: UUID) : EventSource {

    data class Frame(val id: Int, val event: String, val data: String, val retry: Int)

    private val channel = Channel<Frame>(capacity = 64)
    private val counter = AtomicInteger(0)
    override val onClose = EventHandle<Event>()

    @Volatile
    private var closed = false

    override val isClosed get() = closed

    fun sendFrame(event: String, data: String, retry: Int) {
        if (closed) throw IllegalStateException("EventSource has been closed!")
        val frame = Frame(counter.incrementAndGet(), event, data, retry)
        channel.trySend(frame)
    }

    override fun send(event: String, data: Any?, retry: Int) {
        sendFrame(event, data.toJsonElement().toString(), retry)
    }

    override fun close() {
        if (closed) return
        try {
            sendFrame("close", "null", 10000)
        } catch (_: Throwable) {
        }
        channel.close()
        closed = true
        onClose fire Event(this)
    }

    override suspend fun await() {
        suspendCancellableCoroutine<Unit> { cont ->
            val handle = IEventRunnable<Event> { cont.resume(Unit) }
            onClose += handle
            cont.invokeOnCancellation { onClose -= handle }
        }
    }

    /** 挂起消费所有帧，直到 channel 关闭 */
    suspend fun consume(block: suspend (Frame) -> Unit) {
        for (frame in channel) block(frame)
    }
}
