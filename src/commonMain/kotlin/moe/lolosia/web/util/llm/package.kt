package moe.lolosia.web.util.llm

import kotlinx.serialization.json.*
import kotlin.io.encoding.*

/** OpenAI 兼容的 chat completions 路径（相对 `LlmSettings.baseUrl`）。 */
internal const val CHAT_COMPLETIONS_PATH: String = "/chat/completions"

/** 流式响应结束哨兵帧。 */
internal const val STREAM_DONE: String = "[DONE]"

/** 解析流式响应体用的共享 Json 实例。 */
internal val chatJson: Json = Json {
    ignoreUnknownKeys = true
    isLenient = true
}

/**
 * 把一个工具定义成 [ChatTool]。
 *
 * [block] 是 `suspend` 的，所以工具内部可以直接做网络 / 数据库 / 其他工具调用，
 * 不需要像 JVM 侧那样用阻塞调度桥接协程。
 */
fun tool(
    name: String,
    description: String,
    parameters: JsonObject,
    block: suspend (arguments: JsonObject) -> String,
): ChatTool = object : ChatTool {
    override val name: String get() = name
    override val description: String get() = description
    override val parameters: JsonObject get() = parameters
    override suspend fun call(arguments: JsonObject): String = block(arguments)
}

/** 把字节与 MIME 编成 OpenAI 多模态要求的 base64 data URL。 */
@OptIn(ExperimentalEncodingApi::class)
fun dataUrl(bytes: ByteArray, mime: String): String =
    "data:$mime;base64,${Base64.encode(bytes)}"

/** 按扩展名推断图片 MIME；未知时回退 `application/octet-stream`。 */
fun mimeOf(path: String): String = when (path.substringAfterLast('.', "").lowercase()) {
    "png" -> "image/png"
    "jpg", "jpeg" -> "image/jpeg"
    "webp" -> "image/webp"
    "gif" -> "image/gif"
    "bmp" -> "image/bmp"
    else -> "application/octet-stream"
}

/** 从 SSE 帧里取 `data:` 行的内容；没有该行时返回 `null`。 */
internal fun sseDataLine(frame: ByteArray): String? =
    frame.decodeToString().lineSequence()
        .firstOrNull { it.startsWith("data:") }
        ?.removePrefix("data:")
        ?.trimStart()
