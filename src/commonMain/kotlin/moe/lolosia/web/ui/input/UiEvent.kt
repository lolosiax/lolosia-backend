package moe.lolosia.web.ui.input

/**
 * 一次输入事件的扁平表示。
 *
 * 字段按 [kind] 取用，未用到的保持默认值（C 侧也是同样约定）：
 * 指针类用 `x` / `y`，滚轮用 `dx` / `dy`，按键用 `code`（SDL 键码），文本输入用 [codepoint]。
 *
 * @author 洛洛希雅Lolosia
 */
data class UiEvent(
    val kind: UiEventKind,
    val x: Int = 0,
    val y: Int = 0,
    val dx: Int = 0,
    val dy: Int = 0,
    val code: Int = 0,
    val codepoint: UInt = 0u,
)
