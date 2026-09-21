package moe.lolosia.web.manager

import moe.lolosia.web.annotation.Service
import moe.lolosia.web.util.session.Context
import moe.lolosia.web.util.sse.EventSource
import java.util.*

/**
 * 事件总线 SSE 连接管理器。
 * 维护所有活跃的 SSE 连接（Meta = ctx + EventSource），业务可向任意连接推送事件。
 *
 * @author 洛洛希雅Lolosia
 * @since 2024-12-02 09:51
 */
@Service
class EventBusSSEManager {

    class Meta(var ctx: Context, val client: EventSource)

    private val map = mutableMapOf<UUID, Meta>()

    operator fun get(sseId: UUID): Meta? = map[sseId]

    operator fun set(sseId: UUID, meta: Meta) {
        map[sseId] = meta
    }

    fun remove(sseId: UUID) {
        map.remove(sseId)
    }
}
