package moe.lolosia.web.ui.client

import kotlinx.serialization.json.Json
import moe.lolosia.web.api.get
import moe.lolosia.web.dto.LOCAL_PROTOCOL_VERSION
import moe.lolosia.web.dto.LocalCapabilitiesDto
import moe.lolosia.web.dto.LocalEndpoint
import moe.lolosia.web.dto.LocalHealthDto
import moe.lolosia.web.dto.LocalMetricsDto
import moe.lolosia.web.ui.api.LocalHealth
import moe.lolosia.web.ui.api.LocalMetrics
import moe.lolosia.web.ui.api.LocalServiceApi
import moe.lolosia.web.util.platform.envOrNull
import okio.FileSystem
import okio.Path.Companion.toPath

/**
 * 通过 loopback HTTP 访问本机系统服务。
 *
 * 每次调用都重新读取发现文件：服务重启后端口与 token 会轮换，UI 无需重启即可恢复。
 * 文件缺失、协议不匹配或请求失败一律按「服务未运行」降级，不向页面抛异常。
 *
 * @param endpointPath 发现文件路径；为空时用默认位置（`%ProgramData%\Lolosia\local-service.json`）。
 * @author 洛洛希雅Lolosia
 */
class CoreLocalServiceApi(
    private val endpointPath: String? = null,
) : LocalServiceApi {

    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun health(): LocalHealth? = runCatching {
        val endpoint = loadEndpoint() ?: return null
        val dto = get<LocalHealthDto>(
            localBaseUrl(endpoint),
            "/local/v1/health",
            sessionId = endpoint.token,
            noLog = true,
        )
        LocalHealth(ok = dto.ok, version = dto.version, uptime = dto.uptime)
    }.getOrNull()

    override suspend fun capabilities(): List<String> = runCatching {
        val endpoint = loadEndpoint() ?: return emptyList()
        get<LocalCapabilitiesDto>(
            localBaseUrl(endpoint),
            "/local/v1/capabilities",
            sessionId = endpoint.token,
            noLog = true,
        ).items
    }.getOrDefault(emptyList())

    override suspend fun metrics(): LocalMetrics? = runCatching {
        val endpoint = loadEndpoint() ?: return null
        val dto = get<LocalMetricsDto>(
            localBaseUrl(endpoint),
            "/local/v1/metrics",
            sessionId = endpoint.token,
            noLog = true,
        )
        LocalMetrics(cpu = dto.cpu, mem = dto.mem, disk = dto.disk)
    }.getOrNull()

    /** 读取发现文件；不存在或协议版本不一致时返回 `null`。 */
    private fun loadEndpoint(): LocalEndpoint? = runCatching {
        val file = (endpointPath ?: defaultEndpointPath()).toPath()
        if (!FileSystem.SYSTEM.exists(file)) return null
        val endpoint = json.decodeFromString<LocalEndpoint>(FileSystem.SYSTEM.read(file) { readUtf8() })
        endpoint.takeIf { it.protocol == LOCAL_PROTOCOL_VERSION && it.port in 1..65535 }
    }.getOrNull()

    private fun localBaseUrl(endpoint: LocalEndpoint): String = "http://127.0.0.1:${endpoint.port}"
}

/** 与服务端相同的发现文件位置；可用 `LOLOSIA_LOCAL_ENDPOINT` 覆盖。 */
private fun defaultEndpointPath(): String {
    envOrNull("LOLOSIA_LOCAL_ENDPOINT")?.takeIf { it.isNotBlank() }?.let { return it }
    val base = envOrNull("PROGRAMDATA")?.takeIf { it.isNotBlank() } ?: "."
    return "$base/Lolosia/local-service.json"
}
