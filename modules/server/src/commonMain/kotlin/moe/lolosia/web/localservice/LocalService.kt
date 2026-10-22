package moe.lolosia.web.localservice

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.cio.*
import io.ktor.server.engine.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.delay
import kotlinx.serialization.KSerializer
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import moe.lolosia.local.metrics.MetricsSampler
import moe.lolosia.web.dto.ApiResult
import moe.lolosia.web.dto.LOCAL_PROTOCOL_VERSION
import moe.lolosia.web.dto.LocalCapabilitiesDto
import moe.lolosia.web.dto.LocalEndpoint
import moe.lolosia.web.dto.LocalHealthDto
import moe.lolosia.web.dto.LocalMetricsDto
import moe.lolosia.web.dto.LocalVersionDto
import moe.lolosia.web.util.log.Log
import moe.lolosia.web.util.platform.envOrNull
import okio.FileSystem
import okio.Path.Companion.toPath
import kotlin.concurrent.Volatile
import kotlin.random.Random
import kotlin.time.TimeSource

/**
 * 本地系统服务：在 loopback 上提供只读的本机能力，供 UI 进程查询。
 *
 * 与远程 `/api/` 使用同一套 `{code,msg,data}` 信封；只绑定 `127.0.0.1`，
 * 每次启动生成随机端口与 token，并写入 [endpointPath] 供 UI 发现。
 * **不代理远程 API**，也不接受 UI 传入的路径或命令。
 *
 * @author 洛洛希雅Lolosia
 */
class LocalService internal constructor(
    private val stopEngine: () -> Unit,
    val endpoint: LocalEndpoint,
    private val endpointPath: String,
) {

    /** 停止服务并删除发现文件；重复调用无副作用。 */
    fun stop() {
        runCatching { stopEngine() }
        runCatching { FileSystem.SYSTEM.delete(endpointPath.toPath()) }
    }
}

/** 服务版本；与 UI 的展示对齐，构建标识留给后续接入。 */
private const val LOCAL_SERVICE_VERSION = "1.0.0"

private const val LOCAL_SERVICE_BUILD = "native"

private val json = Json {
    encodeDefaults = true
    ignoreUnknownKeys = true
}

/**
 * 启动本地服务并返回句柄。
 *
 * @param endpointPath 发现文件路径；默认 `%ProgramData%\Lolosia\local-service.json`，
 *   可用环境变量 `LOLOSIA_LOCAL_ENDPOINT` 覆盖（开发/测试用）。
 */
suspend fun startLocalService(endpointPath: String = defaultLocalEndpointPath()): LocalService {
    val token = randomToken()
    val startedAt = TimeSource.Monotonic.markNow()
    val metrics = MetricsSampler()
    val server = embeddedServer(CIO, port = 0, host = "127.0.0.1") {
        routing {
            get("/local/v1/health") {
                if (!call.authorized(token)) return@get
                call.respondEnvelope(
                    ApiResult.ok(
                        LocalHealthDto(
                            ok = true,
                            version = LOCAL_SERVICE_VERSION,
                            uptime = startedAt.elapsedNow().inWholeSeconds,
                            protocol = LOCAL_PROTOCOL_VERSION,
                        )
                    ),
                    LocalHealthDto.serializer(),
                )
            }
            get("/local/v1/capabilities") {
                if (!call.authorized(token)) return@get
                call.respondEnvelope(
                    ApiResult.ok(LocalCapabilitiesDto(listOf("metrics", "version"))),
                    LocalCapabilitiesDto.serializer(),
                )
            }
            get("/local/v1/metrics") {
                if (!call.authorized(token)) return@get
                call.respondEnvelope(ApiResult.ok(metrics.read()), LocalMetricsDto.serializer())
            }
            get("/local/v1/version") {
                if (!call.authorized(token)) return@get
                call.respondEnvelope(
                    ApiResult.ok(LocalVersionDto(LOCAL_SERVICE_VERSION, LOCAL_SERVICE_BUILD)),
                    LocalVersionDto.serializer(),
                )
            }
        }
    }
    server.start(wait = false)
    val port = server.engine.resolvedConnectors().first().port
    val endpoint = LocalEndpoint(port = port, token = token, pid = 0)
    writeEndpoint(endpointPath, endpoint)
    Log.of("LocalService").info { "本地服务已启动：$port -> $endpointPath" }
    return LocalService(stopEngine = { server.stop(500, 1500) }, endpoint = endpoint, endpointPath = endpointPath)
}

/** 默认发现文件路径：ProgramData 下按应用分目录，便于后续收紧 ACL。 */
fun defaultLocalEndpointPath(): String {
    envOrNull("LOLOSIA_LOCAL_ENDPOINT")?.takeIf { it.isNotBlank() }?.let { return it }
    val base = envOrNull("PROGRAMDATA")?.takeIf { it.isNotBlank() } ?: "."
    return "$base/Lolosia/local-service.json"
}

/** 写发现文件；目录不存在时创建。写失败只记日志，不影响服务本身。 */
private fun writeEndpoint(path: String, endpoint: LocalEndpoint) {
    runCatching {
        val file = path.toPath()
        file.parent?.let { FileSystem.SYSTEM.createDirectories(it) }
        FileSystem.SYSTEM.write(file) { writeUtf8(json.encodeToString(endpoint)) }
    }.onFailure {
        Log.of("LocalService").warn(it) { "写本地服务发现文件失败：$path" }
    }
}

/** 校验 `Authorization: Bearer <token>`；失败时直接回 401 信封。 */
private suspend fun ApplicationCall.authorized(token: String): Boolean {
    if (request.headers[HttpHeaders.Authorization] == "Bearer $token") return true
    respondText(UNAUTHORIZED_BODY, ContentType.Application.Json, HttpStatusCode.Unauthorized)
    return false
}

/** 按 `{code,msg,data}` 信封回 JSON；手工序列化，避免为本地服务再装一套内容协商。 */
private suspend fun <T> ApplicationCall.respondEnvelope(result: ApiResult<T>, serializer: KSerializer<T>) {
    respondText(
        json.encodeToString(ApiResult.serializer(serializer), result),
        ContentType.Application.Json,
    )
}

/** 32 位十六进制随机 token。 */
private fun randomToken(): String {
    val bytes = Random.nextBytes(16)
    return buildString(bytes.size * 2) {
        bytes.forEach { byte ->
            val value = byte.toInt() and 0xFF
            append(HEX[value ushr 4])
            append(HEX[value and 0x0F])
        }
    }
}

private const val HEX = "0123456789abcdef"

/** 未授权响应：仍是 `{code,msg,data}` 信封。 */
private const val UNAUTHORIZED_BODY: String = """{"code":401,"msg":"本地服务未授权","data":null}"""
