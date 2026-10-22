package moe.lolosia.web

/**
 * 以服务进程方式运行 [block]，阻塞到服务被停止。
 *
 * Windows 下优先交给 SCM（`StartServiceCtrlDispatcherW`）；若当前进程不是由 SCM 拉起
 * （直接命令行 / 调试），自动回退为在当前进程运行。Linux 由 systemd 等信号驱动。
 *
 * @param block 服务主体；`awaitStop()` 在收到 SCM STOP 或进程终止信号时返回，
 *   调用方应在 `finally` 里释放本地服务与事件订阅。
 * @return 进程退出码，0 表示正常退出。
 * @author 洛洛希雅Lolosia
 */
expect fun runServiceProcess(block: suspend (awaitStop: suspend () -> Unit) -> Unit): Int
