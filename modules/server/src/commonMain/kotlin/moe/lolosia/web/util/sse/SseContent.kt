package moe.lolosia.web.util.sse

import io.ktor.http.*
import io.ktor.http.content.*
import io.ktor.utils.io.*

/**
 * 把 [KtorEventSource] 的帧队列写成 `text/event-stream` 的响应体。
 *
 * 单写者：帧与心跳都由本协程按序写出（无需加锁）。心跳是 SSE 注释帧 —— `EventSource`
 * 会忽略注释，作用只是让连接在空闲时也有字节流动，避免被中间设备掐掉。
 *
 * 客户端断开时写失败 → 消费循环结束 → `finally` 里 [EventSource.close]，连接随之注销。
 *
 * @author 洛洛希雅Lolosia
 */
class SseContent(private val source: KtorEventSource) : OutgoingContent.WriteChannelContent() {

    override val contentType: ContentType = ContentType.Text.EventStream

    override val headers: Headers = headersOf(HttpHeaders.CacheControl, "no-cache")

    /** 帧序号（`id:` 字段，供客户端断线重连时定位）；只有本协程会改，故不需要同步。 */
    private var frameId: Int = 0

    override suspend fun writeTo(channel: ByteWriteChannel) {
        try {
            source.consume(
                heartbeatIntervalMs = HEARTBEAT_INTERVAL_MS,
                onHeartbeat = { write(channel, HEARTBEAT_FRAME) },
            ) { frame ->
                frameId += 1
                write(channel, "id: $frameId\nevent: ${frame.event}\ndata: ${frame.data}\nretry: ${frame.retry}\n\n")
            }
        } catch (_: Throwable) {
            // 客户端断开、写超时等：静默收尾，连接由 finally 统一关闭
        } finally {
            source.close()
        }
    }

    private suspend fun write(channel: ByteWriteChannel, text: String) {
        channel.writeFully(text.encodeToByteArray())
        // 必须 flush：writeFully 只写缓冲，不 flush 数据到不了 socket（表现为响应挂起、0 字节）
        channel.flush()
    }

    companion object {
        /** 心跳间隔：空闲这么久就发一个注释帧。 */
        const val HEARTBEAT_INTERVAL_MS: Long = 5_000L

        /** SSE 注释帧（以 `:` 开头，客户端忽略）。 */
        private const val HEARTBEAT_FRAME: String = ": heartbeat\n\n"
    }
}
