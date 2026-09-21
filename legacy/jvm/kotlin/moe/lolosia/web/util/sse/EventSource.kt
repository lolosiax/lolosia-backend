package moe.lolosia.web.util.sse

import moe.lolosia.web.util.event.Event
import moe.lolosia.web.util.event.IEventHandle
import java.util.*

/**
 * SSE 事件源。
 * 业务代码通过 [send] 推送事件，中间件消费事件并写为 text/event-stream。
 *
 * @author 洛洛希雅Lolosia
 * @since 2024-04-25 10:01
 */
interface EventSource {

    val sseId: UUID

    val isClosed: Boolean

    fun send(data: Any? = null) = send("message", data)
    fun send(event: String = "message", data: Any? = null) = send(event, data, 10000)
    fun send(event: String = "message", data: Any? = null, retry: Int = 10000)
    fun close()

    val onClose: IEventHandle<Event>

    suspend fun await()
}
