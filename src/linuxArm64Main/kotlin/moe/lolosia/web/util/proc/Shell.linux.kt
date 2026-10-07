package moe.lolosia.web.util.proc

import platform.posix.system

/** 经 C 运行时的 `system()` 执行，命令由 `/bin/sh -c` 解释。 */
internal actual fun shellExec(command: String): Int {
    return system(command)
}