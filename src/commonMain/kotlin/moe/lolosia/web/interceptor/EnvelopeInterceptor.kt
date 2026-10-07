package moe.lolosia.web.interceptor

import io.ktor.http.*
import io.ktor.http.content.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** 信封的解析与编码共用一套 Json：紧凑输出，与 `ContentNegotiation` 里那套保持形状一致。 */
private val json = Json

/** 信封的三个键；顶层同时出现即视为已带信封。 */
private val ENVELOPE_KEYS = setOf("code", "msg", "data")

/**
 * 末层：把 `/api/` 下各接口的 JSON 响应包成 `{code, msg, data}`。
 *
 * 判定完全基于响应体的 MIME 类型，因此**必须注册在 `install(ContentNegotiation)` 之后**：
 * 内容协商负责把控制器返回的对象（`@Serializable` 数据类、`JsonElement`…）转成带
 * `Content-Type` 的 `OutgoingContent`，本层才判断得出它最终以什么类型发出；
 * 注册在它之前只能看到原始对象，无从判定。
 *
 * - `application/json` / `text/json` / `*+json` → 包装；
 * - `text/plain`（控制器直接返回裸 `String` 即属此类）→ 原样放行，故要输出 JSON 文本又需要信封时，
 *   应返回 `moe.lolosia.web.dto.RawJson`；
 * - 顶层已含 [ENVELOPE_KEYS] 的响应（`ApiResult`、StatusPages 生成的错误响应）→ 跳过，避免双重包裹；
 * - 无法同步读取的内容（`WriteChannelContent`，如文件下载、SSE 流）与非 JSON 内容 → 不处理；
 * - 需要保持原样的响应（如 SSE 握手）由调用方给 call 打 [EnvelopeSkipKey]；
 * - 只按响应 MIME 判定、**不看 `subject` 的具体类型**：native 上没有通用反射，且 `proceedWith`
 *   会沿用旧的 `typeInfo` 去序列化，按类型判定容易错配（legacy JVM 版那条路在 native 上不可用）。
 *
 * @author 洛洛希雅Lolosia
 */
@Interceptor(-2)
fun Application.installEnvelopeInterceptor() {
    sendPipeline.intercept(ApplicationSendPipeline.Transform) { subject ->
        val path = call.request.path()
        if (!path.startsWith("/api/") || call.attributes.contains(EnvelopeSkipKey)) {
            proceed()
            return@intercept
        }

        val content = subject as? OutgoingContent.ByteArrayContent
        val type = content?.contentType
        if (content == null || type == null || !type.isJson()) {
            proceed()
            return@intercept
        }

        val text = content.bytes().decodeToString()
        if (text.isEnveloped()) {
            proceed()
            return@intercept
        }
        val data = runCatching { json.parseToJsonElement(text) }.getOrNull()
        if (data == null) {
            proceed()
            return@intercept
        }

        proceedWith(TextContent(envelopeOf(data), ContentType.Application.Json))
    }
}

/** 顶层同时带 [ENVELOPE_KEYS] 即认为已经包过信封。 */
private fun String.isEnveloped(): Boolean {
    val obj = runCatching { json.parseToJsonElement(this) as? JsonObject }.getOrNull() ?: return false
    return ENVELOPE_KEYS.all { it in obj }
}

/** 把 [data] 包成 `{code, msg, data}` 的 JSON 文本。 */
private fun envelopeOf(data: JsonElement): String = json.encodeToString(
    JsonObject.serializer(),
    JsonObject(
        mapOf(
            "code" to JsonPrimitive(200),
            "msg" to JsonPrimitive("success"),
            "data" to data,
        )
    ),
)

/** `application/json`、`text/json`、`application/xxx+json` 都算 JSON 响应。 */
private fun ContentType.isJson(): Boolean =
    contentSubtype.equals("json", ignoreCase = true) || contentSubtype.endsWith("+json", ignoreCase = true)
