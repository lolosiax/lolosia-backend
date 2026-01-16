package top.lolosia.web.util.spring

import top.lolosia.web.util.event.Event
import top.lolosia.web.util.event.IEventHandle
import java.util.*
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

interface EventSource {

    val sseId: UUID

    val isClosed: Boolean
    
    fun send(data: Any? = null) = send("message", data)
    fun send(event: String = "message", data: Any? = null) = send(event, data, 10000)
    fun send(event: String = "message", data: Any? = null, retry: Int = 10000)
    fun close()

    val onClose: IEventHandle<Event>

    suspend fun await() = suspendCoroutine {
        onClose += { _ ->
            it.resume(Unit)
        }
    }
}