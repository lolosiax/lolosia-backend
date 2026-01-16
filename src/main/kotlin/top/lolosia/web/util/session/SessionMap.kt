package top.lolosia.web.util.session

/**
 * 用户Session哈希表
 * @author 洛洛希雅Lolosia
 * @since 2024-10-26 19:29
 */

interface SessionMap : Map<String, Any?> {
    operator fun set(key: String, value: Any?)
    fun remove(key: String): Any?
    operator fun <T> invoke(key: String): T? {
        @Suppress("UNCHECKED_CAST")
        return get(key) as T
    }
}