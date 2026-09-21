package moe.lolosia.web.logging

import org.slf4j.Logger
import org.slf4j.LoggerFactory

/**
 * 统一日志入口。
 *
 * @author 洛洛希雅Lolosia
 * @since 2024-12-02 09:51
 */
object CoreLogging {
    @Deprecated("不应使用全局日志包")
    val log: Logger = LoggerFactory.getLogger("moe.lolosia.web")
}

/** 便捷访问：`import moe.lolosia.web.logging.log` 后直接 `log.info(...)`。 */
@Suppress("DEPRECATION")
@Deprecated("不应使用全局日志包")
val log: Logger get() = CoreLogging.log
