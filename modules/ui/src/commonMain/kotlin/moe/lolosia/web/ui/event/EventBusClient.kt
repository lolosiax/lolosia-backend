package moe.lolosia.web.ui.event

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** EventBus 协议错误（握手/绑定/建流失败）。 */
class EventBusException(message: String) : Exception(message)

/**
 * 服务端 EventBus 的跨平台客户端（SSE 协议在这里，HTTP 在 [EventStreamTransport]）。
 *
 * 与服务端 `SseController` / `SseService` 对齐，分三步：
 * 1. `POST /api/sse` + 头 `x-upgrade: event-source` → 拿到连接标识；
 * 2. `POST /api/sse/registry` + Bearer token → 把连接绑到当前用户（之后才会收到定向推送）；
 * 3. `GET /api/sse?eventSourceId=<id>` → 读 `text/event-stream`。
 *
 * 浏览器原生 `EventSource` 带不了自定义头，因此建流请求是匿名的；登录态只能靠第 2 步补绑。
 *
 * @author 洛洛希雅Lolosia
 */
class EventBusClient(private val transport: EventStreamTransport) {

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * 建立连接并阻塞读取事件，直到连接断开或 [EventStreamTransport.close] 被调用。
     *
     * @param onConnected 绑定成功后回调一次，参数是本次连接标识。
     * @param onEvent 每收到一帧事件回调 `(event, data)`；`data` 是服务端的 JSON 文本。
     */
    suspend fun run(
        baseUrl: String,
        token: String,
        onConnected: (String) -> Unit,
        onEvent: (String, String) -> Unit,
    ) {
        val sseId = handshake(baseUrl)
        registry(baseUrl, token, sseId)
        onConnected(sseId)
        stream(baseUrl, sseId, onEvent)
    }

    private suspend fun handshake(baseUrl: String): String {
        val response = transport.postJson(
            baseUrl = baseUrl,
            path = "/api/sse",
            token = null,
            body = "{}",
            headers = mapOf("x-upgrade" to "event-source"),
        )
        val id = runCatching {
            json.parseToJsonElement(response.body).jsonObject["data"]?.jsonPrimitive?.contentOrNull
        }.getOrNull()
        if (id.isNullOrBlank()) {
            throw EventBusException("SSE 握手响应缺少 data：${response.body.take(160)}")
        }
        return id
    }

    private suspend fun registry(baseUrl: String, token: String, sseId: String) {
        val response = transport.postJson(
            baseUrl = baseUrl,
            path = "/api/sse/registry",
            token = token,
            body = """{"sseId":"$sseId"}""",
        )
        val code = runCatching {
            json.parseToJsonElement(response.body).jsonObject["code"]?.jsonPrimitive?.intOrNull
        }.getOrNull()
        if (response.status !in 200..299 || code != 200) {
            throw EventBusException("SSE 绑定失败：HTTP ${response.status} / code=$code")
        }
    }

    private suspend fun stream(baseUrl: String, sseId: String, onEvent: (String, String) -> Unit) {
        var event = "message"
        val data = StringBuilder()
        transport.openEventStream(
            baseUrl = baseUrl,
            path = "/api/sse?eventSourceId=$sseId",
            headers = mapOf(
                "Accept" to "text/event-stream",
                "Cache-Control" to "no-cache",
            ),
        ) { line ->
            when {
                line.isEmpty() -> {
                    if (data.isNotEmpty()) onEvent(event, data.toString())
                    event = "message"
                    data.setLength(0)
                }
                // 以 `:` 开头的是注释帧（服务端心跳），忽略
                line.startsWith(":") -> Unit
                line.startsWith("event:") -> event = line.substring(6).trim()
                line.startsWith("data:") -> {
                    if (data.isNotEmpty()) data.append('\n')
                    data.append(line.substring(5).trimStart())
                }
                else -> Unit
            }
        }
    }
}
