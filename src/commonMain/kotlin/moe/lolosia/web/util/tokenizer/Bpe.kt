package moe.lolosia.web.util.tokenizer

/**
 * 字节级 BPE 合并（照 HF `tokenizers` 的 BPE 实现，见 `doc/vision-autotag-design.md` §6.1）。
 *
 * 每个词先经 [ByteLevel] 变成「字节字符」序列，然后反复挑**合并优先级最高（rank 最小）**的相邻对
 * 合并，直到没有可合并的对。用「取最小 rank 的对」而非朴素 O(n²)，与 HF 一致。
 *
 * @param ranks 「左符号 \u0000 右符号」→ 合并优先级（值越小越优先）
 * @author 洛洛希雅Lolosia
 */
class Bpe(private val ranks: Map<String, Int>) {

    private val cache = HashMap<String, List<String>>()

    /** 合并一串符号；长度 < 2 时原样返回。 */
    fun merge(symbols: List<String>): List<String> {
        if (symbols.size < 2) return symbols
        val key = symbols.joinToString("\u0001")
        cache[key]?.let { return it }

        var word = symbols
        var pairs = pairsOf(word)
        while (pairs.isNotEmpty()) {
            var best: Pair<String, String>? = null
            var bestRank = Int.MAX_VALUE
            for (pair in pairs) {
                val rank = ranks[rankKey(pair.first, pair.second)] ?: continue
                if (rank < bestRank) {
                    bestRank = rank
                    best = pair
                }
            }
            if (best == null) break
            val first = best.first
            val second = best.second

            val merged = ArrayList<String>(word.size)
            var i = 0
            while (i < word.size) {
                var j = i
                while (j < word.size && word[j] != first) j++
                if (j >= word.size) {
                    merged.addAll(word.subList(i, word.size))
                    break
                }
                merged.addAll(word.subList(i, j))
                i = j
                if (i < word.size - 1 && word[i] == first && word[i + 1] == second) {
                    merged.add(first + second)
                    i += 2
                } else {
                    merged.add(word[i])
                    i += 1
                }
            }
            word = merged
            if (word.size == 1) break
            pairs = pairsOf(word)
        }
        cache[key] = word
        return word
    }

    private fun pairsOf(word: List<String>): Set<Pair<String, String>> {
        if (word.size < 2) return emptySet()
        val out = LinkedHashSet<Pair<String, String>>()
        for (i in 0 until word.size - 1) out.add(word[i] to word[i + 1])
        return out
    }

    private fun rankKey(a: String, b: String): String = "$a\u0000$b"
}
