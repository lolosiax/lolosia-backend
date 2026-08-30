package moe.lolosia.web.manager

import moe.lolosia.web.annotation.Service
import moe.lolosia.web.util.sse.EventSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import java.util.*

/**
 * 管理 SSE 事件源。
 *
 * @author 洛洛希雅Lolosia
 * @since 2024-04-25 10:01
 * */
@Service
class EventSourceManager : CoroutineScope {
    override val coroutineContext get() = Dispatchers.Default

    private val eventSources = mutableMapOf<UUID, EventSource>()

    fun createEventSourceId(): UUID = UUID.randomUUID()

    fun setEventSource(id: UUID, source: EventSource) {
        eventSources[id] = source
    }

    fun getEventSource(id: UUID): EventSource? = eventSources[id]

    fun removeEventSource(id: UUID) {
        eventSources.remove(id)
    }
}
