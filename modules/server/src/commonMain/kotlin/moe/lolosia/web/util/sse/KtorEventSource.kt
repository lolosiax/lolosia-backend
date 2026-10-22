package moe.lolosia.web.util.sse

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

/** 帧数据的 JSON 编码共用一套 Json（紧凑输出，与前端 `JSON.parse(e.data)` 的假设一致）。 */
private val json = Json

/**
 * 基于 Channel 队列的 [EventSource] 实现：业务协程只管投帧，写响应的协程按序消费，
 * 两者只通过队列解耦 —— 写通道因此永远只有一个写者，不需要加锁。
 *
 * @param sseId 握手阶段分配的连接标识
 * @author 洛洛希雅Lolosia
 */
class KtorEventSource(override val sseId: String) : EventSource {

    /** 一帧 SSE 数据（`data` 已是 JSON 文本）。 */
    data class Frame(val event: String, val data: String, val retry: Int)

    private val channel = Channel<Frame>(capacity = QUEUE_CAPACITY)
    private val closedSignal = CompletableDeferred<Unit>()

    override val isClosed: Boolean get() = closedSignal.isCompleted

    override fun send(event: String, data: JsonElement?, retry: Int) {
        val text = data?.let { json.encodeToString(JsonElement.serializer(), it) } ?: "null"
        channel.trySend(Frame(event, text, retry))
    }

    override fun close() {
        // 两者都幂等：重复调用或与消费方并发调用都安全
        closedSignal.complete(Unit)
        channel.close()
    }

    override suspend fun await(): Unit = closedSignal.await()

    /**
     * 按序消费队列：[block] 处理每帧；队列空转满 [heartbeatIntervalMs] 毫秒调一次 [onHeartbeat]
     * （用于发保活注释帧），队列关闭且排空后返回。
     *
     * @param onHeartbeat 心跳回调（写注释帧，避免中间设备掐掉空闲连接）
     * @param block 帧处理器；抛异常即结束消费（由调用方收尾）
     */
    suspend fun consume(
        heartbeatIntervalMs: Long,
        onHeartbeat: suspend () -> Unit,
        block: suspend (Frame) -> Unit,
    ) {
        while (true) {
            val frame = try {
                withTimeoutOrNull(heartbeatIntervalMs) { channel.receive() }
            } catch (_: ClosedReceiveChannelException) {
                // 关闭且已排空（关闭时队列里剩下的帧会先被 receive 取走）
                return
            }
            if (frame == null) onHeartbeat() else block(frame)
        }
    }

    companion object {
        /** 帧队列容量：满了就丢帧，不反压业务协程。 */
        private const val QUEUE_CAPACITY: Int = 64
    }
}
