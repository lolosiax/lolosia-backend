package moe.lolosia.web.api

import io.ktor.client.*
import io.ktor.client.plugins.websocket.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.http.content.*
import io.ktor.utils.io.*
import io.ktor.websocket.*
import kotlinx.coroutines.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.*
import kotlinx.serialization.serializer
import moe.lolosia.web.dto.ApiException
import moe.lolosia.web.dto.ApiResult
import moe.lolosia.web.util.log.Log
import moe.lolosia.web.util.log.Logger
import moe.lolosia.web.util.session.Context
import moe.lolosia.web.util.sse.EventSource
import kotlin.concurrent.Volatile
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

private val logger: Logger = Log.of("Api")

private val json = Json {
    ignoreUnknownKeys = true
    isLenient = true
    encodeDefaults = true
}

/** 请求默认超时。 */
val DEFAULT_TIMEOUT: Duration = 14.seconds

/** 从 [baseUrl] 到 [url] 的整体重试上限。 */
private const val MAX_RETRY: Int = 5

/** 流式读取的块大小。 */
private const val STREAM_BUFFER_SIZE: Int = 8192

/** 日志里单条载荷的最大长度。 */
private const val LOG_PAYLOAD_LIMIT: Int = 200

/** WS 心跳间隔。 */
private val WS_HEARTBEAT_INTERVAL: Duration = 5.seconds

/** WS 心跳载荷（二进制帧）。 */
private val WS_HEARTBEAT_PAYLOAD: ByteArray = ByteArray(0)

/**
 * 创建对外请求用的 HTTP 客户端。
 *
 * 引擎按目标平台选择（见各源集 actual 实现），所选引擎均自带 TLS 与 WebSocket 能力。
 */
internal expect fun createApiClient(): HttpClient

/** 全局共享的客户端实例。 */
internal val apiClient: HttpClient by lazy { createApiClient() }

/**
 * 向外部服务发起 POST 请求，并把响应体反序列化为 [T]。
 *
 * @param T 响应类型；为 `Flow<ByteArray>` 时改为按字节块流式返回，为 `Unit` 时只消费响应体
 * @param baseUrl 服务根地址
 * @param url 相对路径
 * @param body 请求体，`null` 表示不携带
 * @param rawResponse 为 `true` 时直接把响应体解析为 [T]，否则先按 [ApiResult] 解包取 `data`
 * @param ctx 请求上下文，用于取当前会话 id
 * @param sessionId 显式指定的会话 id，优先于 [ctx]
 * @param headers 额外请求头
 * @param timeout 请求超时
 * @param noLog 为 `true` 时不输出请求与响应日志
 */
suspend inline fun <reified T> post(
    baseUrl: String,
    url: String,
    body: JsonElement? = null,
    rawResponse: Boolean = false,
    ctx: Context? = null,
    sessionId: String? = null,
    headers: Map<String, String> = emptyMap(),
    timeout: Duration = DEFAULT_TIMEOUT,
    noLog: Boolean = false,
): T {
    val auth = sessionId ?: ctx?.sessionId
    return dispatch<T>(HttpMethod.Post, baseUrl, url, null, body, rawResponse, auth, headers, timeout, noLog)
}

/**
 * 向外部服务发起 PUT 请求，并把响应体反序列化为 [T]。
 *
 * @param T 响应类型；为 `Flow<ByteArray>` 时改为按字节块流式返回，为 `Unit` 时只消费响应体
 * @param baseUrl 服务根地址
 * @param url 相对路径
 * @param body 请求体，`null` 表示不携带
 * @param rawResponse 为 `true` 时直接把响应体解析为 [T]，否则先按 [ApiResult] 解包取 `data`
 * @param ctx 请求上下文，用于取当前会话 id
 * @param sessionId 显式指定的会话 id，优先于 [ctx]
 * @param headers 额外请求头
 * @param timeout 请求超时
 * @param noLog 为 `true` 时不输出请求与响应日志
 */
suspend inline fun <reified T> put(
    baseUrl: String,
    url: String,
    body: JsonElement? = null,
    rawResponse: Boolean = false,
    ctx: Context? = null,
    sessionId: String? = null,
    headers: Map<String, String> = emptyMap(),
    timeout: Duration = DEFAULT_TIMEOUT,
    noLog: Boolean = false,
): T {
    val auth = sessionId ?: ctx?.sessionId
    return dispatch<T>(HttpMethod.Put, baseUrl, url, null, body, rawResponse, auth, headers, timeout, noLog)
}

/**
 * 向外部服务发起 GET 请求，并把响应体反序列化为 [T]。
 *
 * @param T 响应类型；为 `Flow<ByteArray>` 时改为按字节块流式返回，为 `Unit` 时只消费响应体
 * @param baseUrl 服务根地址
 * @param url 相对路径
 * @param query 查询参数，同名多值按序追加
 * @param rawResponse 为 `true` 时直接把响应体解析为 [T]，否则先按 [ApiResult] 解包取 `data`
 * @param ctx 请求上下文，用于取当前会话 id
 * @param sessionId 显式指定的会话 id，优先于 [ctx]
 * @param headers 额外请求头
 * @param timeout 请求超时
 * @param noLog 为 `true` 时不输出请求与响应日志
 */
suspend inline fun <reified T> get(
    baseUrl: String,
    url: String,
    query: Map<String, List<String>>? = null,
    rawResponse: Boolean = false,
    ctx: Context? = null,
    sessionId: String? = null,
    headers: Map<String, String> = emptyMap(),
    timeout: Duration = DEFAULT_TIMEOUT,
    noLog: Boolean = false,
): T {
    val auth = sessionId ?: ctx?.sessionId
    return dispatch<T>(HttpMethod.Get, baseUrl, url, query, null, rawResponse, auth, headers, timeout, noLog)
}

/** 按 [T] 的形态分流：`Unit` 只消费响应体，`Flow` 走字节流，其余按 JSON 反序列化。 */
@PublishedApi
@Suppress("UNCHECKED_CAST")
internal suspend inline fun <reified T> dispatch(
    method: HttpMethod,
    baseUrl: String,
    url: String,
    query: Map<String, List<String>>?,
    body: JsonElement?,
    rawResponse: Boolean,
    sessionId: String?,
    headers: Map<String, String>,
    timeout: Duration,
    noLog: Boolean,
): T {
    if (T::class == Unit::class) {
        sendUnit(method, baseUrl, url, body, query, headers, sessionId, timeout, noLog)
        return Unit as T
    }
    if (T::class == Flow::class) {
        return bytes(method, baseUrl, url, body, query, headers, sessionId, noLog) as T
    }
    return sendTyped(method, baseUrl, url, body, query, rawResponse, headers, sessionId, timeout, noLog, serializer<T>())
}

/** 发出请求并只消费响应体，不解析。 */
@PublishedApi
internal suspend fun sendUnit(
    method: HttpMethod,
    baseUrl: String,
    url: String,
    body: JsonElement?,
    query: Map<String, List<String>>?,
    headers: Map<String, String>,
    sessionId: String?,
    timeout: Duration,
    noLog: Boolean,
) {
    val response = send(method, baseUrl, url, body, query, headers, sessionId)
    if (!noLog) beforeWebRequest(body, baseUrl, url)
    withTimeout(timeout) {
        if (!response.status.isSuccess()) respHandleException(response, baseUrl, url)
        val text = response.bodyAsText()
        if (!noLog) afterWebRequest(text, baseUrl, url)
    }
}

/** 发出请求并把响应体按 [ApiResult] 信封（或 [rawResponse] 时的原样）反序列化成 [T]。 */
@PublishedApi
internal suspend fun <T> sendTyped(
    method: HttpMethod,
    baseUrl: String,
    url: String,
    body: JsonElement?,
    query: Map<String, List<String>>?,
    rawResponse: Boolean,
    headers: Map<String, String>,
    sessionId: String?,
    timeout: Duration,
    noLog: Boolean,
    deserializer: KSerializer<T>,
): T {
    val response = send(method, baseUrl, url, body, query, headers, sessionId)
    if (!noLog) beforeWebRequest(body, baseUrl, url)
    return withTimeout(timeout) {
        if (!response.status.isSuccess()) respHandleException(response, baseUrl, url)
        val text = response.bodyAsText()
        if (!noLog) afterWebRequest(text, baseUrl, url)

        @Suppress("UNCHECKED_CAST")
        if (rawResponse) {
            json.decodeFromString(deserializer, text)
        } else {
            val envelope = json.decodeFromString(ApiResult.serializer(deserializer), text)
            if (envelope.code != 0 && envelope.code != 200) {
                throw ApiException(502, "${envelope.msg}, 错误代码: ${envelope.code}")
            }
            envelope.data as T
        }
    }
}

/** 发出请求并把响应体作为字节块流返回。 */
@PublishedApi
internal suspend fun bytes(
    method: HttpMethod,
    baseUrl: String,
    url: String,
    body: JsonElement?,
    query: Map<String, List<String>>?,
    headers: Map<String, String>,
    sessionId: String?,
    noLog: Boolean,
): Flow<ByteArray> {
    val response = send(method, baseUrl, url, body, query, headers, sessionId)
    if (!noLog) beforeWebRequest(body, baseUrl, url)
    if (!response.status.isSuccess()) respHandleException(response, baseUrl, url)
    return response.bodyAsChannel().asFlow()
        .onCompletion { if (!noLog) afterWebRequest(null, baseUrl, url) }
}

/** 组装并执行一次请求；`expectSuccess` 保持关闭，状态码由调用方判定。 */
private suspend fun send(
    method: HttpMethod,
    baseUrl: String,
    url: String,
    body: JsonElement?,
    query: Map<String, List<String>>?,
    headers: Map<String, String>,
    sessionId: String?,
): HttpResponse {
    val target = baseUrl + url
    return apiClient.request {
        this.method = method
        this.url(target)
        query?.forEach { (name, values) -> values.forEach { parameter(name, it) } }
        headers.forEach { (name, value) -> header(name, value) }
        sessionId?.let { header(HttpHeaders.Authorization, "Bearer $it") }
        if (body != null) {
            setBody(TextContent(body.toString(), ContentType.Application.Json))
        }
    }
}

/** 把响应体读成字节块流。 */
suspend fun HttpResponse.bodyAsFlow(): Flow<ByteArray> = bodyAsChannel().asFlow()

private fun ByteReadChannel.asFlow(): Flow<ByteArray> = flow {
    val buffer = ByteArray(STREAM_BUFFER_SIZE)
    while (true) {
        val read = readAvailable(buffer, 0, buffer.size)
        if (read <= 0) break
        emit(buffer.copyOf(read))
    }
}

/**
 * 向外部服务发起 POST，并把响应体里 `data` 字段的文本逐段推给 [event]。
 *
 * @param event 接收推送的事件源
 * @param baseUrl 服务根地址
 * @param url 相对路径
 * @param body 请求体，`null` 表示不携带
 * @param sessionId 会话 id，非空时作为 `Authorization: Bearer`
 * @param noLog 为 `true` 时不输出请求与响应日志
 * @return `data` 字段的完整文本
 */
suspend fun streamingRequest(
    event: EventSource,
    baseUrl: String,
    url: String,
    body: JsonElement? = null,
    sessionId: String? = null,
    noLog: Boolean = false,
): String {
    val text = sendTyped<JsonElement>(
        HttpMethod.Post, baseUrl, url, body, null, false,
        emptyMap(), sessionId, DEFAULT_TIMEOUT, noLog, JsonElement.serializer(),
    )
    val out = (text as? JsonPrimitive)?.contentOrNull ?: ""
    event.send("data", JsonPrimitive(out))
    return out
}

/**
 * 连接外部服务的 SSE 接口。
 *
 * 非 [raw] 模式走两段式握手：先 POST 同路径（带 `X-Upgrade: event-source`）换取
 * `eventSourceId`，再以 `?eventSourceId=` 发起 GET 订阅；[raw] 模式直接连接 `baseUrl + url`。
 *
 * @param baseUrl 服务根地址
 * @param url 相对路径
 * @param body 握手请求体，`null` 表示不携带
 * @param raw 为 `true` 时跳过握手，直接连接 [url]（需自带 query）
 * @param ctx 请求上下文，用于取当前会话 id
 * @param sessionId 显式指定的会话 id，优先于 [ctx]
 * @param noLog 为 `true` 时不输出请求与响应日志
 */
suspend fun sse(
    baseUrl: String,
    url: String,
    body: JsonElement? = null,
    raw: Boolean = false,
    ctx: Context? = null,
    sessionId: String? = null,
    noLog: Boolean = false,
): SSEResult {
    val auth = sessionId ?: ctx?.sessionId
    var id: String? = null
    if (!raw) {
        id = post<String>(
            baseUrl, url, body,
            ctx = ctx, sessionId = auth,
            headers = mapOf("X-Upgrade" to "event-source"),
            noLog = noLog,
        )
    }

    val target = if (raw) "$baseUrl$url" else "$baseUrl$url?eventSourceId=$id"
    val job = coroutineJob()
    val flow = channelFlow {
        if (!noLog) beforeWebRequest(body, baseUrl, url)
        val response = apiClient.request {
            method = HttpMethod.Get
            this.url(target)
            accept(ContentType.Text.EventStream)
            auth?.let { header(HttpHeaders.Authorization, "Bearer $it") }
        }
        if (!response.status.isSuccess()) respHandleException(response, baseUrl, url)

        try {
            response.bodyAsFlow()
                .toEventSourceFrames()
                .collect { frame ->
                    // 以冒号开头的行是注释，直接丢弃
                    if (frame.isEmpty() || frame[0] == ':'.code.toByte()) return@collect

                    var event = "message"
                    var data = "null"
                    frame.decodeToString().split("\n").forEach { line ->
                        when {
                            line.startsWith("event: ") -> event = line.substringAfter(' ')
                            line.startsWith("data: ") -> data = line.substringAfter(' ')
                        }
                    }

                    if (event == "close") {
                        if (!noLog) afterWebRequest(data, baseUrl, url)
                        throw CancellationException(data)
                    }
                    send(event to json.parseToJsonElement(data))
                }
        } catch (e: CancellationException) {
            // 正常结束（含远端 close）
            if (e.message != null) throw e
        } catch (e: Throwable) {
            catchWebRequestException(e, baseUrl, url)
            throw e
        }
    }.catch { e ->
        catchWebRequestException(e, baseUrl, url)
        throw e
    }

    return SSESession(flow, id, job)
}

/**
 * 连接外部服务的 WebSocket 接口。
 *
 * 与 [sse] 同构，仅第二段由「GET 订阅」换成「WS 建连」：非 [raw] 模式先 POST 同路径
 * （带 `X-Upgrade: websocket`）换取 `websocketId`，再以 `?websocketId=` 建连。
 *
 * 帧格式固定为一帧一事件：`{"event": "<name>", "data": {...}}`，收发同构；
 * 收到 `close` 事件即结束下行流，该事件不透传给调用方。
 *
 * @param baseUrl 服务根地址
 * @param url 相对路径
 * @param body 握手请求体，`null` 表示不携带
 * @param raw 为 `true` 时跳过握手，直接连接 [url]（需自带 query）
 * @param ctx 请求上下文，用于取当前会话 id
 * @param sessionId 显式指定的会话 id，优先于 [ctx]
 * @param noLog 为 `true` 时不输出请求与响应日志
 */
suspend fun ws(
    baseUrl: String,
    url: String,
    body: JsonElement? = null,
    raw: Boolean = false,
    ctx: Context? = null,
    sessionId: String? = null,
    noLog: Boolean = false,
): WSConnection {
    val auth = sessionId ?: ctx?.sessionId
    var id: String? = null
    if (!raw) {
        id = post<String>(
            baseUrl, url, body,
            ctx = ctx, sessionId = auth,
            headers = mapOf("X-Upgrade" to "websocket"),
            noLog = noLog,
        )
    }

    val wsBaseUrl = baseUrl.replaceFirst("http", "ws")
    val target = if (raw) "$wsBaseUrl$url" else "$wsBaseUrl$url?websocketId=$id"
    if (!noLog) beforeWebRequest(body, wsBaseUrl, url)

    val session = try {
        withTimeout(DEFAULT_TIMEOUT) {
            apiClient.webSocketSession {
                url(target)
                auth?.let { header(HttpHeaders.Authorization, "Bearer $it") }
            }
        }
    } catch (e: Throwable) {
        catchWebRequestException(e, wsBaseUrl, url)
        throw e
    }
    if (!noLog) afterWebRequest("WebSocket 已连接", wsBaseUrl, url)

    val flow = session.incoming.receiveAsFlow()
        .mapNotNull { frame -> (frame as? Frame.Text)?.let { decodeWsFrame(it.data.decodeToString()) } }
        // close 仅作为流的终止信号，不透传给调用方（与 sse 一致）
        .onCompletion { if (!noLog) afterWebRequest("WebSocket 已关闭", wsBaseUrl, url) }
        .takeWhile { (event, _) -> event != "close" }

    return WSConnection(session, flow, coroutineJob(), id)
}

/** 解析 WS 帧 `{"event": "<name>", "data": {...}}`；缺省事件名为 message。 */
private fun decodeWsFrame(text: String): Pair<String, JsonElement?> {
    val element = runCatching { json.parseToJsonElement(text) }.getOrNull() ?: return "message" to JsonPrimitive(text)
    val obj = element as? JsonObject ?: return "message" to element
    return (obj["event"] as? JsonPrimitive)?.contentOrNull.orEmpty().ifEmpty { "message" } to obj["data"]
}

/**
 * 校验信封式响应（`{"success": true, ...}`）并返回响应对象。
 *
 * @throws ApiException 响应不是 JSON 对象，或 `success` 不为 `true`；错误详情取 `errors` 字段
 */
fun JsonElement.requireSuccess(): JsonObject {
    val obj = this as? JsonObject ?: throw ApiException(502, "响应不是 JSON 对象：${toString().s200()}")
    if ((obj["success"] as? JsonPrimitive)?.contentOrNull != "true") {
        throw ApiException(502, "接口返回失败：${(obj["errors"] ?: obj).toString().s200()}")
    }
    return obj
}

/** 一次流式会话的产物：下行事件流 + 远端会话 id + 承载协程 Job。 */
interface SSEResult {
    /** 下行事件流，元素为「事件名 to 数据」。 */
    val flow: Flow<Pair<String, JsonElement?>>

    /** 远端下发的会话 id；[raw] 直连时为 `null`。 */
    val id: String?

    /** 承载该连接的 Job。 */
    val job: Job
}

/** [sse] 路径的会话产物。 */
class SSESession(
    override val flow: Flow<Pair<String, JsonElement?>>,
    override val id: String?,
    override val job: Job,
) : SSEResult

/**
 * WebSocket 会话：既是下行事件流（[SSEResult]），又是可上行发送的连接（[EventSource]）。
 *
 * 帧格式与 [ws] 一致：`{"event": "<name>", "data": {...}}`。
 * 流结束（正常结束 / 收到 close / 异常）即视为连接关闭。
 */
class WSConnection(
    private val session: DefaultClientWebSocketSession,
    rawFlow: Flow<Pair<String, JsonElement?>>,
    override val job: Job,
    /** 两段式握手第一段拿到的 websocketId；[raw] 模式下为 `null` */
    override val id: String?,
) : SSEResult, EventSource {

    override val sseId: String
        get() = id ?: throw NoSuchElementException("该连接没有 id")

    @Volatile
    private var closed: Boolean = false
    override val isClosed: Boolean get() = closed

    override val flow: Flow<Pair<String, JsonElement?>> = rawFlow.onCompletion { markClosed() }

    /** 上行队列：连接建立后由发送协程消费。 */
    private val outbound = Channel<Frame>(Channel.UNLIMITED)

    /**
     * 心跳协程：定时发一帧**二进制**数据保活（部分代理会掐断空闲连接）。
     * 走二进制通道是因为下行只认 TEXT 帧，心跳不会污染事件流。
     */
    private val heartbeat: Job = CoroutineScope(Dispatchers.Default).launch {
        while (!closed) {
            delay(WS_HEARTBEAT_INTERVAL)
            if (closed) break
            outbound.trySend(Frame.Binary(true, WS_HEARTBEAT_PAYLOAD))
        }
    }

    /** 发送协程：把上行队列里的帧写进连接。 */
    private val sender: Job = CoroutineScope(Dispatchers.Default).launch {
        for (frame in outbound) {
            runCatching { session.send(frame) }
                .onFailure { logger.warn(it) { "WS 上行发送失败" } }
        }
    }

    override fun send(event: String, data: JsonElement?, retry: Int) {
        if (closed) throw IllegalStateException("连接已关闭")
        // WS 无 retry 语义，忽略该参数
        val text = buildJsonObject {
            put("event", event)
            put("data", data ?: JsonNull)
        }
        outbound.trySend(Frame.Text(text.toString()))
    }

    override suspend fun await() {
        job.join()
    }

    override fun close() {
        if (closed) return
        runCatching { send("close", null, EventSource.DEFAULT_RETRY) }
        markClosed()
        CoroutineScope(Dispatchers.Default).launch { runCatching { session.close() } }
    }

    private fun markClosed() {
        if (closed) return
        closed = true
        heartbeat.cancel()
        sender.cancel()
        outbound.close()
    }
}

/** 把字节流切成 SSE 帧（以空行为界）。 */
private fun Flow<ByteArray>.toEventSourceFrames(): Flow<ByteArray> = flow {
    var pending = ByteArray(0)
    collect { chunk ->
        pending += chunk
        var start = 0
        while (true) {
            val end = indexOfFrameEnd(pending, start)
            if (end < 0) break
            emit(pending.copyOfRange(start, end))
            start = end + 2
        }
        if (start > 0) pending = pending.copyOfRange(start, pending.size)
    }
}

/** 从 [from] 起找第一个空行（`\n\n`），返回其起始下标；找不到返回 `-1`。 */
private fun indexOfFrameEnd(bytes: ByteArray, from: Int): Int {
    for (i in from until bytes.size - 1) {
        if (bytes[i] == LF && bytes[i + 1] == LF) return i
    }
    return -1
}

/** 取当前协程的 Job；在协程外调用属于用法错误。 */
private suspend fun coroutineJob(): Job =
    kotlinx.coroutines.currentCoroutineContext()[Job] ?: error("必须在协程内使用")

private fun beforeWebRequest(body: JsonElement?, baseUrl: String, url: String) {
    logger.info { "发起请求: $baseUrl$url - ${body.s200()}" }
}

private fun afterWebRequest(result: Any?, baseUrl: String, url: String) {
    logger.info { "响应成功: $baseUrl$url - ${result.s200()}" }
}

private fun String.s200(): String =
    if (length > LOG_PAYLOAD_LIMIT) take(LOG_PAYLOAD_LIMIT) + "...(${length - LOG_PAYLOAD_LIMIT} more)" else this

private fun Any?.s200(): String = when (this) {
    null -> "null"
    is String -> s200()
    is Flow<*> -> "<flow>"
    else -> toString().s200()
}

/** 非 2xx：优先按 [ApiResult] 信封取业务错误信息，取不到就用原文。 */
private suspend fun respHandleException(response: HttpResponse, baseUrl: String, url: String): Nothing {
    val text = runCatching { response.bodyAsText() }.getOrDefault("")
    logger.warn { "响应失败: $baseUrl$url - ${response.status.value} - ${text.s200()}" }
    val msg = runCatching {
        json.decodeFromString(ApiResult.serializer(JsonElement.serializer()), text).msg
    }.getOrNull()
    throw ApiException(502, msg?.ifBlank { null } ?: text.s200())
}

private fun catchWebRequestException(e: Throwable, baseUrl: String, url: String) {
    if (e is CancellationException) return
    logger.warn(e) { "响应失败: $baseUrl$url" }
}

private const val LF: Byte = 10

/**
 * 简单的重试包装：连续失败超过 [maxRetry] 次后把最后一次异常抛出。
 *
 * @param maxRetry 允许的连续失败次数
 * @param warnMessage 记日志时的前缀
 * @param logger 日志出口
 */
class RetryScope(
    val maxRetry: Int = MAX_RETRY,
    private val warnMessage: String = "发生未知异常",
    private val logger: Logger = Log.of("Retry"),
) {
    private var retryCount: Int = 0
    var exception: Throwable? = null
        private set

    /** 执行 [block]；成功则清空计数，失败则累计并在超限时抛出。 */
    fun <R> scope(block: () -> R): R? {
        try {
            val result = block()
            clearCount()
            return result
        } catch (e: Throwable) {
            handleException(e)
        }
        return null
    }

    /** 清空失败计数与最后一次异常。 */
    fun clearCount() {
        retryCount = 0
        exception = null
    }

    /** 记录一次失败；超过 [maxRetry] 次则把异常抛出。 */
    fun handleException(e: Throwable) {
        logger.warn(e) { warnMessage }
        if (retryCount > maxRetry) throw e
        retryCount++
        exception = e
    }

    /** 当前连续失败次数。 */
    val count: Int get() = retryCount
}
