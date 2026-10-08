package moe.lolosia.web.service.media

import kotlinx.serialization.Serializable
import moe.lolosia.web.service.files.extensionOf

/**
 * 一条「目录 / 文件名 → 标签或评级」的规则（见 `doc/media-index-design.md` §7.4）。
 *
 * 同一个形状有两个来源：内嵌资源 `media-rules.yaml`（[tag] 直接写 `"命名空间:名字"`）与
 * 库表 `media_tag_rule`（`tagId` 在服务层翻译成同样的 `"命名空间:名字"`）。
 *
 * [tag] 与 [rating] 至少填一个；[priority] 小者优先，只用于评级冲突时定胜负。
 *
 * @author 洛洛希雅Lolosia
 */
@Serializable
data class MediaRule(
    /** [MATCH_PATH_PREFIX] / [MATCH_PATH_CONTAINS] / [MATCH_NAME_GLOB] / [MATCH_EXT]。 */
    val match: String,
    val pattern: String,
    /** `"命名空间:名字"`；标签不存在时按需创建。 */
    val tag: String? = null,
    /** `unknown` / `safe` / `questionable` / `explicit`。 */
    val rating: String? = null,
    val priority: Int = 100,
) {

    /** `name_glob` 编译一次就够 —— 规则集会在整轮扫描里被上万个文件名反复匹配。 */
    private val glob: Regex by lazy { globToRegex(pattern) }

    /** 这条规则是否命中 [path]（相对浏览根）与 [name]。 */
    fun hits(path: String, name: String): Boolean = when (match) {
        MATCH_PATH_PREFIX -> path.startsWith(pattern, ignoreCase = true)
        MATCH_PATH_CONTAINS -> path.contains(pattern, ignoreCase = true)
        MATCH_NAME_GLOB -> glob.matches(name)
        MATCH_EXT -> extensionOf(name) == pattern.removePrefix(".").lowercase()
        else -> false
    }

    /** 把 [tag] 拆成「命名空间 + 名字」；格式不对（没有 `:`）返回 `null`。 */
    fun tagParts(): Pair<String, String>? {
        val text = tag?.trim().orEmpty()
        val at = text.indexOf(':')
        if (at <= 0 || at == text.lastIndex) return null
        val namespace = text.substring(0, at).trim()
        val name = text.substring(at + 1).trim()
        return if (namespace.isEmpty() || name.isEmpty()) null else namespace to name
    }

    companion object {
        const val MATCH_PATH_PREFIX: String = "path_prefix"
        const val MATCH_PATH_CONTAINS: String = "path_contains"
        const val MATCH_NAME_GLOB: String = "name_glob"
        const val MATCH_EXT: String = "ext"
    }
}

/** 把只有 `*` / `?` 的 glob 编译成大小写不敏感的正则。 */
internal fun globToRegex(pattern: String): Regex {
    val text = StringBuilder()
    for (ch in pattern) {
        when (ch) {
            '*' -> text.append(".*")
            '?' -> text.append('.')
            else -> text.append(Regex.escape(ch.toString()))
        }
    }
    return Regex(text.toString(), RegexOption.IGNORE_CASE)
}
