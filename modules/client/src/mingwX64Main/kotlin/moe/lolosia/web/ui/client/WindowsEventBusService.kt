package moe.lolosia.web.ui.client

import io.ktor.client.HttpClient
import io.ktor.client.engine.curl.Curl
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.prepareGet
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.utils.io.readAvailable
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.get
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.toKString
import kotlinx.cinterop.wcstr
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import moe.lolosia.web.ui.event.EventBusSubscription
import moe.lolosia.web.ui.event.EventStreamTransport
import moe.lolosia.web.ui.event.EventBusException
import moe.lolosia.web.ui.event.HttpResult
import moe.lolosia.web.ui.event.ServerPushMessage
import moe.lolosia.web.ui.session.ClientSettings
import moe.lolosia.web.ui.session.SettingsStore
import moe.lolosia.web.util.log.Log
import okio.FileSystem
import okio.Path.Companion.toPath
import okio.buffer
import platform.posix.getenv
import platform.posix.time
import platform.windows.DWORD
import platform.windows.GetLastError
import platform.windows.LPVOID
import platform.windows.LPWSTR
import platform.windows.LPWSTRVar
import platform.windows.RegisterServiceCtrlHandlerExW
import platform.windows.SERVICE_ACCEPT_STOP
import platform.windows.SERVICE_CONTROL_STOP
import platform.windows.SERVICE_RUNNING
import platform.windows.SERVICE_STATUS
import platform.windows.SERVICE_STATUS_HANDLE
import platform.windows.SERVICE_STOPPED
import platform.windows.SERVICE_TABLE_ENTRYW
import platform.windows.SERVICE_WIN32_OWN_PROCESS
import platform.windows.SetServiceStatus
import platform.windows.StartServiceCtrlDispatcherW
import kotlin.concurrent.Volatile
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

private const val SERVICE_NAME = "LolosiaEventBus"

/** SCM 控制句柄；只在服务主线程写入，控制回调读取。 */
@Volatile
private var serviceStatusHandle: SERVICE_STATUS_HANDLE? = null

/** 当前订阅任务；控制回调用它取消阻塞中的 SSE 读。 */
@Volatile
private var serviceJob: Job? = null

/** 当前订阅对象；控制回调先 close 传输层再取消任务。 */
@Volatile
private var serviceSubscription: EventBusSubscription? = null

/**
 * Windows 事件总线服务入口。
 *
 * `console = false` 时通过 `StartServiceCtrlDispatcherW` 交给 SCM（必须由 SCM 启动，
 * 直接双击运行会报 1063）；`console = true` 时跳过 SCM，直接在当前进程跑订阅循环。
 *
 * @author 洛洛希雅Lolosia
 */
/** Windows 实现：SCM 分发或控制台直跑。 */
internal fun runWindowsEventService(console: Boolean): Int {
    if (console) {
        runEventLoop()
        return 0
    }

    return memScoped {
        val table = allocArray<SERVICE_TABLE_ENTRYW>(2)
        table.get(0).lpServiceName = SERVICE_NAME.wcstr.ptr
        table.get(0).lpServiceProc = serviceMainProc
        table.get(1).lpServiceName = null
        table.get(1).lpServiceProc = null
        if (StartServiceCtrlDispatcherW(table) == 0) {
            val error = GetLastError()
            Log.of("EventService").error {
                "连接 SCM 失败：$error（本地调试请用 --event-service-console）"
            }
            return@memScoped 1
        }
        0
    }
}

/** SCM 服务主函数：注册控制回调、上报状态、阻塞跑订阅循环。 */
private fun serviceMain() {
    val log = Log.of("EventService")
    serviceStatusHandle = RegisterServiceCtrlHandlerExW(SERVICE_NAME, serviceControlHandler, null)
    if (serviceStatusHandle == null) {
        log.error { "注册服务控制回调失败：${GetLastError()}" }
        return
    }
    reportServiceStatus(SERVICE_RUNNING.toUInt())
    log.info { "事件服务已启动" }
    runEventLoop()
    reportServiceStatus(SERVICE_STOPPED.toUInt())
    log.info { "事件服务已停止" }
}

/** 订阅循环：读 `%ProgramData%\Lolosia\event-service.json`，事件追加到 spool 文件。 */
private fun runEventLoop() {
    val log = Log.of("EventService")
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val subscription = EventBusSubscription(
        settings = EventServiceSettingsStore(),
        transportFactory = { WindowsEventStreamTransport() },
        onStatus = { status -> log.info { "状态：$status" } },
        onMessage = { message ->
            WindowsEventSpool.append(message)
            log.info { "推送：${message.title} - ${message.text}" }
        },
        onLog = { line -> log.info { line } },
    )
    serviceSubscription = subscription
    val job = scope.launch { subscription.run() }
    serviceJob = job
    runBlocking { job.join() }
    scope.cancel()
    serviceSubscription = null
    serviceJob = null
}

private fun reportServiceStatus(state: UInt) {
    val handle = serviceStatusHandle ?: return
    memScoped {
        val status = alloc<SERVICE_STATUS>()
        status.dwServiceType = SERVICE_WIN32_OWN_PROCESS.toUInt()
        status.dwCurrentState = state
        status.dwControlsAccepted = if (state == SERVICE_RUNNING.toUInt()) {
            SERVICE_ACCEPT_STOP.toUInt()
        } else {
            0u
        }
        status.dwWin32ExitCode = 0u
        status.dwServiceSpecificExitCode = 0u
        status.dwCheckPoint = 0u
        status.dwWaitHint = 0u
        SetServiceStatus(handle, status.ptr)
    }
}

/** 服务配置：放在 ProgramData，避免把会话 token 写进服务注册表。 */
internal class EventServiceSettingsStore(
    private val path: String = "${programDataDir()}\\Lolosia\\event-service.json",
) : SettingsStore {

    private val json = Json { ignoreUnknownKeys = true }

    override fun load(): ClientSettings = runCatching {
        val file = path.toPath()
        if (!FileSystem.SYSTEM.exists(file)) return@runCatching ClientSettings()
        json.decodeFromString<ClientSettings>(FileSystem.SYSTEM.read(file) { readUtf8() })
    }.getOrDefault(ClientSettings())

    override fun save(settings: ClientSettings) = Unit
}

/**
 * Windows 侧的 EventBus 传输。
 *
 * 用 Ktor Curl（libcurl）而不是 WinHTTP：后者会把响应体整体读完才返回，
 * SSE 这种永不结束的响应读不到任何事件；libcurl 的写回调是增量的。
 * TLS 由 Windows Schannel 提供。
 */
internal class WindowsEventStreamTransport : EventStreamTransport {

    private val client = HttpClient(Curl)

    override suspend fun postJson(
        baseUrl: String,
        path: String,
        token: String?,
        body: String,
        headers: Map<String, String>,
    ): HttpResult {
        val response = client.post(baseUrl + path) {
            contentType(ContentType.Application.Json)
            headers.forEach { (name, value) -> header(name, value) }
            if (!token.isNullOrBlank()) header("Authorization", "Bearer $token")
            setBody(body)
        }
        return HttpResult(response.status.value, response.bodyAsText())
    }

    override suspend fun openEventStream(
        baseUrl: String,
        path: String,
        headers: Map<String, String>,
        onLine: (String) -> Unit,
    ) {
        client.prepareGet(baseUrl + path) {
            headers.forEach { (name, value) -> header(name, value) }
        }.execute { response ->
            if (!response.status.isSuccess()) {
                throw EventBusException("SSE 建流失败：HTTP ${response.status.value}")
            }
            val channel = response.bodyAsChannel()
            val buffer = ByteArray(STREAM_BUFFER_SIZE)
            var pending = ""
            while (true) {
                val read = channel.readAvailable(buffer, 0, buffer.size)
                if (read <= 0) break
                pending += buffer.decodeToString(0, read)
                while (true) {
                    val end = pending.indexOf('\n')
                    if (end < 0) break
                    val line = pending.substring(0, end).trimEnd('\r')
                    pending = pending.substring(end + 1)
                    onLine(line)
                }
            }
        }
    }

    override fun close() {
        client.close()
    }

    private companion object {
        const val STREAM_BUFFER_SIZE = 8192
    }
}

/** 服务与 UI 之间的单向队列：服务追加，UI 进程按行读取并弹通知。 */
@Serializable
internal data class EventSpoolRecord(val ts: Long, val title: String, val text: String)

internal object WindowsEventSpool {

    private val json = Json { encodeDefaults = true }

    val path: String get() = "${programDataDir()}\\Lolosia\\event-bus-events.jsonl"

    fun append(message: ServerPushMessage) {
        runCatching {
            val file = path.toPath()
            file.parent?.let { FileSystem.SYSTEM.createDirectories(it) }
            val record = EventSpoolRecord(time(null) * 1000L, message.title, message.text)
            val sink = FileSystem.SYSTEM.appendingSink(file).buffer()
            try {
                sink.writeUtf8(json.encodeToString(record))
                sink.writeUtf8("\n")
            } finally {
                sink.close()
            }
        }
    }
}

/** `%ProgramData%`；服务与用户进程都读得写得的共享位置。 */
internal fun programDataDir(): String =
    getenv("ProgramData")?.toKString()?.takeIf { it.isNotBlank() } ?: "C:\\ProgramData"

/** SCM 控制回调：收到 STOP 时断开 SSE 并取消订阅循环。 */
@OptIn(ExperimentalForeignApi::class)
private val serviceControlHandler = kotlinx.cinterop.staticCFunction {
        control: DWORD, _: DWORD, _: LPVOID?, _: LPVOID? ->
    if (control == SERVICE_CONTROL_STOP.toUInt()) {
        Log.of("EventService").info { "收到 SCM 停止请求" }
        serviceSubscription?.close()
        serviceJob?.cancel()
    }
    0u
}

/** SCM 服务主函数指针。 */
@OptIn(ExperimentalForeignApi::class)
private val serviceMainProc = kotlinx.cinterop.staticCFunction { _: DWORD, _: CPointer<LPWSTRVar>? ->
    serviceMain()
}
