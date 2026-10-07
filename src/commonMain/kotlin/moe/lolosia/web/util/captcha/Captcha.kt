package moe.lolosia.web.util.captcha

import moe.lolosia.web.util.image.RgbCanvas
import kotlin.random.Random

/**
 * 图形验证码（对等复刻旧 JVM 版的 hutool `LineCaptcha` + `MathGenerator`）。
 *
 * 旧版走 hutool，依赖 JDK 的 `java.awt` 字体栅格化与 `javax.imageio` 的 PNG 编码——这两件在
 * Kotlin/Native 上都没有。这里把整条链路自研掉：
 * - 文字：**5x7 点阵字形**（只需要 `0-9 + - * =` 这几个字符），按格缩放；
 * - 干扰线：[INTERFERE_LINES] 条随机彩色短线，随机端点，画在文字**下面**（与 hutool 同序）；
 * - 出图：[RgbCanvas] 手写 PNG（见 `util/image/Png.kt`）。
 *
 * 与旧版的**有意差异**（观感）：hutool 用系统 SansSerif 抗锯齿渲染，这里是方块点阵，字更"像素风"；
 * 算式、干扰线数量、配色策略（每个字符单独随机色）与旧版一致。
 *
 * 已知取舍：随机源是 [Random.Default]（非密码学强度），与 `BCrypt.gensalt` 同一个待办
 * （见 `doc/开发记忆.md` §8）。
 *
 * @author 洛洛希雅Lolosia
 */
class MathCaptcha(
    /** 图上画出来的算式，如 `3 +5 =`（数字按 hutool 的做法用空格补到 2 位）。 */
    val expression: String,
    /** 正确答案（用户应该在输入框里填这个）。 */
    val answer: Int,
    /** PNG 字节（尚未 base64）。 */
    val png: ByteArray,
)

/**
 * 生成一张算式图形验证码。
 *
 * 尺寸与干扰线数量沿用旧版 `CaptchaUtil.createLineCaptcha(100, 40)`（= 150 条线），
 * 背景沿用旧版显式设置的黑色。
 */
fun createMathCaptcha(
    width: Int = DEFAULT_WIDTH,
    height: Int = DEFAULT_HEIGHT,
    interfereCount: Int = INTERFERE_LINES,
    random: Random = Random.Default,
): MathCaptcha {
    val (expression, answer) = generateExpression(random)
    val canvas = RgbCanvas(width, height)
    canvas.fill(BACKGROUND)
    drawInterfereLines(canvas, interfereCount, random)
    drawExpression(canvas, expression, random)
    return MathCaptcha(expression, answer, canvas.toPng())
}

/** 宽度（旧版 100）。 */
const val DEFAULT_WIDTH: Int = 100

/** 高度（旧版 40）。 */
const val DEFAULT_HEIGHT: Int = 40

/** 干扰线条数（hutool `LineCaptcha(width, height)` 默认 150）。 */
const val INTERFERE_LINES: Int = 150

/** 背景色（旧版 `captcha.setBackground(Color.BLACK)`）。 */
private const val BACKGROUND: Int = 0x000000

/** 参与计算的数字位数（hutool `MathGenerator` 默认 2）。 */
private const val NUMBER_LENGTH: Int = 2

/** 参与计算的数字上界 = 10^NUMBER_LENGTH。 */
private const val NUMBER_LIMIT: Int = 100

/** 运算符（hutool `MathGenerator.operators`，**没有除法**）。 */
private const val OPERATORS: String = "+-*"

/**
 * 生成算式与答案：两个 `[0, 100)` 的随机数 + 一个 `+ - *`，结果**允许为负**（hutool 默认）。
 *
 * 字符串形态与 hutool 逐字一致：数字用空格补齐到 [NUMBER_LENGTH] 位，最后跟一个 `=`，
 * 例如 `3 +5 =`、`12*34=`。
 */
private fun generateExpression(random: Random): Pair<String, Int> {
    val left = random.nextInt(NUMBER_LIMIT)
    val right = random.nextInt(NUMBER_LIMIT)
    val operator = OPERATORS[random.nextInt(OPERATORS.length)]
    val answer = when (operator) {
        '+' -> left + right
        '-' -> left - right
        else -> left * right
    }
    val number1 = left.toString().padEnd(NUMBER_LENGTH, ' ')
    val number2 = right.toString().padEnd(NUMBER_LENGTH, ' ')
    return "$number1$operator$number2=" to answer
}

/** 150 条随机彩色干扰线；端点范围沿用 hutool（终点最多偏移 1/8 宽高）。 */
private fun drawInterfereLines(canvas: RgbCanvas, count: Int, random: Random) {
    val maxDx = (canvas.width / 8).coerceAtLeast(1)
    val maxDy = (canvas.height / 8).coerceAtLeast(1)
    repeat(count) {
        val xs = random.nextInt(canvas.width)
        val ys = random.nextInt(canvas.height)
        canvas.drawLine(xs, ys, xs + random.nextInt(maxDx), ys + random.nextInt(maxDy), randomColor(random))
    }
}

/** 逐字符画点阵，每个字符单独随机颜色，整行垂直居中、字符在各自等分格内水平居中。 */
private fun drawExpression(canvas: RgbCanvas, expression: String, random: Random) {
    if (expression.isEmpty()) return
    val cellWidth = canvas.width / expression.length
    val scale = minOf(cellWidth / GLYPH_WIDTH, canvas.height / GLYPH_HEIGHT).coerceAtLeast(1)
    val top = (canvas.height - GLYPH_HEIGHT * scale) / 2

    expression.forEachIndexed { index, ch ->
        val glyph = GLYPHS[ch] ?: return@forEachIndexed
        val left = index * cellWidth + (cellWidth - GLYPH_WIDTH * scale) / 2
        val color = randomColor(random)
        glyph.forEachIndexed { row, line ->
            line.forEachIndexed { col, dot ->
                // 每格多画 1px：点阵笔画由 scale px 加粗成 scale+1 px，字更实、更像 hutool 的字重
                if (dot == '1') canvas.fillRect(left + col * scale, top + row * scale, scale + 1, scale + 1, color)
            }
        }
    }
}

/** 随机彩色（`0..255` 各分量，与 hutool `ImgUtil.randomColor()` 一致）。 */
private fun randomColor(random: Random): Int =
    RgbCanvas.rgb(random.nextInt(256), random.nextInt(256), random.nextInt(256))

/** 点阵字形宽（列数）。 */
private const val GLYPH_WIDTH: Int = 5

/** 点阵字形高（行数）。 */
private const val GLYPH_HEIGHT: Int = 7

/** 点阵字形表：只覆盖 `0-9 + - * =`（空格查不到，[drawExpression] 会跳过）。 */
private val GLYPHS: Map<Char, List<String>> = mapOf(
    '0' to listOf("01110", "10001", "10011", "10101", "11001", "10001", "01110"),
    '1' to listOf("00100", "01100", "00100", "00100", "00100", "00100", "01110"),
    '2' to listOf("01110", "10001", "00001", "00010", "00100", "01000", "11111"),
    '3' to listOf("11111", "00010", "00100", "00010", "00001", "10001", "01110"),
    '4' to listOf("00010", "00110", "01010", "10010", "11111", "00010", "00010"),
    '5' to listOf("11111", "10000", "11110", "00001", "00001", "10001", "01110"),
    '6' to listOf("00110", "01000", "10000", "11110", "10001", "10001", "01110"),
    '7' to listOf("11111", "00001", "00010", "00100", "01000", "01000", "01000"),
    '8' to listOf("01110", "10001", "10001", "01110", "10001", "10001", "01110"),
    '9' to listOf("01110", "10001", "10001", "01111", "00001", "00010", "01100"),
    '+' to listOf("00000", "00100", "00100", "11111", "00100", "00100", "00000"),
    '-' to listOf("00000", "00000", "00000", "11111", "00000", "00000", "00000"),
    '*' to listOf("00000", "10101", "01110", "11111", "01110", "10101", "00000"),
    '=' to listOf("00000", "00000", "11111", "00000", "11111", "00000", "00000"),
)
