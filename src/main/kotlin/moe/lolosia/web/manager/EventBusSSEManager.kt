package moe.lolosia.web.manager

import moe.lolosia.web.util.ebean.toUuid
import moe.lolosia.web.util.session.SSEContext
import moe.lolosia.web.util.spring.EventSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.springframework.stereotype.Service
import java.util.*

@Service
class EventBusSSEManager : CoroutineScope {

    override val coroutineContext get() = Dispatchers.Default

    // sseId -> controller
    private val mClients = mutableMapOf<UUID, Meta>()


    /**
     * 记录了所有的Socket连接信息，键：SessionId
     */
    val clients: Map<UUID, Meta> get() = mClients.toMap()

    operator fun get(sseId: UUID): Meta? {
        return mClients[sseId]
    }

    fun getUserSessions(userId: UUID?): Map<UUID, Meta> {
        synchronized(mClients) {
            return mClients.filterValues { it.ctx.userIdOrNull == userId }
        }
    }

    fun getUserSessions(userId: String?): Map<UUID, Meta> {
        return getUserSessions(userId?.toUuid())
    }

    fun set(meta: Meta) {
        synchronized(mClients) {
            mClients[meta.client.sseId] = meta
        }
    }

    operator fun set(sseId: UUID, meta: Meta) {
        synchronized(mClients) {
            mClients[sseId] = meta
        }
    }

    fun remove(sseId: UUID) {
        synchronized(mClients) {
            mClients.remove(sseId)
        }
    }

    fun remove(client: EventSource) {
        synchronized(mClients){
            mClients.remove(client.sseId)
        }
    }

    val size get() = mClients.size

    data class Meta(
        val ctx: SSEContext,
        val client: EventSource
    )
}