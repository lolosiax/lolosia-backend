package moe.lolosia.web

import kotlinx.coroutines.runBlocking
import moe.lolosia.web.util.platform.awaitSignalTermination

actual fun runServiceProcess(block: suspend (awaitStop: suspend () -> Unit) -> Unit): Int {
    // systemd / 控制台：信号由内核默认动作处理，服务主体一直挂起。
    runBlocking { block { awaitSignalTermination() } }
    return 0
}
