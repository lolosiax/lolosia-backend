package moe.lolosia.web.service.media

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import moe.lolosia.web.util.exif.ExifEntry

/**
 * 探测结果 → 表列的映射（`doc/media-index-design.md` §6）。
 *
 * 绝大多数 EXIF 项没有自己的列，统一折成 `metadata_json` 里的一个扁平对象：
 * 键是 `组.标签名`（如 `exif.LensModel`），值是截断后的文本。扁平的好处是属性页可以直接
 * 按键值对渲染，不必再认识 IFD 结构。
 *
 * @author 洛洛希雅Lolosia
 */
object MediaMetadata {

    /** 落库的项数上限：一张图能有几百项，但属性页看得完的只有前面这些。 */
    private const val MAX_ENTRIES: Int = 128

    /** 单个值的字符上限（有些相机把整段 XML 塞进某个标签里）。 */
    private const val MAX_VALUE_CHARS: Int = 200

    /** 把长尾 EXIF 项折成 JSON 对象；一项都没有时返回 `null`（列保持 `NULL` 而不是 `"{}"`）。 */
    fun json(entries: List<ExifEntry>): String? {
        if (entries.isEmpty()) return null
        val map = LinkedHashMap<String, JsonPrimitive>()
        for (entry in entries) {
            if (map.size >= MAX_ENTRIES) break
            if (entry.value.isEmpty()) continue
            val key = "${entry.group}.${entry.name}"
            if (map.containsKey(key)) continue
            map[key] = JsonPrimitive(entry.value.take(MAX_VALUE_CHARS))
        }
        return if (map.isEmpty()) null else JsonObject(map).toString()
    }
}
