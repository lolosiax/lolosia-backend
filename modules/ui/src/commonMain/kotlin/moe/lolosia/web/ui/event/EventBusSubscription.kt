package moe.lolosia.web.ui.event

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import moe.lolosia.web.ui.session.SettingsStore
import kotlin.concurrent.Volatile
import kotlin.math.min

/**
 * EventBus 订阅的跨平台生命周期：读设置 → 建连 → 收事件 → 断线退避重连。
 *
 * 平台侧只提供三样东西：
 * - [SettingsStore]：服务端地址与登录 token；
 * - [EventStreamTransport]：本平台的 HTTP 实现；
 * - 两个回调：连接状态（用于前台通知）与推送内容（用于通知栏/横幅）。
 *
 * token 每次重连都重新读取，因此登录/登出后不需要额外通知订阅层；未登录时安静等待。
 *
 * @author 洛洛希雅Lolosia
 */
class EventBusSubscription(
    private val settings: SettingsStore,
    private val transportFactory: () -> EventStreamTransport,
    private val onStatus: (String) -> Unit,
    private val onMessage: (ServerPushMessage) -> Unit,
    private val onLog: (String) -> Unit = {},
    private val waitLoginMs: Long = 10_000L,
    private val initialBackoffMs: Long = 1_000L,
    private val maxBackoffMs: Long = 30_000L,
) {

    @Volatile
    private var activeTransport: EventStreamTransport? = null

    /** 供平台生命周期调用：立即断开当前连接，让阻塞读退出。 */
    fun close() {
        activeTransport?.close()
    }

    /** 阻塞运行直到调用方取消协程（Android 是 Service 销毁，桌面是应用退出）。 */
    suspend fun run() {
        var attempt = 0
        while (currentCoroutineContext().isActive) {
            val current = settings.load()
            val token = current.token
            if (token.isNullOrBlank()) {
                onStatus("等待登录…")
                delay(waitLoginMs)
                continue
            }

            val transport = transportFactory()
            activeTransport = transport
            try {
                onStatus("正在连接 ${current.baseUrl}")
                EventBusClient(transport).run(
                    baseUrl = current.baseUrl,
                    token = token,
                    onConnected = { sseId ->
                        attempt = 0
                        onStatus("已连接 · 等待服务器事件")
                        onLog("SSE 已连接：$sseId")
                    },
                    onEvent = { event, data ->
                        onLog("收到事件 $event：${data.take(200)}")
                        ServerEventFormatter.format(event, data)?.let(onMessage)
                    },
                )
                onLog("SSE 连接结束（服务端关闭）")
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                onStatus("连接断开，准备重试…")
                onLog("SSE 连接失败：${error.message}")
            } finally {
                activeTransport = null
                transport.close()
            }

            delay(backoffMillis(attempt))
            attempt += 1
        }
    }

    private fun backoffMillis(attempt: Int): Long =
        min(maxBackoffMs, initialBackoffMs shl min(attempt, 5))
}
