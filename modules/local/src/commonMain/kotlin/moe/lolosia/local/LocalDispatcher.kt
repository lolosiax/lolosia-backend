package moe.lolosia.local

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import moe.lolosia.local.metrics.MetricsSampler
import moe.lolosia.web.dto.LOCAL_PROTOCOL_VERSION
import moe.lolosia.web.dto.LocalCapabilitiesDto
import moe.lolosia.web.dto.LocalError
import moe.lolosia.web.dto.LocalFrame
import moe.lolosia.web.dto.LocalHealthDto
import moe.lolosia.web.dto.LocalMetricsDto
import moe.lolosia.web.dto.LocalVersionDto
import moe.lolosia.web.util.time.nowEpochMs

/**
 * 本地服务的方法分发：路径（方法名）匹配、鉴权、参数解码、错误信封都在这里。
 *
 * 进程内传输与 loopback 传输共用本类，传输层只搬字节，因此两种启动方式行为一致。
 * 本类不依赖 Ktor，也不依赖服务器的 `Context` / 路由生成。
 *
 * @author 洛洛希雅Lolosia
 */
class LocalDispatcher(
    private val metrics: MetricsSampler = MetricsSampler(),
    private val startedAtMs: Long = nowEpochMs(),
) {

    private val json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = true
    }

    /**
     * 处理一帧请求。
     *
     * @param authorized 传输层给出的鉴权结论；进程内为 `true`，loopback 由 token 校验得出。
     * @return 要回给调用方的帧（正常一个响应）。所有异常都在这里转成错误帧，
     *   避免单进程模式下把 UI 一起带走。
     */
    suspend fun dispatch(frame: LocalFrame, authorized: Boolean): LocalDispatchResult {
        val id = frame.id
        if (!authorized) return error(id, 401, "本地服务未授权")
        val method = frame.method ?: return error(id, 400, "缺少 method")
        return try {
            when (method) {
                "hello" -> hello(id, frame.params)
                "capabilities.get" -> ok(
                    id,
                    json.encodeToJsonElement(
                        LocalCapabilitiesDto.serializer(),
                        LocalCapabilitiesDto(LOCAL_SERVICE_CAPABILITIES),
                    ),
                )
                "metrics.get" -> ok(
                    id,
                    json.encodeToJsonElement(LocalMetricsDto.serializer(), metrics.read()),
                )
                "version.get" -> ok(
                    id,
                    json.encodeToJsonElement(
                        LocalVersionDto.serializer(),
                        LocalVersionDto(LOCAL_SERVICE_VERSION, LOCAL_SERVICE_BUILD),
                    ),
                )
                else -> error(id, 404, "未知方法：$method")
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            error(id, 500, e.message ?: "本地服务内部错误")
        }
    }

    /** `hello`：协商协议版本，返回健康状态。 */
    private fun hello(id: Long?, params: JsonObject?): LocalDispatchResult {
        val clientProtocol = params?.get("clientProtocol")?.jsonPrimitive?.intOrNull
            ?: LOCAL_PROTOCOL_VERSION
        if (clientProtocol != LOCAL_PROTOCOL_VERSION) {
            return error(id, 409, "协议版本不一致：客户端 $clientProtocol，服务 $LOCAL_PROTOCOL_VERSION")
        }
        val uptime = ((nowEpochMs() - startedAtMs) / 1000).coerceAtLeast(0)
        return ok(
            id,
            json.encodeToJsonElement(
                LocalHealthDto.serializer(),
                LocalHealthDto(
                    ok = true,
                    version = LOCAL_SERVICE_VERSION,
                    uptime = uptime,
                    protocol = LOCAL_PROTOCOL_VERSION,
                ),
            ),
        )
    }

    private fun ok(id: Long?, result: kotlinx.serialization.json.JsonElement): LocalDispatchResult =
        LocalDispatchResult(listOf(LocalFrame(id = id, result = result)))

    private fun error(id: Long?, code: Int, message: String): LocalDispatchResult =
        LocalDispatchResult(listOf(LocalFrame(id = id, error = LocalError(code, message))))
}
