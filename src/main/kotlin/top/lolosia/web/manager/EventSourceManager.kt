package top.lolosia.web.manager

import top.lolosia.web.util.spring.EventSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.springframework.core.io.buffer.DataBufferUtils
import org.springframework.http.server.reactive.ServerHttpRequest
import org.springframework.stereotype.Service
import java.io.ByteArrayOutputStream
import java.util.*
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine
import kotlin.time.Duration.Companion.seconds

@Service
class EventSourceManager {

    private val requestCache = mutableMapOf<UUID, Pair<ServerHttpRequest, ByteArray>>()
    private val eventSources = mutableMapOf<UUID, EventSource>()
    suspend fun createEventSource(request: ServerHttpRequest): UUID {
        val id = UUID.randomUUID()
        val body: ByteArray = suspendCoroutine {
            val bos = ByteArrayOutputStream()
            request.body.subscribe({
                it.asInputStream().transferTo(bos)
                DataBufferUtils.release(it)
            }, { e ->
                it.resumeWithException(e)
            }, {
                it.resume(bos.toByteArray())
            })
        }
        requestCache[id] = request to body
        CoroutineScope(Dispatchers.Default).launch {
            delay(10.seconds)
            requestCache.remove(id)
        }
        return id
    }

    fun getRequest(id: UUID): Pair<ServerHttpRequest, ByteArray>? {
        return requestCache.remove(id)
    }

    fun setEventSource(id: UUID, source: EventSource) {
        eventSources[id] = source
    }

    fun getEventSource(id: UUID): EventSource? {
        return eventSources[id]
    }

    fun removeEventSource(id: UUID) {
        eventSources.remove(id)
    }
}