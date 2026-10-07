package moe.lolosia.web.util.proc

/** 该目标不提供 shell 执行能力，恒返回 `-1`。 */
internal actual fun shellExec(command: String): Int {
    return -1
}