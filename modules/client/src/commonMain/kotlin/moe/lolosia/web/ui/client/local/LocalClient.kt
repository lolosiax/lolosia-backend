package moe.lolosia.web.ui.client.local

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.put
import moe.lolosia.local.LocalTransport
import moe.lolosia.web.dto.LOCAL_PROTOCOL_VERSION
import moe.lolosia.web.dto.LocalCapabilitiesDto
import moe.lolosia.web.dto.LocalFrame
import moe.lolosia.web.dto.LocalHealthDto
import moe.lolosia.web.dto.LocalMetricsDto
import moe.lolosia.web.ui.api.LocalHealth
import moe.lolosia.web.ui.api.LocalMetrics
import moe.lolosia.web.ui.api.LocalServiceApi
import kotlin.random.Random

/**
 * 本地服务客户端：请求 / 响应关联、事件流与超时。
 *
 * 传输层可以是进程内的两条 Channel，也可以是 loopback WebSocket；
 * 本类不关心是哪一种，`--embed` 与分离部署因此走同一套调用路径。
 *
 * 所有接口方法遵守 [LocalServiceApi] 的约定：失败返回 `null` / 空列表，不向页面抛异常。
 *
 * @author 洛洛希雅Lolosia
 */
class LocalClient(
    private val transport: LocalTransport,
) : LocalServiceApi, AutoCloseable {

    private val json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = true
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val mutex = Mutex()
    private val pending = mutableMapOf<Long, CompletableDeferred<LocalFrame>>()
    private val eventFlow = MutableSharedFlow<LocalFrame>(extraBufferCapacity = 64)

    /** 服务推送的事件；目前没有方法产生事件，后续 job / 提示会用到。 */
    val events: SharedFlow<LocalFrame> = eventFlow

    init {
        scope.launch { receiveLoop() }
    }

    override suspend fun health(): LocalHealth? = runCatching {
        val result = request(
            "hello",
            buildJsonObject { put("clientProtocol", LOCAL_PROTOCOL_VERSION) },
        )
        val dto = json.decodeFromJsonElement(LocalHealthDto.serializer(), result)
        LocalHealth(ok = dto.ok, version = dto.version, uptime = dto.uptime)
    }.getOrNull()

    override suspend fun capabilities(): List<String> = runCatching {
        val result = request("capabilities.get")
        json.decodeFromJsonElement(LocalCapabilitiesDto.serializer(), result).items
    }.getOrDefault(emptyList())

    override suspend fun metrics(): LocalMetrics? = runCatching {
        val result = request("metrics.get")
        val dto = json.decodeFromJsonElement(LocalMetricsDto.serializer(), result)
        LocalMetrics(cpu = dto.cpu, mem = dto.mem, disk = dto.disk)
    }.getOrNull()

    /** 关闭连接并停止接收循环；重复调用无副作用。 */
    override fun close() {
        transport.close()
        scope.cancel()
    }

    /** 发一个请求并等待匹配 id 的响应。 */
    private suspend fun request(method: String, params: JsonObject? = null): JsonElement {
        val id = Random.nextLong()
        val deferred = CompletableDeferred<LocalFrame>()
        mutex.withLock { pending[id] = deferred }
        try {
            transport.send(LocalFrame(id = id, method = method, params = params))
            val frame = withTimeoutOrNull(REQUEST_TIMEOUT_MS) { deferred.await() }
                ?: error("本地服务响应超时：$method")
            frame.error?.let { error("本地服务错误 ${it.code}：${it.message}") }
            return frame.result ?: JsonNull
        } finally {
            mutex.withLock { pending.remove(id) }
        }
    }

    /** 接收循环：响应交给挂起表，事件进事件流。 */
    private suspend fun receiveLoop() {
        while (true) {
            val frame = runCatching { transport.receive() }.getOrNull() ?: return
            val id = frame.id
            if (id != null && (frame.result != null || frame.error != null)) {
                val deferred = mutex.withLock { pending.remove(id) }
                deferred?.complete(frame)
            } else if (frame.event != null) {
                eventFlow.tryEmit(frame)
            }
        }
    }
}

/** 单次请求默认超时；长任务应改用 job 模型，而不是调大这个值。 */
private const val REQUEST_TIMEOUT_MS: Long = 15_000
