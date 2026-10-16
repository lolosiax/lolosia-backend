package moe.lolosia.web.ui.input

/**
 * 输入事件类型。
 *
 * `code` 的取值与 C 侧 `lolosia_ui.h` 里的 `LL_UI_EVENT_*` 枚举一一对应，
 * 两边任何一侧增删事件都要同步改（对面有同名常量，忘了改会在转换处表现为事件被丢弃）。
 *
 * @param code C 侧枚举值。
 * @author 洛洛希雅Lolosia
 */
enum class UiEventKind(val code: Int) {
    Quit(1),
    Resize(2),
    MouseDown(3),
    MouseUp(4),
    MouseMove(5),
    Wheel(6),
    KeyDown(7),
    KeyUp(8),
    Text(9);

    companion object {
        /** 按 C 侧枚举值查类型；未知值返回 `null`（调用方丢弃该事件）。 */
        fun fromCode(code: Int): UiEventKind? = entries.firstOrNull { it.code == code }
    }
}
