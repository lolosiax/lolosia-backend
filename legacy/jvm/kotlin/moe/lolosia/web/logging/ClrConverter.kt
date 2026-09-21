package moe.lolosia.web.logging

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.pattern.CompositeConverter

/**
 * 复刻 Spring Boot 的 `%clr` 颜色转换器，让 Ktor 也能用 Spring 风格的彩色日志 pattern。
 *
 * 用法：`%clr(内容){颜色}`，颜色支持 faint/red/green/yellow/blue/magenta/cyan/default/strong；
 * 不带 `{颜色}` 时按日志级别着色（ERROR红 / WARN黄 / INFO蓝 / DEBUG绿 / TRACE绿），与 Spring 行为一致。
 *
 * 默认开启 ANSI 颜色；输出到文件等场景可用 `-Dlog.ansi=false` 关闭。
 *
 * @author 洛洛希雅Lolosia
 * @since 2026-08-30 14:45
 */
class ClrConverter : CompositeConverter<ILoggingEvent>() {

    private val styles = mapOf(
        "faint" to "\u001B[2m",
        "red" to "\u001B[31m",
        "green" to "\u001B[32m",
        "yellow" to "\u001B[33m",
        "blue" to "\u001B[34m",
        "magenta" to "\u001B[35m",
        "cyan" to "\u001B[36m",
        "default" to "\u001B[39m",
        "strong" to "\u001B[1m",
    )

    private val levelStyles = mapOf(
        Level.ERROR_INT to "\u001B[31m",
        Level.WARN_INT to "\u001B[33m",
        Level.INFO_INT to "\u001B[34m",
        Level.DEBUG_INT to "\u001B[32m",
        Level.TRACE_INT to "\u001B[32m",
    )

    override fun transform(event: ILoggingEvent, `in`: String): String {
        if (!ansiEnabled) return `in`
        val style = firstOption?.let { styles[it] } ?: levelStyles[event.level.toInteger()] ?: "\u001B[32m"
        return "$style$`in`\u001B[0m"
    }

    private companion object {
        val ansiEnabled: Boolean = System.getProperty("log.ansi", "true").toBoolean()
    }
}
