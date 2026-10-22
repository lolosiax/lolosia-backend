package moe.lolosia.web.service

import kotlinx.serialization.json.*
import moe.lolosia.web.util.time.nowEpochMs

/**
 * 会话数据：一层 `String -> JSON 值` 的映射，序列化后整份存进 `sys_sessions.data`。
 *
 * 内置键见 [KEY_SESSION_ID]、[KEY_USER_ID]、[KEY_LAST_ACCESS]。
 *
 * @author 洛洛希雅Lolosia
 */
class SessionMap internal constructor(
    val id: String,
    private val values: MutableMap<String, JsonElement> = linkedMapOf(),
) {
    val userId: String? get() = getString(KEY_USER_ID)
    val lastAccess: Long get() = getLong(KEY_LAST_ACCESS) ?: 0L

    fun getString(key: String): String? = values[key]?.jsonPrimitive?.contentOrNull

    fun getLong(key: String): Long? = getString(key)?.toLongOrNull()

    fun set(key: String, value: String?): SessionMap = apply {
        if (value == null) values.remove(key) else values[key] = JsonPrimitive(value)
    }

    fun set(key: String, value: Long): SessionMap = apply {
        values[key] = JsonPrimitive(value)
    }

    /** 标记「刚被访问过」，供自动保存与内存回收判断。 */
    internal fun touch(): SessionMap = set(KEY_LAST_ACCESS, nowEpochMs())

    override fun toString(): String = toJson()

    fun toJson(): String = JSON.encodeToString(JsonObject.serializer(), JsonObject(values))

    companion object {
        const val KEY_SESSION_ID: String = "sessionId"
        const val KEY_USER_ID: String = "userId"
        internal const val KEY_LAST_ACCESS: String = "session:lastAccess"

        private val JSON: Json = Json { ignoreUnknownKeys = true }

        /** 从库里的 JSON 还原；解析失败返回 `null`（调用方据此丢弃脏数据）。 */
        fun fromJson(id: String, text: String): SessionMap? = runCatching {
            val obj = JSON.decodeFromJsonElement(JsonObject.serializer(), JSON.parseToJsonElement(text))
            SessionMap(id, LinkedHashMap(obj))
        }.getOrNull()

        /** 新建一份空会话。 */
        fun of(id: String): SessionMap = SessionMap(id).apply {
            set(KEY_SESSION_ID, id)
            touch()
        }
    }
}
