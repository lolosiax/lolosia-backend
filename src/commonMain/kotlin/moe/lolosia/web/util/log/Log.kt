package moe.lolosia.web.util.log

import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Clock

/**
 * 自研极简日志。
 *
 * Kotlin/Native 上没有 SLF4J/Logback（旧 JVM 版的 `logging` 包整层依赖 Logback API，已删），
 * 而本项目对日志的需求只有三样：**级别过滤 / 时间戳 / 异常栈**，所以不引第三方日志库。
 *
 * 用法：
 * ```
 * private val log = Log.of("Bootstrap")
 * log.info { "classpath 已装载：${cp.entries.size} 个条目" }
 * log.error({ "处理请求失败" }, cause)
 * ```
 *
 * 输出形如：`[2026-09-21 14:03:22.123] [INFO ] [Bootstrap] classpath 已装载：100 个条目`
 *
 * @author 洛洛希雅Lolosia
 */
public enum class LogLevel {
    TRACE, DEBUG, INFO, WARN, ERROR, OFF;

    public companion object {
        /** 按名称解析（大小写不敏感、忽略首尾空白）；识别不了时回退 [INFO]。 */
        public fun parse(name: String?): LogLevel =
            entries.firstOrNull { it.name.equals(name?.trim(), ignoreCase = true) } ?: INFO
    }
}

/** 日志全局配置与输出。 */
public object Log {

    /** 全局级别：低于该级别的调用直接丢弃。由配置 `logLevel` 在启动时设定。 */
    public var level: LogLevel = LogLevel.INFO

    /** 输出目的地（默认标准输出）。 */
    public var sink: (String) -> Unit = { println(it) }

    /** 按文件/模块取一个 logger。 */
    public fun of(tag: String): Logger = Logger(tag)

    internal fun write(level: LogLevel, tag: String, message: () -> String, cause: Throwable?) {
        if (level.ordinal < this.level.ordinal) return
        val head = "[${timestamp()}] [${level.name.padEnd(5)}] [$tag] ${message()}"
        sink(if (cause == null) head else "$head\n${render(cause)}")
    }

    /**
     * 渲染异常：先打最外层（含栈），再把 cause 链的类名与消息逐层列出。
     *
     * 只打 `stackTraceToString()` 会丢根因 —— Kotlin 的 `Throwable.toString()` 不带 cause，
     * 于是「Koin 创建实例失败」这类包装异常会把真正的原因（例如连库失败）整个吞掉。
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

    /** 取不到系统时区时退回 UTC —— 日志本身不该把进程拖垮。 */
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

/** 单条日志的入口（持有 tag）。异常放在首个参数位，以支持尾随 lambda 写消息。 */
public class Logger internal constructor(private val tag: String) {
    public fun trace(message: () -> String): Unit = Log.write(LogLevel.TRACE, tag, message, null)
    public fun debug(message: () -> String): Unit = Log.write(LogLevel.DEBUG, tag, message, null)
    public fun info(message: () -> String): Unit = Log.write(LogLevel.INFO, tag, message, null)

    public fun warn(cause: Throwable? = null, message: () -> String): Unit =
        Log.write(LogLevel.WARN, tag, message, cause)

    public fun error(cause: Throwable? = null, message: () -> String): Unit =
        Log.write(LogLevel.ERROR, tag, message, cause)
}
