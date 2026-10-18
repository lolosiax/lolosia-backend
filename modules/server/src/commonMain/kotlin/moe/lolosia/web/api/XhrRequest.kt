package moe.lolosia.web.api

import io.ktor.http.*
import kotlinx.serialization.json.*
import moe.lolosia.web.dto.ApiException
import moe.lolosia.web.util.xhrdata.decodeXhrData
import kotlin.time.Duration

private const val LOG_PAYLOAD_LIMIT: Int = 200

/**
 * 走**自定义请求通道**（`application/vnd.lolosia.xhr.data`：msgpack + gzip）请求外部服务，
 * 解出信封后返回 `data`。用于境外中继这类「不愿暴露明文 JSON 特征」的链路（见
 * `doc/anime-timetable-design.md` §4.4）。
 *
 * 非 2xx 或信封 `code` 非 0/200 时抛 [ApiException]（优先取通道响应里的 `msg`）。
 */
suspend fun xhrRequest(
    method: HttpMethod,
    baseUrl: String,
    url: String,
    body: JsonElement? = null,
    headers: Map<String, String> = emptyMap(),
    timeout: Duration = DEFAULT_TIMEOUT,
    noLog: Boolean = false,
): JsonElement {
    val (status, raw) = requestBytes(method, baseUrl, url, body, headers, timeout, noLog)
    val element = runCatching { decodeXhrData(raw) }.getOrElse {
        throw ApiException(502, "中继响应不是通道格式：${raw.decodeToString().s200()}")
    }
    val env = element as? JsonObject ?: throw ApiException(502, "中继通道响应不是 JSON 对象")
    val code = (env["code"] as? JsonPrimitive)?.intOrNull ?: 0
    val msg = (env["msg"] as? JsonPrimitive)?.contentOrNull.orEmpty()
    if (status !in 200..299 || (code != 0 && code != 200)) {
        throw ApiException(status, msg.ifBlank { "中继请求失败" })
    }
    return env["data"] ?: JsonNull
}

private fun String.s200(): String =
    if (length > LOG_PAYLOAD_LIMIT) take(LOG_PAYLOAD_LIMIT) + "...(${length - LOG_PAYLOAD_LIMIT} more)" else this
