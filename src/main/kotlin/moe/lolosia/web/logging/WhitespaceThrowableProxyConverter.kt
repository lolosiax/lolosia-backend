package moe.lolosia.web.logging

import ch.qos.logback.classic.pattern.ThrowableProxyConverter
import ch.qos.logback.classic.spi.IThrowableProxy

/**
 * 复刻 Spring Boot 的 `%wEx`：异常堆栈前后各加一个换行，与日志主体分隔开。
 *
 * @author 洛洛希雅Lolosia
 * @since 2026-08-30 14:45
 */
class WhitespaceThrowableProxyConverter : ThrowableProxyConverter() {

    override fun throwableProxyToString(tp: IThrowableProxy): String {
        return "\n" + super.throwableProxyToString(tp) + "\n"
    }
}
