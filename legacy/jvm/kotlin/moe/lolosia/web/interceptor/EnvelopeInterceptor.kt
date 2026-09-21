package moe.lolosia.web.interceptor

import io.ktor.http.ContentType
import io.ktor.http.content.TextContent
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.request.path
import io.ktor.server.response.ApplicationSendPipeline
import io.ktor.util.AttributeKey
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import moe.lolosia.web.dto.response.ApiResult

private val envelopeJson = Json { prettyPrint = true }

/**
 * 响应信封中间件：自动把所有 JSON 数据响应包成 `{code, msg, data}`
 * （等价于 Spring 侧 ReturnWebFilter 在洋葱最内层加信封，见 kotlin-spring-backend 的 interceptor 包）。
 *
 * - 仅作用于 `/api/` 开头的路径（与 Spring 一致），非 API 响应不动
 * - [ApiResult]（已包信封，含 StatusPages 的错误响应）→ 跳过，避免双重包裹
 * - [JsonObject]/[JsonArray]/[JsonPrimitive]（各 Controller 返回的裸数据）→ 手动拼信封后按 JSON 字节发出。
 *   不能 `proceedWith(ApiResult)`：ContentNegotiation 会用旧的 typeInfo（原 JsonElement 类型）去序列化，
 *   导致 `ApiResult 无法 cast 为 JsonArray/JsonObject`。
 * - 文件/SSE 流等 OutgoingContent → 不处理
 * - 需要保持原样时给 call 打 [EnvelopeSkipKey]（如 SSE 握手返回 `{data, code}`）
 *
 * 注意：注册必须**先于** ContentNegotiation（configureSerialization），
 * 否则 subject 已被序列化成字节、无法识别 Json 类型。
 *
 * @author 洛洛希雅Lolosia
 * @since 2026-08-30 21:10
 */
fun Application.installEnvelopeInterceptor() {
    sendPipeline.intercept(ApplicationSendPipeline.Transform) { subject ->
        if (!call.request.path().startsWith("/api/") || call.attributes.contains(EnvelopeSkipKey)) {
            proceed()
            return@intercept
        }
        when (subject) {
            is ApiResult<*> -> proceed() // 已包信封（含 StatusPages 错误响应）
            is JsonObject, is JsonArray, is JsonPrimitive -> proceedWith(
                TextContent(
                    envelopeJson.encodeToString(
                        JsonObject.serializer(),
                        JsonObject(
                            mapOf(
                                "code" to JsonPrimitive(200),
                                "msg" to JsonPrimitive("success"),
                                "data" to subject,
                            )
                        )
                    ),
                    ContentType.Application.Json,
                )
            )
            else -> proceed()
        }
    }
}

/** 该请求的响应不自动加信封（如 SSE 握手返回 {data, code}） */
val EnvelopeSkipKey = AttributeKey<Unit>("envelope-skip")
