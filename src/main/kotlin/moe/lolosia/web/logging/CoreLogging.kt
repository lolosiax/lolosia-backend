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
    val log: Logger = LoggerFactory.getLogger("moe.lolosia.web")
}

/** 便捷访问：`import moe.lolosia.web.logging.log` 后直接 `log.info(...)`。 */
val log: Logger get() = CoreLogging.log
