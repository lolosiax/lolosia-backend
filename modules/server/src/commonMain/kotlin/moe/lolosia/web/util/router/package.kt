package moe.lolosia.web.util.router

import kotlin.uuid.Uuid

/** 取到值则返回，为 `null` 时抛 [RouterParamException]。 */
fun <T : Any> required(value: T?, name: String): T =
    value ?: throw RouterParamException("缺少必需的参数：$name")

fun asStringOrNull(raw: String?): String? = raw

fun asIntOrNull(raw: String?, name: String): Int? {
    if (raw == null) return null
    return raw.toIntOrNull() ?: throw RouterParamException("参数 $name 不是合法整数：$raw")
}

fun asLongOrNull(raw: String?, name: String): Long? {
    if (raw == null) return null
    return raw.toLongOrNull() ?: throw RouterParamException("参数 $name 不是合法长整数：$raw")
}

fun asDoubleOrNull(raw: String?, name: String): Double? {
    if (raw == null) return null
    return raw.toDoubleOrNull() ?: throw RouterParamException("参数 $name 不是合法小数：$raw")
}

fun asBooleanOrNull(raw: String?, name: String): Boolean? {
    if (raw == null) return null
    return when (raw.lowercase()) {
        "true", "1", "yes", "on" -> true
        "false", "0", "no", "off" -> false
        else -> throw RouterParamException("参数 $name 不是合法布尔值：$raw")
    }
}

fun asUuidOrNull(raw: String?, name: String): Uuid? {
    if (raw == null) return null
    return runCatching { Uuid.parse(raw) }
        .getOrElse { throw RouterParamException("参数 $name 不是合法 UUID：$raw") }
}

inline fun <reified E : Enum<E>> asEnumOrNull(raw: String?, name: String): E? {
    if (raw == null) return null
    return enumValues<E>().firstOrNull { it.name == raw }
        ?: throw RouterParamException(
            "参数 $name 不是合法枚举值：$raw（可选：${enumValues<E>().joinToString(",") { it.name }}）"
        )
}
