package moe.lolosia.web.interceptor

import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.call
import io.ktor.server.response.ApplicationSendPipeline
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.writeFully
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import moe.lolosia.web.manager.EventSourceManager
import moe.lolosia.web.util.bundle.bundleScope
import moe.lolosia.web.util.ebean.toUuid
import moe.lolosia.web.util.sse.EventSource
import moe.lolosia.web.util.sse.KtorEventSource
import org.koin.core.context.GlobalContext
import org.koin.core.qualifier.named

/**
 * SSE 中间件（特殊协议）：
 *
 * 1. 请求头 `x-upgrade: event-source` → 创建会话ID，返回 `{data: <id>, code: 200}`，不进入路由。
 * 2. 请求参数 `eventSourceId=<id>` → 创建 EventSource，放入 attributes，进入路由执行业务；
 *    路由的响应会被发送管道替换为 text/event-stream 流（直到 EventSource close）。
 *
 * 帧格式：`id: N\nevent: name\ndata: json\nretry: N\n\n`，客户端断开时自动 close。
 *
 * @author 洛洛希雅Lolosia
 * @since 2024-04-25 10:01
 */
fun Application.installSseInterceptor() {
    val eventSourceManager = GlobalContext.get().get<EventSourceManager>()

    // 发送管道：若请求带有 SSE source，将路由的普通响应替换为 SSE 流
    sendPipeline.intercept(ApplicationSendPipeline.After) { _ ->
        val source = call.attributes.getOrNull(SseSourceKey) as? KtorEventSource
        if (source != null) proceedWith(SseContent(source)) else proceed()
    }

    intercept(ApplicationCallPipeline.Call) {
        val request = call.request
        when {
            request.headers["x-upgrade"] == "event-source" -> {
                // 握手响应保持 {data, code} 原样（前端直接读 body.data 作 EventSourceId），跳过信封中间件
                call.attributes.put(EnvelopeSkipKey, Unit)
                val id = eventSourceManager.createEventSourceId()
                call.respond(HttpStatusCode.OK, bundleScope {
                    "data" set id.toString()
                    "code" set 200
                })
                finish()
            }

            request.queryParameters["eventSourceId"] != null -> {
                val id = request.queryParameters["eventSourceId"]!!.toUuid()
                val source = KtorEventSource(id)
                call.attributes.put(SseSourceKey, source)
                eventSourceManager.setEventSource(id, source)
                call.response.header(HttpHeaders.ContentType, ContentType.Text.EventStream.toString())
                call.response.header(HttpHeaders.CacheControl, "no-cache")
                call.response.header(HttpHeaders.Connection, "keep-alive")
                proceed()
            }

            else -> proceed()
        }
    }
}

/** SSE EventSource 在 call.attributes 中的键（用接口类型，控制器 @Attribute("sse") 才能取到） */
val SseSourceKey = io.ktor.util.AttributeKey<EventSource>("sse")

/** 把 EventSource 的帧队列写为 text/event-stream 流 */
class SseContent(private val source: KtorEventSource) : OutgoingContent.WriteChannelContent() {
    override val contentType: ContentType = ContentType.Text.EventStream

    override suspend fun writeTo(channel: ByteWriteChannel) {
        try {
            coroutineScope {
                // 心跳：连接存活期间每 5 秒发一个 SSE 注释帧（EventSource 忽略注释，仅保活）
                val heartbeat = launch {
                    try {
                        while (isActive) {
                            delay(5_000)
                            if (channel.isClosedForWrite) break
                            channel.writeFully(": heartbeat\n\n".toByteArray())
                            channel.flush()
                        }
                    } catch (_: Throwable) {
                        // 连接已断开等
                    }
                }
                try {
                    source.consume { frame ->
                        channel.writeFully(
                            "id: ${frame.id}\nevent: ${frame.event}\ndata: ${frame.data}\nretry: ${frame.retry}\n\n".toByteArray()
                        )
                        // 必须 flush：writeFully 只写入缓冲，不 flush 则数据永远到不了 socket（表现为连接挂起、0 字节响应）
                        channel.flush()
                    }
                } finally {
                    heartbeat.cancel()
                }
            }
        } catch (_: Throwable) {
            // 客户端断开等
        } finally {
            source.close()
        }
    }
}
