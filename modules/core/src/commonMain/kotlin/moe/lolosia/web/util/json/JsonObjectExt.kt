package moe.lolosia.web.util.json

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/** 深合并：两边同为对象时递归合并，否则 [other] 整体覆盖。 */
fun JsonObject.overlay(other: JsonObject?): JsonObject {
    if (other.isNullOrEmpty()) return this
    val result = LinkedHashMap<String, JsonElement>(this)
    for ((key, value) in other) {
        val old = result[key]
        result[key] = if (old is JsonObject && value is JsonObject) old.overlay(value) else value
    }
    return JsonObject(result)
}
