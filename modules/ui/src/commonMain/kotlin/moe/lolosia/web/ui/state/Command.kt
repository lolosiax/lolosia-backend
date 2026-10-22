package moe.lolosia.web.ui.state

/**
 * 命令：由视图触发、落到 ViewModel 的动作。
 *
 * `enabled` 即时求值，不缓存。
 *
 * @param canExecute 当前是否可执行。
 * @param body 实际动作。
 * @author 洛洛希雅Lolosia
 */
class Command(private val canExecute: () -> Boolean = { true }, private val body: () -> Unit) {

    val enabled: Boolean
        get() = canExecute()

    /** 执行；不可执行时静默跳过。 */
    fun execute() {
        if (!canExecute()) return
        body()
    }
}
