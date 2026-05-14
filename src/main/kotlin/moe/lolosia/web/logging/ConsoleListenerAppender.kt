package moe.lolosia.web.logging

import ch.qos.logback.core.OutputStreamAppender

/**
 * 日志回返
 *
 * @author 洛洛希雅Lolosia
 * @since 2023-12-25 11:42
 */
class ConsoleListenerAppender<E> : OutputStreamAppender<E>() {
    override fun start() {
        outputStream = CoreLogging.outputStream
        super.start()
    }
}