package moe.lolosia.web.util.log

/** 日志级别。 */
public enum class LogLevel {
    TRACE, DEBUG, INFO, WARN, ERROR, OFF;

    public companion object {
        /** 按名称解析（大小写不敏感、忽略首尾空白）；识别不了时回退 [INFO]。 */
        public fun parse(name: String?): LogLevel =
            entries.firstOrNull { it.name.equals(name?.trim(), ignoreCase = true) } ?: INFO
    }
}
