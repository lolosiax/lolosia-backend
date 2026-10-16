package moe.lolosia.web.ui.experiment

import moe.lolosia.web.ui.geometry.UiSize
import moe.lolosia.web.ui.input.UiEvent
import moe.lolosia.web.ui.input.UiEventKind
import moe.lolosia.web.ui.mvvm.Property

/**
 * 「SDL3 + Vulkan 2D 窗口」实验用的 ViewModel。
 *
 * 这里只放与平台无关的部分：View 会订阅这些属性并在变更后标脏重绘，
 * 平台侧（`UiExperiment.mingw.kt`）负责把窗口事件喂进 [onEvent]、把每帧尺寸喂进 [nextFrame]。
 * 后续接上真正的控件树与布局引擎时，这一层就是控件绑定的数据源。
 *
 * @author 洛洛希雅Lolosia
 */
class UiProbeViewModel {

    /** 窗口标题。 */
    val title = Property("洛洛希雅 · Vulkan 2D 实验")

    /** 实际生效的 GPU 后端名（如 `vulkan`）；空串表示没拿到 GPU 设备。 */
    val backend = Property("")

    /** 当前帧的绘制目标尺寸（物理像素）。 */
    val viewport = Property(UiSize.Zero)

    /** 已呈现的帧数。 */
    val frames = Property(0L)

    /** 最近一次输入的可读描述。 */
    val lastInput = Property("等待输入")

    /** 是否已收到关闭请求。 */
    val closeRequested = Property(false)

    /** 处理一次输入事件，更新视图侧数据。 */
    fun onEvent(event: UiEvent) {
        lastInput.value = when (event.kind) {
            UiEventKind.Quit -> {
                closeRequested.value = true
                "关闭请求"
            }

            UiEventKind.Resize -> "窗口尺寸 ${event.x}×${event.y}"
            UiEventKind.MouseMove -> "指针 (${event.x}, ${event.y})"
            UiEventKind.MouseDown -> "按下按钮 ${event.code} @ (${event.x}, ${event.y})"
            UiEventKind.MouseUp -> "抬起按钮 ${event.code} @ (${event.x}, ${event.y})"
            UiEventKind.Wheel -> "滚轮 Δ(${event.dx}, ${event.dy})"
            UiEventKind.KeyDown -> "按击 ${describeKey(event.code)}"
            UiEventKind.KeyUp -> "松开 ${describeKey(event.code)}"
            UiEventKind.Text -> "输入字符 ${describeCodepoint(event.codepoint)}"
        }
    }

    /** 记录一帧；尺寸取当前绘制目标。 */
    fun nextFrame(viewport: UiSize) {
        this.viewport.value = viewport
        frames.value += 1
    }
}

/** SDL 的键码与 ASCII 对齐（小写字母即其码位），可打印的按字符显示，其余给原始码。 */
private fun describeKey(code: Int): String =
    if (code in 32..126) "'${code.toChar()}'" else "键码 $code"

/** 码点可打印时给字符本身，否则给十六进制，便于对照调试。 */
private fun describeCodepoint(codepoint: UInt): String {
    val value = codepoint.toInt()
    return if (value in 32..0x10FFFF) "'${value.toChar()}'" else "U+${codepoint.toString(16).uppercase()}"
}
