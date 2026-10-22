package moe.lolosia.web.util.proc

/**
 * 执行一条 shell 命令，返回退出码。
 *
 * 命令中的重定向与管道由 `/bin/sh` 解释，调用方需保证命令可被 POSIX shell 解析。
 *
 * @param command 完整命令行
 * @return 命令退出码；平台不支持时返回 `-1`
 */
internal expect fun shellExec(command: String): Int
