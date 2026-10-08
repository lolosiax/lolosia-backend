package moe.lolosia.web.util.platform

import kotlinx.cinterop.ExperimentalForeignApi
import platform.posix.SIGINT
import platform.posix.SIGTERM
import platform.posix.SIG_DFL
import platform.posix.signal
import platform.posix.sleep

/**
 * 两个 native 目标的 CRT 都提供 posix 的 `signal` / `SIG_DFL` / `sleep`，所以一份实现就够。
 *
 * 空转用 `sleep` 而不是让 `main` 直接返回：返回会走运行时的退出流程（收尾、等待非守护线程），
 * 那条路在长期运行的服务上未必干干净净终止；这里只需要一个永不返回的挂起点。
 *
 * @author 洛洛希雅Lolosia
 */
@OptIn(ExperimentalForeignApi::class)
actual fun awaitSignalTermination(): Nothing {
    signal(SIGINT, SIG_DFL)
    signal(SIGTERM, SIG_DFL)
    while (true) sleep(1u)
}
