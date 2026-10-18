package moe.lolosia.web.util.tokenizer

/**
 * GPT-2 系 ByteLevel 映射：把每个字节（0..255）映射成一个**可见且不冲突**的 Unicode 字符。
 *
 * 见 `doc/vision-autotag-design.md` §6：DeepSeek 分词器的 `pre_tokenizer` 最后一步是
 * `ByteLevel(use_regex=false)` —— 即只做字节 → 字符的映射，不做额外切分（切分已由前面的
 * `Split` 完成）。BPE 于是在这些「字节字符」上做合并。
 *
 * 可打印区间 `!..~` / `¡..¬` / `®..ÿ` 原样保留，其余字节按序映射到 `U+0100` 起的位置。
 *
 * @author 洛洛希雅Lolosia
 */
object ByteLevel {

    /** 字节值 → 映射字符（下标即字节）。 */
    val byteToChar: Array<Char> = run {
        val bytes = ArrayList<Int>(256)
        for (b in '!'.code..'~'.code) bytes.add(b)
        for (b in 0xA1..0xAC) bytes.add(b)
        for (b in 0xAE..0xFF) bytes.add(b)
        val chars = ArrayList<Int>(bytes)
        val present = bytes.toHashSet()
        var extra = 0
        for (b in 0..255) {
            if (b !in present) {
                bytes.add(b)
                chars.add(0x100 + extra)
                extra++
            }
        }
        val out = Array(256) { ' ' }
        for (i in bytes.indices) out[bytes[i]] = chars[i].toChar()
        out
    }

    /** 映射字符 → 字节值（解码用）。 */
    val charToByte: Map<Char, Int> = byteToChar.withIndex().associate { (byte, ch) -> ch to byte }

    /** 把一个词的 UTF-8 字节逐个映射成字节字符序列（BPE 的输入符号）。 */
    fun encode(word: String): List<String> {
        val out = ArrayList<String>(word.length)
        for (byte in word.encodeToByteArray()) out.add(byteToChar[byte.toInt() and 0xFF].toString())
        return out
    }
}
