package moe.lolosia.web.util.log

/**
 * 单条日志的入口（持有 tag）。
 *
 * 异常放在首个参数位，以支持尾随 lambda 写消息：`log.error(cause) { "处理失败" }`。
 *
 * @author 洛洛希雅Lolosia
 */
class Logger internal constructor(private val tag: String) {
    fun trace(message: () -> String): Unit = Log.write(LogLevel.TRACE, tag, message, null)
    fun debug(message: () -> String): Unit = Log.write(LogLevel.DEBUG, tag, message, null)
    fun info(message: () -> String): Unit = Log.write(LogLevel.INFO, tag, message, null)

    fun warn(cause: Throwable? = null, message: () -> String): Unit =
        Log.write(LogLevel.WARN, tag, message, cause)

    fun error(cause: Throwable? = null, message: () -> String): Unit =
        Log.write(LogLevel.ERROR, tag, message, cause)
}
