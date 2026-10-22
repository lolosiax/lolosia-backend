package moe.lolosia.web.util.tokenizer

/**
 * 分词器的散装常量与降级估算（`doc/vision-autotag-design.md` §6）。
 *
 * @author 洛洛希雅Lolosia
 */

/** 缓存文件名（默认落 `<workDir>/cache/llm/tokenizer.json`）。 */
const val TOKENIZER_CACHE_FILE: String = "tokenizer.json"

/**
 * 字符启发式估算（拉不到分词器时的降级口径，只保证**量级正确**）。
 *
 * 中文 ≈ 0.6 token/字、其余 ≈ 0.3 token/字符 —— 与设计稿 §6.2 的降级口径一致。
 */
fun estimateTokens(text: String): Int {
    if (text.isEmpty()) return 0
    var cjk = 0
    var other = 0
    for (c in text) {
        val code = c.code
        if ((code in 0x4E00..0x9FA5) || (code in 0x3040..0x30FF) || (code in 0x3000..0x303F)) cjk++ else other++
    }
    return (cjk * 0.6 + other * 0.3).toInt().coerceAtLeast(1)
}
