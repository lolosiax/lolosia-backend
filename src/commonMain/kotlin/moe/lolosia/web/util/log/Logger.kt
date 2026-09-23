package moe.lolosia.web.util.log

/**
 * 单条日志的入口（持有 tag）。
 *
 * 异常放在首个参数位，以支持尾随 lambda 写消息：`log.error(cause) { "处理失败" }`。
 *
 * @author 洛洛希雅Lolosia
 */
public class Logger internal constructor(private val tag: String) {
    public fun trace(message: () -> String): Unit = Log.write(LogLevel.TRACE, tag, message, null)
    public fun debug(message: () -> String): Unit = Log.write(LogLevel.DEBUG, tag, message, null)
    public fun info(message: () -> String): Unit = Log.write(LogLevel.INFO, tag, message, null)

    public fun warn(cause: Throwable? = null, message: () -> String): Unit =
        Log.write(LogLevel.WARN, tag, message, cause)

    public fun error(cause: Throwable? = null, message: () -> String): Unit =
        Log.write(LogLevel.ERROR, tag, message, cause)
}
