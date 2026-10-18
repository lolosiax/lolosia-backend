package moe.lolosia.web.ui.mvvm

/**
 * 命令：MVVM 里由 View 触发、落到 ViewModel 的动作（按钮点击、菜单项、快捷键都走它）。
 *
 * `enabled` 由调用方给的判定即时求值，不做缓存 —— 控件绘制时读一次即可，
 * 缓存反而要维护「什么时候失效」，在 UI 规模不大的场景得不偿失。
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
