package moe.lolosia.web.api

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import moe.lolosia.web.util.log.Log
import moe.lolosia.web.util.proc.shellExec
import okio.FileSystem
import okio.Path.Companion.toPath

private val logger = Log.of("API")

private val json = Json {
    ignoreUnknownKeys = true
    isLenient = true
}

/** 请求超时（秒），对应 curl 的 `--max-time`。 */
public const val DEFAULT_TIMEOUT_SECONDS: Int = 20

/** 请求体与响应体的转存路径；两者都先落盘，避免管道缓冲与 shell 转义。 */
private const val RESPONSE_FILE = "/tmp/ktor-api-response.json"
private const val BODY_FILE = "/tmp/ktor-api-body.json"

/**
 * 外部请求失败时抛出。
 *
 * 涵盖三种情形：进程无输出、响应体不是合法 JSON、响应信封标记为失败。
 */
public class ApiRequestException(message: String) : IllegalStateException(message)

/**
 * 向外部服务发起一次 HTTP 请求，返回响应体解析出的 JSON。
 *
 * 请求体经临时文件传递（`--data-binary @file`）以避免 shell 转义；带请求体时若未显式指定
 * `Content-Type`，默认补 `application/json`。底层由系统 `curl` 执行，见 [shellExec]。
 *
 * @param method HTTP 方法，`GET` 时不发送请求体
 * @param url 完整 URL（含 query）
 * @param body 请求体，`null` 表示不携带
 * @param headers 额外请求头
 * @param timeoutSeconds 请求超时（秒）
 * @param noLog 为 `true` 时不输出请求与响应日志
 * @throws ApiRequestException 进程无输出，或响应体不是合法 JSON
 */
public suspend fun request(
    method: String,
    url: String,
    body: JsonElement? = null,
    headers: Map<String, String> = emptyMap(),
    timeoutSeconds: Int = DEFAULT_TIMEOUT_SECONDS,
    noLog: Boolean = false,
): JsonElement {
    val payload = body?.let { json.encodeToString(JsonElement.serializer(), it) }
    if (payload != null) {
        FileSystem.SYSTEM.write(BODY_FILE.toPath()) { writeUtf8(payload) }
    }

    val command = buildString {
        append("curl -sS --max-time ").append(timeoutSeconds)
        if (method != "GET") append(" -X ").append(method)
        headers.forEach { (name, value) -> append(" -H ").append(quote("$name: $value")) }
        if (payload != null) {
            if (headers.keys.none { it.equals("Content-Type", ignoreCase = true) }) {
                append(" -H ").append(quote("Content-Type: application/json"))
            }
            append(" --data-binary @").append(BODY_FILE)
        }
        append(' ').append(quote(url))
        append(" > ").append(RESPONSE_FILE).append(" 2>&1")
    }

    if (!noLog) logger.info { "发起请求: $method $url" }
    // 阻塞的进程调用挪到后台线程，避免占住事件循环
    val exit = withContext(Dispatchers.Default) { shellExec(command) }

    val text = runCatching { FileSystem.SYSTEM.read(RESPONSE_FILE.toPath()) { readUtf8() } }.getOrNull()
        ?: throw ApiRequestException("$method $url 无响应（curl exit=$exit）")
    val element = runCatching { json.parseToJsonElement(text) }.getOrElse {
        throw ApiRequestException("$method $url 响应不是 JSON（curl exit=$exit）：${text.take(300)}")
    }
    if (!noLog) logger.info { "响应成功: $method $url - ${text.take(200)}" }
    return element
}

/** 发起 GET 请求，返回响应 JSON。 */
public suspend fun get(
    url: String,
    headers: Map<String, String> = emptyMap(),
    timeoutSeconds: Int = DEFAULT_TIMEOUT_SECONDS,
    noLog: Boolean = false,
): JsonElement = request("GET", url, null, headers, timeoutSeconds, noLog)

/** 发起 POST 请求，返回响应 JSON。 */
public suspend fun post(
    url: String,
    body: JsonElement? = null,
    headers: Map<String, String> = emptyMap(),
    timeoutSeconds: Int = DEFAULT_TIMEOUT_SECONDS,
    noLog: Boolean = false,
): JsonElement = request("POST", url, body, headers, timeoutSeconds, noLog)

/** 发起 PUT 请求，返回响应 JSON。 */
public suspend fun put(
    url: String,
    body: JsonElement? = null,
    headers: Map<String, String> = emptyMap(),
    timeoutSeconds: Int = DEFAULT_TIMEOUT_SECONDS,
    noLog: Boolean = false,
): JsonElement = request("PUT", url, body, headers, timeoutSeconds, noLog)

/**
 * 校验信封式响应（`{"success": true, ...}`）并返回响应对象。
 *
 * @throws ApiRequestException 响应不是 JSON 对象，或 `success` 不为 `true`；错误详情取 `errors` 字段
 */
public fun JsonElement.requireSuccess(): JsonObject {
    val obj = this as? JsonObject
        ?: throw ApiRequestException("响应不是 JSON 对象：${toString().take(300)}")
    if ((obj["success"] as? JsonPrimitive)?.booleanOrNull != true) {
        throw ApiRequestException("API 返回失败：${(obj["errors"] ?: obj).toString().take(500)}")
    }
    return obj
}

/** 以单引号包裹参数；参数内部的单引号按 `'\''` 转义。 */
private fun quote(value: String): String = "'" + value.replace("'", "'\\''") + "'"
