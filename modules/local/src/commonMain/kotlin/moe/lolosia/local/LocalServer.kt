package moe.lolosia.local

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * 本地服务主体：从传输层收帧、交给 [LocalDispatcher]、把响应写回。
 *
 * 每个请求独立协程处理，接收循环不会被慢请求阻塞——这一点在 `--embed`
 * 下尤其重要：服务反向请求 UI 时双方都需要继续收帧。
 */
class LocalServer(
    private val dispatcher: LocalDispatcher,
    private val transport: LocalTransport,
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var receiveJob: Job? = null

    /** 启动接收循环；重复调用无副作用。 */
    fun start() {
        if (receiveJob != null) return
        receiveJob = scope.launch {
            while (true) {
                val frame = transport.receive() ?: break
                launch {
                    val result = runCatching { dispatcher.dispatch(frame, transport.authorized) }
                        .getOrElse { return@launch }
                    result.responses.forEach { response ->
                        runCatching { transport.send(response) }
                    }
                }
            }
        }
    }

    /** 停止接收循环并关闭传输；重复调用无副作用。 */
    fun stop() {
        transport.close()
        scope.cancel()
    }
}
