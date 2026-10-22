package moe.lolosia.web.ui.client

import kotlinx.coroutines.*
import moe.lolosia.web.ui.event.EventBusSubscription
import moe.lolosia.web.ui.event.EventStreamTransport
import moe.lolosia.web.ui.event.ServerPushMessage
import moe.lolosia.web.ui.session.SettingsStore

/**
 * 服务进程内的事件总线模块：随服务启动，随服务停止。
 *
 * 平台实现只提供设置来源、传输实现与消息落地方式；连接、退避重连与事件解码沿用
 * [EventBusSubscription]。事件总线不再作为独立服务或独立进程运行。
 *
 * @author 洛洛希雅Lolosia
 */
class EventBusRuntime(
    private val settings: SettingsStore,
    private val transportFactory: () -> EventStreamTransport,
    private val onMessage: (ServerPushMessage) -> Unit,
    private val onStatus: (String) -> Unit = {},
    private val onLog: (String) -> Unit = {},
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var subscription: EventBusSubscription? = null
    private var job: Job? = null

    /** 启动订阅协程；重复调用无副作用。 */
    fun start() {
        if (job != null) return
        val subscription = EventBusSubscription(
            settings = settings,
            transportFactory = transportFactory,
            onStatus = onStatus,
            onMessage = onMessage,
            onLog = onLog,
        )
        this.subscription = subscription
        job = scope.launch { subscription.run() }
    }

    /** 断开 SSE 并取消订阅协程；重复调用无副作用。 */
    fun stop() {
        subscription?.close()
        job?.cancel()
        scope.cancel()
        subscription = null
        job = null
    }
}
