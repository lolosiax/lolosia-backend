package moe.lolosia.web.ui

import kotlinx.cinterop.*
import moe.lolosia.web.ui.experiment.UiProbeViewModel
import moe.lolosia.web.ui.geometry.UiSize
import moe.lolosia.web.ui.input.UiEvent
import moe.lolosia.web.ui.input.UiEventKind
import moe.lolosia.web.ui.native.*
import moe.lolosia.web.util.log.Log

private const val WINDOW_WIDTH = 1280
private const val WINDOW_HEIGHT = 800

/** 无输入时一帧的等待上限，约 60fps；有输入就立刻出帧。 */
private const val FRAME_INTERVAL_MS = 16

/** 骨架阶段的底色：先用深色，方便确认 Vulkan 这条链路真的在出帧。 */
private const val CLEAR_R = 0.07f
private const val CLEAR_G = 0.07f
private const val CLEAR_B = 0.10f

actual fun runUiExperiment(): Int {
    val log = Log.of("UiExperiment")
    val viewModel = UiProbeViewModel()

    memScoped {
        if (ll_ui_create(viewModel.title.value.cstr, WINDOW_WIDTH, WINDOW_HEIGHT) != 0) {
            log.error { "创建窗口失败：${lastErrorText()}" }
            return 1
        }
        viewModel.backend.value = ll_ui_backend()?.toKString().orEmpty()
        log.info {
            val backend = viewModel.backend.value.ifEmpty { "无（只显示空白窗口）" }
            "窗口已创建：${WINDOW_WIDTH}×${WINDOW_HEIGHT}，GPU 后端=$backend"
        }
        // 设备没拿到不算致命：窗口照样开，原因留给日志
        lastErrorText().takeIf { it.isNotEmpty() }?.let { log.warn { it } }

        val event = alloc<ll_ui_event>()
        val width = alloc<IntVar>()
        val height = alloc<IntVar>()
        var running = true
        try {
            while (running) {
                // 首次等待兼作帧节拍（无输入时约 60fps），随后把同批积压的事件一次性取走
                var timeout = FRAME_INTERVAL_MS
                while (true) {
                    val result = ll_ui_poll(event.ptr, timeout)
                    if (result < 0) {
                        running = false
                        break
                    }
                    timeout = 0
                    if (result == 0) break
                    event.toUiEvent()?.let(viewModel::onEvent)
                }
                if (!running) break

                if (ll_ui_begin_frame(width.ptr, height.ptr) != 0) {
                    log.error { "取帧失败：${lastErrorText()}" }
                    break
                }
                viewModel.nextFrame(UiSize(width.value, height.value))
                ll_ui_clear(CLEAR_R, CLEAR_G, CLEAR_B, 1.0f)
                if (ll_ui_end_frame() != 0) {
                    log.error { "提交帧失败：${lastErrorText()}" }
                    break
                }
            }
        } finally {
            ll_ui_destroy()
        }
        log.info { "窗口已关闭，共呈现 ${viewModel.frames.value} 帧；最后输入：${viewModel.lastInput.value}" }
    }
    return 0
}

private fun lastErrorText(): String = ll_ui_last_error()?.toKString().orEmpty()

/** C 侧扁平事件 → Kotlin 事件；类型不认识（两侧枚举不同步）时返回 `null` 由调用方丢弃。 */
private fun ll_ui_event.toUiEvent(): UiEvent? {
    val kind = UiEventKind.fromCode(this.kind) ?: return null
    return UiEvent(kind, x, y, dx, dy, code, codepoint)
}
