package moe.lolosia.web.dto.response

/**
 * @author 洛洛希雅Lolosia
 * @since 2026-08-30 15:27
 */

import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.*
import moe.lolosia.web.util.bundle.Bundle
import moe.lolosia.web.util.bundle.bundleOf

/**
 * 任意值序列化器：让 `Bundle`（`Map<String, Any?>`）能被 kotlinx.serialization 序列化。
 * 参考 alumni-link-kmp 的 AnySerializer 实现。
 *
 * 用法：在 ContentNegotiation 的 Json 配置中注册 `contextual(Any::class, AnySerializer)`，
 * 则 `Map<String, Any?>` / `List<Bundle>` 等动态结构即可正常序列化/反序列化。
 *
 * 类型用 `KSerializer<Any>`（而非 `Any?`）：null 由 kotlinx 的 nullable 包装器负责，
 * 因此 [serialize] 实际收到的总是非空值。
 */
object AnySerializer : KSerializer<Any> {
    override val descriptor: SerialDescriptor = buildClassSerialDescriptor("Any")

    override fun serialize(encoder: Encoder, value: Any) {
        (encoder as JsonEncoder).encodeJsonElement(value.toJsonElement())
    }

    override fun deserialize(decoder: Decoder): Any {
        return (decoder as JsonDecoder).decodeJsonElement().jsonElementToAny() ?: ""
    }
}

/** 任意值 → JsonElement（Map/Iterable/Array 递归转换，其余类型转字符串） */
fun Any?.toJsonElement(): JsonElement = when (this) {
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

/** JsonElement → 任意值（JsonObject 转 Bundle，数字统一转 Double） */
fun JsonElement.jsonElementToAny(): Any? = when (this) {
    is JsonNull -> null
    is JsonPrimitive -> when {
        isString -> content
        content == "true" -> true
        content == "false" -> false
        else -> content.toDoubleOrNull() ?: content
    }
    is JsonObject -> jsonElementToBundle(this)
    is JsonArray -> map { it.jsonElementToAny() }
}

@Suppress("UNCHECKED_CAST")
fun JsonElement.jsonElementToBundle(element: JsonElement): Bundle = when (element) {
    is JsonObject -> {
        val map = bundleOf()
        element.forEach { (k, v) -> map[k] = v.jsonElementToAny() }
        map
    }
    else -> bundleOf()
}

/**
 * Bundle → JsonObject。
 * ktorfitx 编译期校验要求返回类型可序列化，`Map<String, Any?>` 过不了；
 * `JsonObject` 是 @Serializable 的，控制器在边界把 Bundle 转成 JsonObject 返回。
 */
fun Bundle.toJsonObject(): JsonObject = buildJsonObject {
    this@toJsonObject.forEach { (k, v) -> put(k, v.toJsonElement()) }
}

fun Iterable<Bundle>.toJsonArray(): JsonArray = buildJsonArray {
    this@toJsonArray.forEach { add(it.toJsonObject()) }
}
