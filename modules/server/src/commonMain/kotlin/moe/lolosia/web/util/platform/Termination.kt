package moe.lolosia.web.util.platform

/**
 * 把中止信号（SIGINT / SIGTERM）交还给内核的默认动作，并挂起当前线程直到进程被信号结束。
 *
 * 起因是 Ktor 的 `EmbeddedServer.start()` 会调 `addShutdownHook { stop() }`，而它的 native 实现
 * 用 `signal()` 把这两个信号接到「在信号处理器里跑 `stop()`」上 —— 处理器里做 `runBlocking`、
 * 释放 Application 会死锁。实测 systemd 发来 SIGTERM 后进程 90s 纹丝不动，直到 `TimeoutStopSec`
 * 超时被 SIGKILL，重启期间新进程又因端口没释放而反复崩在 EADDRINUSE。
 *
 * 还原成默认动作（内核直接终止进程）后 `systemctl restart` 立刻完成；端口上残留的半开连接由
 * 监听 socket 的 `SO_REUSEADDR` 兜住（见 `Main`）。代价是不再有「优雅停机」：不做收尾、不等在途
 * 请求 —— 这比原来卡 90s 再被 SIGKILL 严格更好，且本服务的重启没有需要保住的在途事务。
 *
 * 平台实现见各目标源集。
 *
 * @author 洛洛希雅Lolosia
 */
expect fun awaitSignalTermination(): Nothing
