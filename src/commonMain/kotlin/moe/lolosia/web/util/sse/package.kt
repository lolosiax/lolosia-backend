package moe.lolosia.web.util.sse

import io.ktor.util.AttributeKey

/**
 * 当前请求的 SSE 事件源在 `call.attributes` 里的键。
 *
 * 建流的那个 `GET /api/sse` 请求把 [KtorEventSource] 放进来，`SseInterceptor` 的发送管道
 * 据此把响应体换成流（见 [SseContent]）。
 *
 * @author 洛洛希雅Lolosia
 */
public val SseSourceKey: AttributeKey<KtorEventSource> = AttributeKey("sse-source")

/** 握手用的自定义请求头：前端 `POST /api/sse` 带上它换连接标识。 */
public const val SSE_UPGRADE_HEADER: String = "x-upgrade"

/** [SSE_UPGRADE_HEADER] 的取值。 */
public const val SSE_UPGRADE_VALUE: String = "event-source"

/** 建流时携带连接标识的查询参数名。 */
public const val SSE_ID_PARAM: String = "eventSourceId"
