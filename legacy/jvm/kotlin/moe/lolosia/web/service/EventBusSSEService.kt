package moe.lolosia.web.service

import moe.lolosia.web.event.system.SystemEvent
import moe.lolosia.web.manager.EventBusSSEManager
import moe.lolosia.web.annotation.Service
import moe.lolosia.web.util.bundle.Bundle
import moe.lolosia.web.util.event.EventBus
import moe.lolosia.web.util.session.Context
import moe.lolosia.web.util.session.SSEContext
import moe.lolosia.web.util.sse.EventSource
import moe.lolosia.web.util.ebean.toUuid
import moe.lolosia.web.util.success
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.util.*

/**
 * 事件总线 SSE 服务。
 * 业务协程与写响应协程解耦：connect 注册连接并等待关闭，事件由其他协程通过 manager 推送。
 *
 * @author 洛洛希雅Lolosia
 * @since 2024-12-02 09:51
 */
@Service
class EventBusSSEService : KoinComponent {
    private val manager: EventBusSSEManager by inject()
    private val eventBus: EventBus by inject()

    /**
     * 有用户发起SSE连接（在独立协程中执行，响应体由中间件接管为流）
     */
    suspend fun connect(ctx: SSEContext, source: EventSource) {
        val meta = EventBusSSEManager.Meta(ctx, source)
        manager[source.sseId] = meta
        source.send("status", "connect successful")
        eventBus.publish(SSEConnectedEvent(this, meta))
        // 等待 EventSource 关闭
        source.await()
        manager.remove(source.sseId)
        eventBus.publish(SSEDisconnectedEvent(this, meta))
    }

    /**
     * 用户登录后将已建立连接的SSE链接更新用户信息
     */
    suspend fun registerUser(ctx: Context, sseId: UUID): Bundle {
        manager[sseId]?.let {
            val sseCtx = it.ctx as? SSEContext ?: return@let
            sseCtx.sessionId = ctx.sessionId
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
