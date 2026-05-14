package moe.lolosia.web.service

import moe.lolosia.web.api.SystemApi
import moe.lolosia.web.event.system.SystemEvent
import moe.lolosia.web.manager.EventBusSSEManager
import moe.lolosia.web.util.isClient
import moe.lolosia.web.util.session.Context
import moe.lolosia.web.util.session.SSEContext
import moe.lolosia.web.util.spring.EventSource
import moe.lolosia.web.util.success
import kotlinx.coroutines.Job
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.ApplicationEventPublisher
import org.springframework.http.ResponseEntity
import org.springframework.stereotype.Service
import java.util.*

@Service
class EventBusSSEService {

    private val logger = LoggerFactory.getLogger(EventBusSSEService::class.java)

    @Autowired
    lateinit var manager: EventBusSSEManager

    @Autowired
    lateinit var eventBus: ApplicationEventPublisher

    /**
     * 有用户发起SSE连接
     */
    suspend fun connect(ctx: SSEContext, source: EventSource) {
        var job: Job? = null

        // TODO: SSE在客户端现在仍然是一个不可用状态。
        // if (isClient) {
        //     val result = SystemApi.sse()
        //     // 连接到服务端转发SSE消息
        //     job = CoroutineScope(Dispatchers.IO).launch {
        //         ctx.parentSse = result
        //         result.flow.collect {
        //             logger.info(it.toString())
        //             source.send(it.first, it.second)
        //         }
        //         source.close()
        //         logger.info("SSE disconnected: ${result.id}")
        //     }
        // }
        val meta = EventBusSSEManager.Meta(ctx, source)
        manager[source.sseId] = meta
        source.send("status", "connect successful")
        eventBus.publishEvent(SSEConnectedEvent(this, meta))
        // 等待 EventSource 关闭
        source.await()
        job?.cancel()
        manager.remove(source.sseId)
        eventBus.publishEvent(SSEDisconnectedEvent(this, meta))
    }

    /**
     * 用户登录后将已建立连接的SSE 链接更新用户信息
     */
    suspend fun registerUser(ctx: Context, sseId: UUID): ResponseEntity<ByteArray> {
        manager[sseId]?.let {
            it.ctx.sessionId = ctx.sessionId

            if (isClient) {
                // 服务器下发的SSE-ID并不一致
                SystemApi.registerSseUser(ctx, it.client.sseId)
            }
        }
        return success()
    }

    sealed class SSEConnectEvent(source: Any, val meta: EventBusSSEManager.Meta) : SystemEvent(source) {
        val session get() = meta.ctx
        val client get() = meta.client
    }

    class SSEConnectedEvent(source: Any, meta: EventBusSSEManager.Meta) : SSEConnectEvent(source, meta)
    class SSEDisconnectedEvent(source: Any, meta: EventBusSSEManager.Meta) : SSEConnectEvent(source, meta)
}