package moe.lolosia.web.util.log

/** 日志级别。 */
enum class LogLevel {
    TRACE, DEBUG, INFO, WARN, ERROR, OFF;

    companion object {
        /** 按名称解析（大小写不敏感、忽略首尾空白）；识别不了时回退 [INFO]。 */
        fun parse(name: String?): LogLevel =
            entries.firstOrNull { it.name.equals(name?.trim(), ignoreCase = true) } ?: INFO
    }
}
