package moe.lolosia.web.util.session

/**
 * 用户Session哈希表
 *
 * @author 洛洛希雅Lolosia
 * @since 2024-12-02 09:51
 */
interface SessionMap : Map<String, Any?> {
    operator fun set(key: String, value: Any?)
    fun remove(key: String): Any?
    operator fun <T> invoke(key: String): T? {
        @Suppress("UNCHECKED_CAST")
        return get(key) as T
    }
}
