package moe.lolosia.web.service.media

import com.charleskorn.kaml.Yaml
import kotlinx.serialization.Serializable
import moe.lolosia.web.util.classpath.Classpath
import moe.lolosia.web.util.classpath.readText
import moe.lolosia.web.util.log.Log

/**
 * 媒体索引的散装常量与资源加载（公共顶层声明，见 AGENTS.md §1.5）。
 *
 * @author 洛洛希雅Lolosia
 */

/** 读标签 / 元数据 / 检索。 */
const val MEDIA_READ: String = "lolosia.media.read"

/** 人工打标 / 改评级。 */
const val MEDIA_WRITE: String = "lolosia.media.write"

/** 词表管理 / 重跑模型。 */
const val MEDIA_ADMIN: String = "lolosia.media.admin"

/** 看 GPS（最敏感的一类元数据，见 `doc/media-index-design.md` §6.4）。 */
const val MEDIA_LOCATION: String = "lolosia.media.location"

/** 直链签名里允许带 `NSFW` 位（§10.4）。 */
const val MEDIA_NSFW: String = "lolosia.files.nsfw"

/** SSE 事件名：单个文件索引 / 打标完成。 */
const val MEDIA_INDEX_EVENT: String = "media:index"

/** 默认规则集在 classpath 里的路径。 */
const val MEDIA_RULES_RESOURCE: String = "media-rules.yaml"

/** 评级来源：属性页勾选。 */
const val SOURCE_MANUAL: String = "manual"

/** 评级 / 标签来源：目录与文件名规则。 */
const val SOURCE_RULE: String = "rule"

/** 标签来源：外发分析的模型。 */
const val SOURCE_MODEL: String = "model"

/** `namespace` 里的元信息命名空间（`meta:explicit` 这类投影标签）。 */
const val NAMESPACE_META: String = "meta"

/**
 * `namespace` 里的**功能开关**命名空间：不描述画面，而是给文件管理器 / 仪表板用的展示标记
 * （目录级的 `feature:日期分组`、文件级的 `feature:喜欢`）。
 */
const val NAMESPACE_FEATURE: String = "feature"

/** 文件级「人工标心」标签名（`feature:喜欢`），仪表板「受欢迎」的一档口径。 */
const val TAG_LIKE: String = "喜欢"

/** 评级投影成标签时的名字（§7.2：列是权威，标签是投影）。 */
const val TAG_EXPLICIT: String = "explicit"

private val log = Log.of("Media")

/** 内嵌资源 `media-rules.yaml` 的根。 */
@Serializable
private data class MediaRulesFile(val rules: List<MediaRule> = emptyList())

/**
 * 加载默认规则集；资源缺失或解析失败时返回空表（只记日志、不阻断启动，与权限注册表同口径）。
 *
 * 库里的 `media_tag_rule` 一条都没有时才用它兜底（见 `doc/media-index-design.md` §4.5）。
 */
fun loadMediaRules(classpath: Classpath): List<MediaRule> {
    val text = classpath.readText(MEDIA_RULES_RESOURCE)
    if (text == null) {
        log.warn { "classpath 里没有 $MEDIA_RULES_RESOURCE，默认规则集为空" }
        return emptyList()
    }
    return try {
        val rules = Yaml.default.decodeFromString(MediaRulesFile.serializer(), text.trimStart('\uFEFF')).rules
        log.info { "$MEDIA_RULES_RESOURCE 已加载：${rules.size} 条规则" }
        rules
    } catch (e: Exception) {
        log.warn { "$MEDIA_RULES_RESOURCE 解析失败，默认规则集为空：${e.message}" }
        emptyList()
    }
}
