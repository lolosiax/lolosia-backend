package moe.lolosia.web.service.media.worker

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import moe.lolosia.web.settings.AppSettings
import moe.lolosia.web.util.llm.ChatClient
import moe.lolosia.web.util.llm.ChatUsage
import moe.lolosia.web.util.log.Log

/**
 * DeepSeek 视觉打标实现（阶段 A：构图 / 有效性 / 题材 / 属性 / 一句话描述）。
 *
 * 传输层复用原生 [ChatClient]：**一批图片一请求**（摊薄系统提示），图片走内联 base64
 * （调用方已在本地降采样，见 `MediaAnalyzeService`），`detail` 取配置（默认 `low`）。
 * 强制 JSON 输出并只解析结构化字段 —— 模型写自然语言解释是输出 token 黑洞。
 *
 * 本类不做闸门 / 落库 / 台账：那些都在 `MediaAnalyzeService` 统一处理。
 *
 * @author 洛洛希雅Lolosia
 */
class DeepSeekVisionProvider(
    private val client: ChatClient,
    private val config: AppSettings.FilesConfig.MediaConfig.VisionConfig,
) : TaggingProvider {

    private val log = Log.of("Vision")
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** provider id：`<模型名>@vision`，落 `media_asset.analyze_provider`。 */
    override val id: String = "${client.settings.model}@vision"

    /** DeepSeek 单请求最多 600 张；再受配置的批量上限约束。 */
    override val maxBatch: Int = config.batchSize.coerceIn(1, MAX_IMAGES_PER_REQUEST)

    override suspend fun tag(items: List<TaggingProvider.Item>): TaggingProvider.Output {
        if (items.isEmpty()) return TaggingProvider.Output(emptyList())
        val a = config.analyze
        var usage: ChatUsage? = null
        val spec = client.prompt()
            .system(VisionPrompts.system(a.composition, a.validity, a.subject, a.caption, a.attributes))
            .user(VisionPrompts.user(items.size))
        items.forEachIndexed { index, item ->
            // 图前先给路径与已有分析结果：模型据此决定沿用 / 增补标签、改写描述
            spec.user(VisionPrompts.image(index, item.path, item.tags, item.caption, item.folderRemark))
            spec.image(item.bytes, item.mime, config.detail.ifBlank { "low" })
        }
        spec.onUsage { usage = it }
        val text = spec.text()
        val results = parse(text, items.size)
        return TaggingProvider.Output(results, usage)
    }

    /** 解析模型返回的 JSON 数组，按 `i` 归位；缺项与解析失败都补空结果，不抛异常。 */
    private fun parse(text: String, expected: Int): List<TaggingProvider.Result> {
        val array = extractArray(text)
        if (array == null) {
            log.warn { "视觉模型返回无法解析为 JSON 数组：${text.take(200)}" }
            return List(expected) { TaggingProvider.Result() }
        }
        val byIndex = HashMap<Int, TaggingProvider.Result>(expected)
        var fallback = 0
        for (element in array) {
            val obj = element as? JsonObject ?: continue
            val index = obj["i"].primitiveText()?.toIntOrNull() ?: fallback
            byIndex[index] = toResult(obj)
            fallback = index + 1
        }
        return (0 until expected).map { byIndex[it] ?: TaggingProvider.Result() }
    }

    private fun toResult(obj: JsonObject): TaggingProvider.Result = TaggingProvider.Result(
        count = obj["c"].primitiveText(),
        valid = obj["v"].primitiveText()?.let(::asBoolean),
        subject = obj["s"].primitiveText(),
        caption = obj["d"].primitiveText()?.take(80),
        attributes = obj["a"].stringList(),
    )

    private fun asBoolean(text: String): Boolean? = when (text.lowercase()) {
        "true", "yes", "1", "是", "有效" -> true
        "false", "no", "0", "否", "无效" -> false
        else -> null
    }

    /** 去掉 ```json 围栏并截出最外层数组。 */
    private fun extractArray(text: String): JsonArray? = runCatching {
        val trimmed = text.trim()
        val start = trimmed.indexOf('[')
        val end = trimmed.lastIndexOf(']')
        if (start < 0 || end <= start) return@runCatching null
        val element = json.parseToJsonElement(trimmed.substring(start, end + 1))
        element as? JsonArray
    }.onFailure { log.warn(it) { "解析视觉模型输出失败" } }.getOrNull()

    companion object {
        private const val MAX_IMAGES_PER_REQUEST: Int = 600
    }
}

/** 取基元的文本内容；`null` 与空串都归 `null`。 */
private fun JsonElement?.primitiveText(): String? {
    val prim = this as? JsonPrimitive ?: return null
    if (prim is JsonNull) return null
    return prim.content.trim().ifEmpty { null }
}

/** 字符串数组字段 → 去空、限长的字符串列表。 */
private fun JsonElement?.stringList(): List<String> {
    val array = this as? JsonArray ?: return emptyList()
    return array.mapNotNull { it.primitiveText() }
        .filter { it.length <= 24 }
        .distinct()
        .take(12)
}
