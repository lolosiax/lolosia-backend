package moe.lolosia.web.util.tokenizer

/**
 * DeepSeek 分词器的**预切分**（`pre_tokenizer` 的前三步 `Split`，见 `doc/vision-autotag-design.md` §6.1）。
 *
 * 原始配置是一个 `Sequence`：
 * 1. `Split(\p{N}{1,3})` —— 数字按每 1~3 个一组切出；
 * 2. `Split([一-龥぀-ゟ゠-ヿ]+)` —— 中日文连续段整段切出；
 * 3. `Split(GPT-2 系正则)` —— 其余部分按词/标点/空白切分。
 *
 * K/N 的 `Regex` 对 `\p{L}` 这类 Unicode 类支持有限，故**手写扫描器**（也更快）：
 * 三段是互斥的首字符判定，等价于顺序做三次 Split。
 *
 * @author 洛洛希雅Lolosia
 */
object PreTokenizer {

    /** 第三段的 ASCII 标点集合（原正则的字面量）。 */
    private val ASCII_PUNCT: Set<Char> = "!\"#$%&'()*+,-./:;<=>?@[\\]^_`{|}~".toSet()

    /** 把文本切成「词」（BPE 的输入单元）。 */
    fun split(text: String): List<String> {
        val out = ArrayList<String>()
        var i = 0
        val n = text.length
        while (i < n) {
            val c = text[i]
            when {
                isCjk(c) -> {
                    var j = i + 1
                    while (j < n && isCjk(text[j])) j++
                    out.add(text.substring(i, j))
                    i = j
                }

                isDigit(c) -> {
                    // \p{N}{1,3}：每次最多 3 个数字
                    var j = i
                    var count = 0
                    while (j < n && count < 3 && isDigit(text[j])) {
                        j++
                        count++
                    }
                    out.add(text.substring(i, j))
                    i = j
                }

                else -> {
                    val len = gpt2LengthAt(text, i)
                    if (len <= 0) {
                        out.add(text.substring(i, i + 1))
                        i++
                    } else {
                        out.add(text.substring(i, i + len))
                        i += len
                    }
                }
            }
        }
        return out
    }

    /**
     * 第三段 GPT-2 系正则在位置 [i] 处的匹配长度；无匹配返回 0。
     *
     * 六个候选分支**按原正则的先后顺序**尝试（正则的选择分支是「第一个能匹配的」）：
     * ```
     * [ASCII 标点][A-Za-z]+
     * |[^\r\n\p{L}\p{P}\p{S}]?[\p{L}\p{M}]+
     * | ?[\p{P}\p{S}]+[\r\n]*
     * |\s*[\r\n]+
     * |\s+(?!\S)
     * |\s+
     * ```
     */
    private fun gpt2LengthAt(s: String, i: Int): Int {
        val n = s.length
        val c = s[i]

        // ① [ASCII 标点][A-Za-z]+
        if (c in ASCII_PUNCT && i + 1 < n && isAsciiLetter(s[i + 1])) {
            var j = i + 1
            while (j < n && isAsciiLetter(s[j])) j++
            return j - i
        }

        // ② [^\r\n\p{L}\p{P}\p{S}]?[\p{L}\p{M}]+
        if (isLetter(c)) {
            var j = i
            while (j < n && isLetter(s[j])) j++
            return j - i
        }
        if (isOtherBeforeLetter(c) && i + 1 < n && isLetter(s[i + 1])) {
            var j = i + 1
            while (j < n && isLetter(s[j])) j++
            return j - i
        }

        // ③  ?[\p{P}\p{S}]+[\r\n]*
        if (isPunctSym(c)) return punctRun(s, i)
        if (c == ' ' && i + 1 < n && isPunctSym(s[i + 1])) return punctRun(s, i + 1) + 1

        // ④ \s*[\r\n]+
        run {
            var k = i
            while (k < n && isWhitespace(s[k]) && !isCrLf(s[k])) k++
            if (k < n && isCrLf(s[k])) {
                var j = k
                while (j < n && isCrLf(s[j])) j++
                return j - i
            }
        }

        // ⑤ \s+(?!\S)：空白后**不能**紧跟非空白
        //    到串尾：整段空白都算；后面是非空白：`\s+` 需回退一位，只吃 L-1 个（留下一个交给后续分支）
        if (isWhitespace(c)) {
            var j = i
            while (j < n && isWhitespace(s[j])) j++
            if (j >= n) return j - i
            if (j - i >= 2) return j - i - 1
        }

        // ⑥ \s+
        if (isWhitespace(c)) {
            var j = i
            while (j < n && isWhitespace(s[j])) j++
            return j - i
        }

        return 0
    }

    /** 从 [from] 起连续标点/符号 + 尾随换行的长度。 */
    private fun punctRun(s: String, from: Int): Int {
        var j = from
        while (j < s.length && isPunctSym(s[j])) j++
        while (j < s.length && isCrLf(s[j])) j++
        return j - from
    }

    private fun isCrLf(c: Char): Boolean = c == '\r' || c == '\n'

    private fun isWhitespace(c: Char): Boolean = c.isWhitespace()

    private fun isLetter(c: Char): Boolean = c.isLetter()

    private fun isDigit(c: Char): Boolean = c.isDigit()

    private fun isAsciiLetter(c: Char): Boolean = c in 'a'..'z' || c in 'A'..'Z'

    /** 控制字符（`\s`/字母/数字/标点都不是）。 */
    private fun isControl(c: Char): Boolean = c.code < 0x20 || c.code == 0x7F

    /** `\p{P}` ∪ `\p{S}` 的近似：既非字母/数字/空白/换行，也非控制符。 */
    private fun isPunctSym(c: Char): Boolean =
        !isCrLf(c) && !isWhitespace(c) && !isLetter(c) && !isDigit(c) && !isControl(c)

    /** `[^\r\n\p{L}\p{P}\p{S}]` 的近似：空白/数字/控制等（用于第二分支的前置可选字符）。 */
    private fun isOtherBeforeLetter(c: Char): Boolean =
        !isCrLf(c) && !isLetter(c) && !isPunctSym(c)

    /** `[一-龥぀-ゟ゠-ヿ]`（CJK 统一表意 + 平假名 + 片假名）。 */
    private fun isCjk(c: Char): Boolean {
        val code = c.code
        return (code in 0x4E00..0x9FA5) || (code in 0x3040..0x309F) || (code in 0x30A0..0x30FF)
    }
}
