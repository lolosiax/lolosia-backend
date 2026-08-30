package moe.lolosia.web.api

/**
 * ## 网络请求层
 *
 * 本包提供了通用的 HTTP 请求基础设施，基于 [Ktor Client](https://ktor.io/) 构建。
 *
 * ### 核心组件
 *
 * | 组件 | 说明 |
 * |------|------|
 * | [post] | 发起 `POST` 请求，支持 JSON 和 multipart/form-data |
 * | [get]  | 发起 `GET` 请求，支持查询参数和二进制流 |
 * | [put]  | 发起 `PUT` 请求，支持 JSON 和 multipart/form-data |
 * | [sse]  | 建立 SSE 长连接，返回实时事件流 |
 * | [FormDataBody] | 文件上传的 multipart 载体 |
 * | [ApiResponseEntity] | 统一的后端响应包装体 |
 * | [SSEResult] | SSE 连接结果封装 |
 * | [ErrorResponseException] | 远端业务错误异常 |
 * | [RetryScope] | 自动重试工具 |
 * | [createHttpClient] | 工厂方法，创建预配置的 [HttpClient] |
 *
 * ### 架构说明
 *
 * ```
 * 调用方 (UserApi / 业务代码)
 *     │
 *     ▼
 * post() / get() / put()          ← 公开 API（reified 泛型，自动推导响应类型）
 *     │
 *     ▼
 * postImpl() / getImpl() / putImpl()  ← 内部实现（处理 body 模式、认证、baseUrl）
 *     │
 *     ▼
 * handleResponse()                ← 统一响应处理（JSON 解析、错误映射、二进制流）
 *     │
 *     ▼
 * Ktor HttpClient                 ← 底层 HTTP 引擎（各平台 expect/actual）
 * ```
 *
 * ### 响应处理规则
 *
 * 所有请求方法共享 `handleResponse` 的统一处理逻辑：
 *
 * - **JSON 响应** — 自动解包 [ApiResponseEntity]，提取 `data` 字段反序列化为目标类型 `T`。
 *   若 `code != 200`，抛出 [ErrorResponseException]。
 * - **`rawResponse = true`** — 跳过解包，直接返回原始 JSON 反序列化结果。
 * - **`T = Flow<ByteArray>`** — 返回二进制流，用于文件下载等场景。
 * - **`T = Unit`** — 忽略响应体，只返回 `Unit`。
 * - **HTTP 状态码 ≥ 400** — 抛出 [ErrorResponseException]，附带远端错误详情。
 *
 * @see post
 * @see get
 * @see put
 * @see sse
 *
 * @author 洛洛希雅Lolosia
 * @since 2024-12-02 09:51
 */

import moe.lolosia.web.util.bundle.Bundle
import moe.lolosia.web.util.bundle.bundleOf
import moe.lolosia.web.util.bundle.bundleScope
import moe.lolosia.web.util.session.Context
import io.ktor.client.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.client.request.forms.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.http.content.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.utils.io.*
import io.ktor.utils.io.core.toByteArray
import kotlinx.coroutines.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.*
import kotlinx.serialization.*
import kotlinx.serialization.json.*
import kotlinx.serialization.modules.SerializersModule
import moe.lolosia.web.dto.response.AnySerializer
import moe.lolosia.web.util.packageLogger
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import kotlin.reflect.KType
import kotlin.reflect.typeOf
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

// ============================================================
// JSON 配置
// ============================================================

private val json = Json {
    ignoreUnknownKeys = true
    isLenient = true
    encodeDefaults = true
    serializersModule = SerializersModule {
        contextual(Any::class, AnySerializer)
    }
}

private val logger = packageLogger("moe.lolosia.web.api.PackageKt")

// ============================================================
// HttpClient 工厂
// ============================================================

/**
 * 创建并返回一个预配置的 [HttpClient] 实例，用于发起 HTTP 请求。
 *
 * 该客户端已内置以下配置：
 * - [ContentNegotiation] — 使用 [kotlinx.serialization.json.Json] 进行 JSON 序列化/反序列化，
 *   配置了 `ignoreUnknownKeys = true`、`isLenient = true` 以及 `encodeDefaults = true`。
 * - [HttpTimeout] — 默认请求超时 14 秒，可由各请求方法上的 `timeout` 参数覆盖。
 *
 * 各平台（Android / iOS / JVM）通过 `expect`/`actual` 机制提供对应的 Ktor 引擎，
 * 因此调用方无需关心底层引擎选择。
 *
 * ### 使用示例
 * ```kotlin
 * val client = createHttpClient()
 * val result: MyData = post(client = client, url = "/api/data")
 * ```
 *
 * @see post
 * @see get
 * @see put
 */
fun createHttpClient(): HttpClient {
    return HttpClient {
        install(ContentNegotiation) {
            json(json)
        }
        install(HttpTimeout) {
            requestTimeoutMillis = 14_000
        }
        // 开发环境可启用日志
        // install(Logging)
    }
}

// ============================================================
// 日志工具
// ============================================================

private fun String.s200() = if (length > 200) slice(0..200) + "...(${length - 200} more)" else this

private fun Any?.s200(): String = try {
    if (this is Flow<*>) "<flow>"
    else json.encodeToString(JsonElement.serializer(), this.toJsonElement()).s200()
} catch (e: Throwable) {
    "<serialization failed>"
}

// ============================================================
// Bundle → JsonElement 转换
// ============================================================

private fun Any?.toJsonElement(): JsonElement = when (this) {
    null -> JsonNull
    is JsonElement -> this
    is Boolean -> JsonPrimitive(this)
    is Number -> JsonPrimitive(this)
    is String -> JsonPrimitive(this)
    is Map<*, *> -> buildJsonObject {
        this@toJsonElement.forEach { (k, v) -> put(k.toString(), v.toJsonElement()) }
    }

    is Iterable<*> -> buildJsonArray {
        this@toJsonElement.forEach { add(it.toJsonElement()) }
    }

    is Array<*> -> buildJsonArray {
        this@toJsonElement.forEach { add(it.toJsonElement()) }
    }

    else -> JsonPrimitive(this.toString())
}

// ============================================================
// 异常定义
// ============================================================

/**
 * 表示远端服务返回了业务层面的错误响应（HTTP 状态码 ≥ 400）。
 *
 * 当远端返回 JSON 格式的错误体时，会自动解析其中的 `msg` 字段作为异常消息，
 * 并将整个响应体反序列化为 [bundle] 以便调用方获取更多错误细节。
 *
 * @property statusCode 远端返回的 HTTP 状态码
 * @property message 人类可读的错误描述，来源于远端响应的 `msg` 字段
 * @property bundle 远端响应体的完整内容（已解析为键值对），可能为 `null`
 * @property cause 原始的 [ClientRequestException] 或其他底层异常
 *
 * @see ClientRequestException
 */

class ErrorResponseException(
    val statusCode: HttpStatusCode,
    override val message: String,
    val bundle: Bundle? = null,
    override val cause: Throwable? = null
) : RuntimeException(message, cause)

// ============================================================
// 数据模型
// ============================================================

/**
 * 统一的 API 响应包装体，对应后端返回的 JSON 结构。
 *
 * 典型的后端响应格式为：
 * ```json
 * { "data": {}, "code": 200, "msg": "ok" }
 * ```
 *
 * 当 [code] 不为 `200` 时，[post] 和 [get] 等方法会自动抛出 [ErrorResponseException]。
 *
 * @param T 业务数据的类型
 * @property data 业务数据载荷，可能为 `null`
 * @property code 业务状态码，`200` 表示成功
 * @property msg 服务端返回的消息文本，JSON 字段名为 `msg`
 */
@Serializable
data class ApiResponseEntity<T>(
    val data: T? = null,
    val code: Int = 200,
    @SerialName("msg")
    val msg: String = ""
)

/**
 * SSE（Server-Sent Events）连接的结果封装。
 *
 * 通过 [sse] 函数建立 SSE 连接后返回此对象，包含：
 * - [flow] — 一个冷流，逐条发射接收到的 SSE 事件（`event` → `data` 键值对）。
 * - [id] — 本次 SSE 会话的唯一标识，用于后续连接恢复。
 * - [job] — 与该 SSE 会话绑定的协程 [Job]，可用于取消连接。
 *
 * ### 典型用法
 * ```kotlin
 * val result = sse(baseUrl, "/events")
 * result.flow.collect { (event, data) ->
 *     println("[$event] $data")
 * }
 * // 需要时取消
 * result.job.cancel()
 * ```
 *
 * @property flow SSE 事件流，每个元素为 `Pair<事件名, 数据>`
 * @property id 会话标识，`raw = true` 时为 `null`
 * @property job 与 SSE 会话绑定的协程 Job
 *
 * @see sse
 */

data class SSEResult(
    val flow: Flow<Pair<String, Any?>>,
    val id: String?,
    val job: Job
)

/**
 * 表单数据体，传入 [post] 或 [put] 的 `body` 参数时将以 `multipart/form-data` 编码发送，
 * 用于上传文件、图片等二进制内容。
 *
 * 使用 [formData] 构建器来构造 [parts] 列表，每个 [PartData] 可以是一个普通的表单字段
 * 或一个带文件名的二进制附件。
 *
 * ### 使用示例
 * ```kotlin
 * // 上传图片
 * post<UploadResult>(url = "/upload", body = formBody {
 *     append("name", "avatar")
 *     append("file", imageBytes, Headers.build {
 *         append(HttpHeaders.ContentDisposition, "filename=\"photo.jpg\"")
 *         append(HttpHeaders.ContentType, "image/jpeg")
 *     })
 * })
 * ```
 *
 * @property parts 由 [formData] 构建器生成的 [PartData] 列表
 *
 * @see formData
 * @see MultiPartFormDataContent
 */
class FormDataBody(val parts: List<PartData>)
inline fun formBody(block: FormBuilder.() -> Unit) = FormDataBody(formData(block))

// ============================================================
// 请求前/后日志
// ============================================================

private fun beforeWebRequest(body: Any?, baseUrl: String, url: String) {
    if (url.contains("/debug/log")) return
    if (body is FormDataBody) {
        logger.info("[HTTP] REQ: $baseUrl$url - <form-data>")
    } else {
        logger.info("[HTTP] REQ: $baseUrl$url - ${body.s200()}")
    }
}

private fun afterWebRequest(map: Any?, baseUrl: String, url: String) {
    if (url.contains("/debug/log")) return
    logger.info("[HTTP] OK: $baseUrl$url - 200 - ${map.s200()}")
}

private suspend fun catchWebRequestException(e: Throwable, baseUrl: String, url: String) {
    if (url.contains("/debug/log")) return
    if (e is ClientRequestException) {
        val status = e.response.status
        val respBody = try {
            e.response.bodyAsText().s200()
        } catch (_: Throwable) {
            "<unreadable>"
        }
        logger.info("[HTTP] ERR: $baseUrl$url - $status - $respBody")

        val contentType = e.response.contentType()
        if (contentType?.match(ContentType.Application.Json) == true) {
            try {
                val element = json.parseToJsonElement(e.response.bodyAsText())
                val bundle = jsonElementToBundle(element)
                throw ErrorResponseException(
                    HttpStatusCode.BadGateway,
                    bundle["msg"]?.toString() ?: e.message ?: status.description,
                    bundle,
                    e
                )
            } catch (_: Throwable) {
                // 不是预期的 JSON 格式，继续抛出原始异常
            }
        }
        return
    }
    logger.info("[HTTP] ERR: $baseUrl$url - ${e::class.simpleName}: ${e.message}")
}

// ============================================================
// JSON → Bundle 反序列化
// ============================================================
@Suppress("UNCHECKED_CAST")
private fun jsonElementToBundle(element: JsonElement): Bundle {
    return when (element) {
        is JsonObject -> {
            val map = mutableMapOf<String, Any?>()
            element.forEach { (k, v) -> map[k] = jsonElementToAny(v) }
            map
        }

        else -> bundleOf()
    }
}

private fun jsonElementToAny(element: JsonElement): Any? = when (element) {
    is JsonNull -> null
    is JsonPrimitive -> {
        when {
            element.isString -> element.content
            element.content == "true" -> true
            element.content == "false" -> false
            else -> element.content.toDoubleOrNull() ?: element.content
        }
    }

    is JsonObject -> jsonElementToBundle(element)
    is JsonArray -> element.map { jsonElementToAny(it) }
}

// ============================================================
// 核心请求方法
// ============================================================

/**
 * 向远端服务发起 `POST` 请求，自动处理认证、序列化与错误解析。
 *
 * 这是最常用的请求方法，支持两种 body 模式：
 * 1. **JSON 模式**（默认） — 当 `body` 为 [Bundle] 或任意可序列化对象时，
 *    以 `application/json` 编码发送。若 `raw = false`，还会自动包裹一层
 *    `{ "key": ..., "apiBody": ... }` 的外壳。
 * 2. **Multipart 模式** — 当 `body` 为 [FormDataBody] 时，以 `multipart/form-data`
 *    编码发送，适用于文件上传。此模式下 `raw` 参数不生效。
 *
 * 响应处理规则：
 * - 若响应为 JSON，自动解析 [ApiResponseEntity] 并提取 `data` 字段反序列化为 `T`。
 *   若 `code != 200`，抛出 [ErrorResponseException]。
 * - 若 `rawResponse = true`，返回原始响应 JSON 反序列化为 `T`，不提取 `data`。
 * - 若 `T` 为 [Flow]<[ByteArray]>，返回二进制流用于文件下载等场景。
 * - 若 `T` 为 [Unit]，忽略响应体只返回 `Unit`。
 * - 若 HTTP 状态码 ≥ 400，抛出 [ErrorResponseException] 并提供远端错误详情。
 *
 * `baseUrl` 可从 [ctx] 中自动获取，无需手动传入。
 *
 * ### 使用示例
 * ```kotlin
 * // 简单 JSON POST
 * val user: SysUser = post(ctx, "/user/myInfo")
 *
 * // 带参数
 * val result: Bundle = post(ctx, "/user/list", bundleOf("pageNo" to 1, "pageSize" to 20))
 *
 * // 上传文件
 * val resp: UploadResult = post(ctx, "/upload", body = FormDataBody(formData {
 *     append("file", bytes, Headers.build {
 *         append(HttpHeaders.ContentDisposition, "filename=\"doc.pdf\"")
 *         append(HttpHeaders.ContentType, "application/pdf")
 *     })
 * }))
 * ```
 *
 * @param T 期望的响应类型，由 `reified` 关键字自动推导
 * @param ctx 会话上下文，`sessionId` 的默认值
 * @param url 请求路径（相对于 `baseUrl`），例如 `"/user/myInfo"`
 * @param body 请求体，[Bundle] 时以 JSON 发送，[FormDataBody] 时以 multipart 发送
 * @param key 远端接口验证密钥，仅 `raw = false` 时生效
 * @param baseUrl 服务根地址，默认从 [ctx] 获取
 * @param raw 为 `true` 时直接发送 `body`，为 `false` 时包裹 `{ key, apiBody }` 外壳
 * @param rawResponse 为 `true` 时返回原始响应 JSON，不提取 `data` 字段
 * @param sessionId 认证令牌，默认从 [ctx] 获取
 * @param timeout 请求超时时间，默认 14 秒
 * @param headers 额外的 HTTP 请求头
 * @param noLog 为 `true` 时禁止打印请求/响应日志
 * @param client 可复用的 [HttpClient] 实例，默认由 [createHttpClient] 创建
 * @return 反序列化后的响应体，类型为 `T`
 *
 * @throws ErrorResponseException 当远端返回的业务状态码不为 200 时
 * @throws NoSuchElementException 当 `baseUrl` 未配置时
 * @throws TimeoutCancellationException 当请求超时时
 *
 * @see get
 * @see put
 * @see FormDataBody
 */
suspend inline fun <reified T> post(
    baseUrl: String? = null,
    url: String,
    body: Any? = bundleOf(),
    key: String? = null,
    raw: Boolean = true,
    rawResponse: Boolean = false,
    ctx: Context? = null,
    sessionId: String? = null,
    timeout: Duration = 14.seconds,
    headers: Headers = Headers.Empty,
    noLog: Boolean = false,
    client: HttpClient = createHttpClient()
): T = postImpl(
    client, ctx, url, body, key, baseUrl, raw, rawResponse, sessionId, headers, timeout, noLog, typeOf<T>()
)

@PublishedApi
@Suppress("UNCHECKED_CAST")
internal suspend fun <T> postImpl(
    client: HttpClient,
    ctx: Context? = null,
    url: String,
    body: Any? = bundleOf(),
    key: String? = null,
    baseUrl: String? = null,
    raw: Boolean = true,
    rawResponse: Boolean = false,
    sessionId: String? = null,
    headers: Headers = Headers.Empty,
    timeout: Duration = 14.seconds,
    noLog: Boolean = false,
    kType: KType
): T {
    val isFormData = body is FormDataBody
    val body1 = when {
        isFormData -> body // FormDataBody 直接透传，交由 handleResponse 中的 requestBuilder 处理
        raw -> body ?: bundleOf()
        else -> bundleScope {
            "key" set key
            "apiBody" set body
        }
    }
    if (baseUrl.isNullOrEmpty()) {
        throw NoSuchElementException("中央服务器未配置")
    }
    val sessionId = sessionId ?: ctx?.sessionId

    return handleResponse(client, url, body, baseUrl, rawResponse, timeout, noLog, kType) {
        client.post("$baseUrl$url") {
            if (isFormData) {
                val formBody = body as FormDataBody
                setBody(MultiPartFormDataContent(formBody.parts))
            } else {
                contentType(ContentType.Application.Json)
                setBody(body1)
            }
            headers.forEach { name, values -> values.forEach { header(name, it) } }
            sessionId?.let { header(HttpHeaders.Authorization, "Bearer $it") }
            onTimeout(timeout)
        }
    }
}

/**
 * 向远端服务发起 `PUT` 请求，行为与 [post] 类似，支持 JSON 和 [FormDataBody] 两种模式。
 *
 * 主要用于资源更新场景，例如上传头像：
 * ```kotlin
 * val result: Bundle = put(ctx, "/user/avatar?id=$userId", body = FormDataBody(formData {
 *     append("file", imageBytes, Headers.build {
 *         append(HttpHeaders.ContentDisposition, "filename=\"avatar.jpg\"")
 *         append(HttpHeaders.ContentType, "image/jpeg")
 *     })
 * }))
 * ```
 *
 * @param T 期望的响应类型
 * @param ctx 会话上下文，提供`sessionId` 的默认值
 * @param url 请求路径
 * @param body 请求体，[Bundle] 时以 JSON 发送，[FormDataBody] 时以 multipart 发送
 * @param baseUrl 服务根地址，默认从 [ctx] 获取
 * @param sessionId 认证令牌，默认从 [ctx] 获取
 * @param timeout 请求超时时间，默认 14 秒
 * @param headers 额外的 HTTP 请求头
 * @param noLog 为 `true` 时禁止打印请求/响应日志
 * @param client 可复用的 [HttpClient] 实例
 *
 * @throws ErrorResponseException 当远端返回的业务状态码不为 200 时
 * @throws NoSuchElementException 当 `baseUrl` 未配置时
 *
 * @see post
 * @see get
 * @see FormDataBody
 */
suspend inline fun <reified T> put(
    baseUrl: String? = null,
    url: String,
    body: Any? = bundleOf(),
    ctx: Context? = null,
    sessionId: String? = null,
    timeout: Duration = 14.seconds,
    headers: Headers = Headers.Empty,
    noLog: Boolean = false,
    client: HttpClient = createHttpClient()
): T = putImpl(client, ctx, url, body, baseUrl, sessionId, headers, timeout, noLog, typeOf<T>())

@PublishedApi
@Suppress("UNCHECKED_CAST")
internal suspend fun <T> putImpl(
    client: HttpClient,
    ctx: Context? = null,
    url: String,
    body: Any? = bundleOf(),
    baseUrl: String? = null,
    sessionId: String? = null,
    headers: Headers = Headers.Empty,
    timeout: Duration = 14.seconds,
    noLog: Boolean = false,
    kType: KType
): T {
    val isFormData = body is FormDataBody
    if (baseUrl.isNullOrEmpty()) {
        throw NoSuchElementException("中央服务器未配置")
    }
    val sessionId = sessionId ?: ctx?.sessionId

    return handleResponse(client, url, body, baseUrl, true, timeout, noLog, kType) {
        client.put("$baseUrl$url") {
            if (isFormData) {
                val formBody = body as FormDataBody
                setBody(MultiPartFormDataContent(formBody.parts))
            } else {
                contentType(ContentType.Application.Json)
                setBody(body)
            }
            headers.forEach { name, values -> values.forEach { header(name, it) } }
            sessionId?.let { header(HttpHeaders.Authorization, "Bearer $it") }
            onTimeout(timeout)
        }
    }
}

/**
 * 向远端服务发起 `GET` 请求，自动处理认证、序列化与错误解析。
 *
 * 与 [post] 共享相同的响应处理逻辑：
 * - JSON 响应自动解析 [ApiResponseEntity] 并提取 `data`。
 * - 若 `T` 为 [Flow]<[ByteArray]>，返回二进制流。
 * - 若 `T` 为 [Unit]，忽略响应体。
 *
 * ### 使用示例
 * ```kotlin
 * // 带查询参数
 * val user: SysUser = get(ctx, "/user/detail", query = bundleOf("id" to userId))
 *
 * // 下载二进制文件
 * val imageFlow: Flow<ByteArray> = get(ctx, "/user/avatar", query = bundleOf("id" to userId))
 * ```
 *
 * @param T 期望的响应类型
 * @param ctx 会话上下文
 * @param url 请求路径
 * @param query 查询参数，键值对形式
 * @param baseUrl 服务根地址，默认从 [ctx] 获取
 * @param headers 额外的 HTTP 请求头
 * @param timeout 请求超时时间，默认 14 秒
 * @param noLog 为 `true` 时禁止打印请求/响应日志
 * @param client 可复用的 [HttpClient] 实例
 *
 * @throws ErrorResponseException 当远端返回的业务状态码不为 200 时
 * @throws NoSuchElementException 当 `baseUrl` 未配置时
 *
 * @see post
 * @see put
 */
suspend inline fun <reified T> get(
    baseUrl: String? = null,
    url: String,
    query: Bundle? = null,
    ctx: Context? = null,
    headers: Headers = Headers.Empty,
    timeout: Duration = 14.seconds,
    noLog: Boolean = false,
    client: HttpClient = createHttpClient(),
): T = getImpl(client, ctx, url, query, baseUrl, headers, timeout, noLog, typeOf<T>())

@PublishedApi
@Suppress("UNCHECKED_CAST")
internal suspend fun <T> getImpl(
    client: HttpClient,
    ctx: Context? = null,
    url: String,
    query: Bundle? = null,
    baseUrl: String? = null,
    headers: Headers = Headers.Empty,
    timeout: Duration = 14.seconds,
    noLog: Boolean = false,
    kType: KType,
): T {
    if (baseUrl.isNullOrEmpty()) {
        throw NoSuchElementException("中央服务器未配置")
    }

    return handleResponse(client, url, query, baseUrl, true, timeout, noLog, kType) {
        client.get("$baseUrl$url") {
            headers.forEach { name, values -> values.forEach { header(name, it) } }
            query?.forEach { (k, v) ->
                when (v) {
                    is Iterable<*> -> v.forEach { parameter(k, it.toString()) }
                    else -> parameter(k, v.toString())
                }
            }
            onTimeout(timeout)
        }
    }
}

// ============================================================
// 响应处理
// ============================================================

@Suppress("UNCHECKED_CAST")
private suspend fun <T> handleResponse(
    client: HttpClient,
    url: String,
    body: Any?,
    baseUrl: String,
    rawResponse: Boolean,
    timeout: Duration,
    noLog: Boolean = false,
    kType: KType,
    requestBuilder: suspend HttpClient.() -> HttpResponse,
): T {
    if (!noLog) beforeWebRequest(body, baseUrl, url)

    return try {
        withTimeout(timeout) {
            val response = client.requestBuilder()
            val status = response.status

            if (status.value >= 400) {
                throw ClientRequestException(response, response.bodyAsText())
            }

            val kClazz = kType.classifier

            if (kClazz == Unit::class) {
                if (!noLog) afterWebRequest("OK", baseUrl, url)
                return@withTimeout Unit as T
            }

            val contentType = response.contentType()
            val isJson = contentType?.match(ContentType.Application.Json) == true
            val serializer = json.serializersModule.serializer(kType) as KSerializer<T>

            if (isJson) {
                val text = response.bodyAsText()

                if (text.isBlank()) {
                    if (!noLog) afterWebRequest(text, baseUrl, url)

                    // TODO nullable check!
                    @Suppress("UNCHECKED_CAST")
                    return@withTimeout null as T
                }

                val apiResp = json.decodeFromString<ApiResponseEntity<Any?>>(text)
                if (apiResp.code != 200) {
                    throw ErrorResponseException(HttpStatusCode.BadGateway, apiResp.msg)
                }

                if (rawResponse) {
                    val result = json.decodeFromString(serializer, text)
                    if (!noLog) afterWebRequest(text, baseUrl, url)
                    result
                } else {
                    val jsonData = json.encodeToString(apiResp.data)
                    val result = json.decodeFromString(serializer, jsonData)
                    if (!noLog) afterWebRequest(text, baseUrl, url)
                    result
                }
            } else if (kClazz == Flow::class) {
                // 二进制流：返回 Flow<ByteArray>
                val channel: ByteReadChannel = response.bodyAsChannel()
                val flow = channelFlow {
                    val buffer = ByteArray(4096)
                    while (!channel.isClosedForRead) {
                        val count = channel.readAvailable(buffer)
                        if (count <= 0) break
                        send(buffer.copyOf(count))
                    }
                }
                if (!noLog) afterWebRequest("<flow>", baseUrl, url)
                flow as T
            } else {
                val text = response.bodyAsText()
                val result = json.decodeFromString(serializer, text)
                if (!noLog) afterWebRequest(result, baseUrl, url)
                result
            }
        }
    } catch (e: TimeoutCancellationException) {
        if (noLog) beforeWebRequest(body, baseUrl, url)
        catchWebRequestException(e, baseUrl, url)
        throw e
    } catch (e: Throwable) {
        if (noLog) beforeWebRequest(body, baseUrl, url)
        catchWebRequestException(e, baseUrl, url)
        throw e
    }
}

// ============================================================
// SSE (Server-Sent Events)
// ============================================================

/**
 * 与远端服务建立 [Server-Sent Events](https://developer.mozilla.org/en-US/docs/Web/API/Server-sent_events) 连接，
 * 返回一个实时事件流。
 *
 * 工作流程：
 * 1. 当 `raw = false`（默认）时，先以 `POST` 请求注册 SSE 会话，获取 `eventSourceId`。
 * 2. 然后以 `GET` 请求建立长连接，持续读取 `text/event-stream` 格式的数据。
 * 3. 解析出的每个 SSE 事件作为一个 `Pair<事件名, 数据>` 发送到返回的 [SSEResult.flow] 中。
 * 4. 当远端发送 `event: close` 事件时，流自动结束。
 *
 * ### 使用示例
 * ```kotlin
 * val result = sse(baseUrl, "/chat/stream", body = bundleOf("message" to "hello"))
 * result.flow.collect { (event, data) ->
 *     when (event) {
 *         "message" -> appendToChat(data.toString())
 *         "error"   -> showError(data.toString())
 *     }
 * }
 * ```
 *
 * @param baseUrl 服务根地址
 * @param url 请求路径
 * @param body 注册 SSE 时携带的请求体，`raw = false` 时会包裹 `{ key, apiBody }` 外壳
 * @param raw 为 `true` 时跳过注册步骤，直接建立 SSE 连接
 * @param sessionId 认证令牌
 * @param noLog 为 `true` 时禁止打印请求/响应日志
 * @param client 可复用的 [HttpClient] 实例
 * @return [SSEResult] 包含事件流、会话 ID 和协程 Job
 *
 * @throws IllegalStateException 当远端返回的不是 SSE 流（例如返回了 JSON）时
 * @throws CancellationException 当远端显式发送 `event: close` 时
 *
 * @see SSEResult
 */
suspend fun sse(
    baseUrl: String,
    url: String,
    body: Any? = bundleOf(),
    raw: Boolean = false,
    sessionId: String? = null,
    noLog: Boolean = false,
    client: HttpClient = createHttpClient(),
): SSEResult {
    var id: String? = null
    if (!raw) {
        id = postImpl(
            client, url = url, body = body, baseUrl = baseUrl,
            sessionId = sessionId,
            headers = Headers.build { append("X-Upgrade", "event-source") },
            noLog = noLog,
            kType = typeOf<String>()
        )
        if (!noLog) logger.info("[SSE] POST /sse - eventSourceId=$id")
    }

    val sseUrl = if (raw) "$baseUrl$url" else "$baseUrl$url?eventSourceId=$id"

    // 使用平台特定的 SSE 连接（JVM/Android 用 HttpURLConnection，iOS 用 Ktor）
    // withContext(IO) 确保阻塞连接在 IO 线程执行，且连接在返回前建立
    val sseConn = withContext(Dispatchers.IO) { createSseConnection(sseUrl, sessionId) }
    if (!noLog) logger.info("[SSE] GET /sse - connected, waiting for events...")

    val flow = sseConn.flow.map { rawEvent ->
        // 解析 SSE 事件字符串（如 "event:message\ndata:{...}"）
        val ev = rawEvent.toByteArray()
        val parsed = processSSEEvent(ev)
        if (parsed != null) {
            if (!noLog) logger.info("[SSE] event - ${parsed.first}")
            parsed
        } else {
            "message" to rawEvent
        }
    }.catch { e ->
        if (!noLog) logger.info("[SSE] STREAM - error: ${e::class.simpleName}: ${e.message}")
        throw e
    }.onCompletion {
        sseConn.close()
        if (!noLog) logger.info("[SSE] STREAM - cleaned up")
    }

    return SSEResult(flow, id, Job())
}

private fun processSSEEvent(ev: ByteArray): Pair<String, Any?>? {
    if (ev.isEmpty() || ev[0] == 58.toByte()) return null // 以冒号开头的数据将被忽略

    var event = "message"
    var data = "null"
    val msg = ev.decodeToString()
    msg.split("\n").forEach {
        if (it.startsWith("event: ")) event = it.substringAfter(' ')
        if (it.startsWith("data: ")) data = it.substringAfter(' ')
    }

    val data1 = try {
        jsonElementToAny(json.parseToJsonElement(data))
    } catch (_: Throwable) {
        data
    }

    if (event == "close") throw CancellationException(data)

    return event to data1
}

fun createSseConnection(url: String, token: String?): SseConnection {
    val conn = (URL(url).openConnection() as HttpURLConnection).apply {
        requestMethod = "GET"
        setRequestProperty("Accept", "text/event-stream")
        setRequestProperty("Cache-Control", "no-cache")
        token?.let { setRequestProperty("Authorization", "Bearer $it") }
        connectTimeout = 10000
        readTimeout = 45000 // 服务端每 5s 发 :ping，45s 未收到任何数据则判定连接已死，触发重连
        doInput = true
    }

    // 触发 HTTP 连接（getInputStream 会发送请求并等待响应头）
    // 这确保 SSE 连接在 createSseConnection 返回前就建立好了
    val inputStream = conn.inputStream

    val flow: Flow<String> = flow {
        val reader = BufferedReader(InputStreamReader(inputStream, Charsets.UTF_8))
        try {
            var line: String?
            val sb = StringBuilder()
            while (true) {
                line = reader.readLine() ?: break
                if (line.isEmpty()) {
                    // 空行表示一个 SSE 事件结束
                    if (sb.isNotEmpty()) {
                        emit(sb.toString())
                        sb.clear()
                    }
                } else if (!line.startsWith(":")) {
                    // 注释行（以 : 开头）忽略
                    if (sb.isNotEmpty()) sb.append("\n")
                    sb.append(line)
                }
            }
        } finally {
            reader.close()
            conn.disconnect()
        }
    }.flowOn(Dispatchers.IO)

    return SseConnection(flow) { conn.disconnect() }
}


// SSE 连接结果
data class SseConnection(
    val flow: Flow<String>,
    val close: () -> Unit
)

// ============================================================
// 重试工具
// ============================================================

/**
 * 为一段代码块提供自动重试能力。
 *
 * 当 [scope] 中的代码抛出异常时，自动捕获并重试，直到成功或达到 [maxRetry] 上限。
 * 重试信息会通过 `println` 输出到控制台。
 *
 * ### 使用示例
 * ```kotlin
 * val retry = RetryScope(maxRetry = 3, warnMessage = "登录失败")
 * val result = retry.scope {
 *     login(username, password)
 * }
 * if (result != null) {
 *     // 登录成功
 * }
 * ```
 *
 * @property maxRetry 最大重试次数，默认 5 次
 * @property warnMessage 重试时输出的警告信息前缀
 */

class RetryScope(
    val maxRetry: Int = 5,
    private val warnMessage: String = "发生未知异常",
) {
    /** 当前已重试的次数 */
    private var retryCount = 0
    /** 最近一次导致重试的异常 */
    private var exception: Throwable? = null

    /**
     * 在重试保护下执行 [block]。
     *
     * - 如果 [block] 成功返回，重置重试计数并返回结果。
     * - 如果 [block] 抛出异常且未超过 [maxRetry]，吞掉异常并返回 `null`。
     * - 如果超过 [maxRetry]，重新抛出该异常。
     *
     * @param block 要执行的代码块
     * @return 成功时返回 `R`，重试中返回 `null`
     */
    inline fun <R> scope(block: () -> R): R? {
        try {
            val rs = block()
            clearCount()
            return rs
        } catch (e: Throwable) {
            handleException(e)
        }
        return null
    }

    /** 重置重试计数和异常状态 */
    fun clearCount() {
        retryCount = 0
        exception = null
    }

    /**
     * 处理 [scope] 中捕获的异常。
     * 若未超过 [maxRetry] 则打印警告并递增计数，否则重新抛出。
     */
    fun handleException(e: Throwable) {
        logger.info("$warnMessage: ${e::class.simpleName}: ${e.message}")
        if (retryCount > maxRetry) throw e
        retryCount++
        exception = e
    }
}

// ============================================================
// 便捷扩展
// ============================================================

/**
 * 为 [HttpRequestBuilder] 设置请求级超时，覆盖 [HttpClient] 的默认配置。
 */
private fun HttpRequestBuilder.onTimeout(timeout: Duration) {
    timeout {
        requestTimeoutMillis = timeout.inWholeMilliseconds
    }
}