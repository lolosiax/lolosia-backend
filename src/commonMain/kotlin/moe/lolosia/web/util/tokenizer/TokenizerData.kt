package moe.lolosia.web.util.tokenizer

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * 解析后的 DeepSeek 分词器：词表 + 合并表 + 特殊 token。见 `doc/vision-autotag-design.md` §6。
 *
 * 特殊 token（`<｜…｜>`）先于 BPE 被整块切出；其余文本走 [PreTokenizer] → [ByteLevel] → [Bpe]。
 *
 * @author 洛洛希雅Lolosia
 */
class TokenizerData(
    private val vocab: Map<String, Int>,
    private val bpe: Bpe,
    /** 特殊 token：内容 → id，**按长度降序**（保证最长匹配）。 */
    private val addedTokens: List<Pair<String, Int>>,
) {

    /** 文本 → token id 序列。 */
    fun encode(text: String): IntArray {
        val ids = ArrayList<Int>()
        var i = 0
        val n = text.length
        while (i < n) {
            val added = addedAt(text, i)
            if (added != null) {
                ids.add(added.second)
                i += added.first.length
                continue
            }
            val next = nextAdded(text, i)
            val segment = text.substring(i, next)
            for (word in PreTokenizer.split(segment)) {
                for (symbol in bpe.merge(ByteLevel.encode(word))) {
                    val id = vocab[symbol]
                    if (id != null) {
                        ids.add(id)
                    } else {
                        // 字节回退：vocab 里没有该符号时，拆回字节查 <0xXX>
                        for (ch in symbol) {
                            val byte = ByteLevel.charToByte[ch] ?: continue
                            val fallback = vocab[byteToken(byte)] ?: vocab[byteToken(byte).lowercase()]
                            if (fallback != null) ids.add(fallback)
                        }
                    }
                }
            }
            i = next
        }
        return ids.toIntArray()
    }

    /** 位置 [i] 处是否有特殊 token 匹配；有则返回（内容, id）。 */
    private fun addedAt(text: String, i: Int): Pair<String, Int>? {
        if (text[i] != '<') return null
        for ((content, id) in addedTokens) {
            if (text.startsWith(content, i)) return content to id
        }
        return null
    }

    /** 从 [i] 起（不含 i）**下一个**特殊 token 的起点；没有则返回串长。 */
    private fun nextAdded(text: String, i: Int): Int {
        var j = text.indexOf('<', i)
        while (j >= 0) {
            if (addedAt(text, j) != null) return j
            j = text.indexOf('<', j + 1)
        }
        return text.length
    }

    private fun byteToken(byte: Int): String = "<0x${byte.toString(16).uppercase().padStart(2, '0')}>"

    companion object {

        private val json = Json { ignoreUnknownKeys = true; isLenient = true }

        /** 解析 HF 格式的 `tokenizer.json`。 */
        fun parse(text: String): TokenizerData {
            val root = json.parseToJsonElement(text).jsonObject
            val model = root["model"]?.jsonObject ?: error("tokenizer.json 缺少 model")

            val vocabJson = model["vocab"]?.jsonObject ?: error("tokenizer.json 缺少 model.vocab")
            val vocab = HashMap<String, Int>(vocabJson.size * 2)
            for ((key, value) in vocabJson) vocab[key] = (value as? JsonPrimitive)?.int ?: continue

            val ranks = HashMap<String, Int>(160_000)
            val merges = model["merges"]?.jsonArray ?: JsonArray(emptyList())
            for ((index, element) in merges.withIndex()) {
                val pair = when (element) {
                    is JsonArray -> element.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
                    is JsonPrimitive -> element.contentOrNull?.split(' ').orEmpty()
                    else -> emptyList()
                }
                if (pair.size == 2) ranks["${pair[0]}\u0000${pair[1]}"] = index
            }

            val added = ArrayList<Pair<String, Int>>()
            for (element in root["added_tokens"]?.jsonArray.orEmpty()) {
                val obj = element.jsonObject
                val content = obj["content"]?.jsonPrimitive?.contentOrNull ?: continue
                val id = obj["id"]?.jsonPrimitive?.int ?: continue
                added.add(content to id)
            }
            added.sortByDescending { it.first.length }
            return TokenizerData(vocab, Bpe(ranks), added)
        }
    }
}
