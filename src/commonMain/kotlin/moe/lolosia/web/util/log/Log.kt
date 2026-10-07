package moe.lolosia.web.util.log

import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Clock

/**
 * 日志输出。
 *
 * 输出形如 `[2026-09-21 14:03:22.123] [INFO ] [Bootstrap] classpath 已装载：100 个条目`。
 *
 * @author 洛洛希雅Lolosia
 */
object Log {

    /** 全局级别：低于该级别的调用直接丢弃。 */
    var level: LogLevel = LogLevel.INFO

    /** 输出目的地（默认标准输出）。 */
    var sink: (String) -> Unit = { println(it) }

    /** 按模块取一个 logger。 */
    fun of(tag: String): Logger = Logger(tag)

    internal fun write(level: LogLevel, tag: String, message: () -> String, cause: Throwable?) {
        if (level.ordinal < this.level.ordinal) return
        val head = "[${timestamp()}] [${level.name.padEnd(5)}] [$tag] ${message()}"
        sink(if (cause == null) head else "$head\n${render(cause)}")
    }

    /**
     * 渲染异常：先打最外层（含栈），再把 cause 链的类名与消息逐层列出。
     *
     * Kotlin 的 `Throwable.toString()` 不带 cause，只打最外层会把真实原因整个吞掉。
     */
    private fun render(cause: Throwable): String = buildString {
        append(cause.stackTraceToString())
        var t = cause.cause
        var depth = 0
        while (t != null && depth < MAX_CAUSE_DEPTH) {
            append("\nCaused by: ${t::class.simpleName}: ${t.message}")
            t = t.cause
            depth++
        }
        if (t != null) append("\n...（cause 链过长已截断）")
    }

    private const val MAX_CAUSE_DEPTH = 5

    /** 取不到系统时区时退回 UTC。 */
    private val zone: TimeZone by lazy {
        runCatching { TimeZone.currentSystemDefault() }.getOrElse { TimeZone.UTC }
    }

    private fun timestamp(): String {
        val t: LocalDateTime = Clock.System.now().toLocalDateTime(zone)
        return buildString {
            append(t.year.toString().padStart(4, '0')).append('-')
            append((t.month.ordinal + 1).toString().padStart(2, '0')).append('-')
            append(t.day.toString().padStart(2, '0')).append(' ')
            append(t.hour.toString().padStart(2, '0')).append(':')
            append(t.minute.toString().padStart(2, '0')).append(':')
            append(t.second.toString().padStart(2, '0')).append('.')
            append((t.nanosecond / 1_000_000).toString().padStart(3, '0'))
        }
    }
}
