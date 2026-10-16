package moe.lolosia.web.ui

import kotlinx.cinterop.*
import moe.lolosia.web.ui.experiment.ModelField
import moe.lolosia.web.ui.experiment.UiProbeViewModel
import moe.lolosia.web.ui.mvvm.Property
import moe.lolosia.web.ui.native.*
import moe.lolosia.web.util.classpath.installPlatformClasspath
import moe.lolosia.web.util.log.Log
import moe.lolosia.web.util.log.Logger

private const val WINDOW_TITLE = "洛洛希雅 · RmlUi 布局实验"
private const val WINDOW_WIDTH = 1120
private const val WINDOW_HEIGHT = 760

/** 字体注册用的家族名，必须与 probe.rml 里 body 的 font-family 一致。 */
private const val FONT_FAMILY = "lolosia-ui"

/** 文档在 classpath 里的位置（改它要重新链接，见 AGENTS.md 的 classpath 说明）。 */
private const val DOCUMENT_PATH = "ui/probe.rml"

/** 字体候选：优先带中日韩字形的，最后才是纯拉丁字体（用它中文会缺字）。 */
private val FONT_CANDIDATES = listOf(
    "C:/Windows/Fonts/msyh.ttc",
    "C:/Windows/Fonts/msyh.ttf",
    "C:/Windows/Fonts/simhei.ttf",
    "C:/Windows/Fonts/segoeui.ttf",
)

/**
 * 开一个 RmlUi 窗口并跑事件循环，直到窗口关闭。
 *
 * 这里是整条链路里唯一接触 C ABI 的地方：声明数据模型字段、订阅 ViewModel 的变更、
 * 把文档里的 action 转给 ViewModel、跑帧循环。视图层要加字段/动作时，改
 * [UiProbeViewModel] 与 `resources/ui/probe.rml` 即可，本文件基本不用动。
 */
actual fun runUiExperiment(): Int {
    val log = Log.of("UiExperiment")
    val viewModel = UiProbeViewModel()

    val classpath = installPlatformClasspath()
    val document = classpath.read(DOCUMENT_PATH)?.decodeToString()
    if (document == null) {
        log.error { "classpath 里找不到 $DOCUMENT_PATH（来源：${classpath.origin}），先确认它在 resources 里" }
        return 1
    }

    if (WINDOW_TITLE.cString { ll_ui_create(it, WINDOW_WIDTH, WINDOW_HEIGHT) } != 0) {
        log.error { "创建窗口失败：${lastError()}" }
        return 1
    }

    // 动作回调不能捕获外部变量（staticCFunction 要求无捕获），
    // 因此把 ViewModel 的稳定引用作为 user_data 交给 C 侧，回调时原样回传
    val viewModelRef = StableRef.create(viewModel)
    var subscriptions: List<Property.Subscription> = emptyList()
    try {
        loadFont(log)

        val bound = bindModel(viewModel, log)
        if (bound == null) return 1
        subscriptions = bound
        ll_ui_set_action_callback(staticCFunction(::handleAction), viewModelRef.asCPointer())

        if (document.cString { ll_ui_load_document(it) } != 0) {
            log.error { "加载文档失败：${lastError()}" }
            return 1
        }

        log.info { "窗口就绪（$WINDOW_WIDTH×$WINDOW_HEIGHT，数据模型字段 ${viewModel.fields.size} 个），关窗退出" }
        while (true) {
            when (val frame = ll_ui_frame()) {
                0 -> Unit
                1 -> {
                    log.info { "收到关闭请求" }
                    return 0
                }

                else -> {
                    log.error { "渲染失败（$frame）：${lastError()}" }
                    return 1
                }
            }
        }
    } finally {
        subscriptions.forEach { it.cancel() }
        viewModelRef.dispose()
        ll_ui_destroy()
    }
}

/**
 * 把 Kotlin 字符串按 UTF-8 传给接受 `const char*` 的 C 函数。
 *
 * 用 pin 而不是 `cstr`：仓库里其他 native 代码（PosixFile / Png / Webp）都是这个写法。
 * 指针只在 [block] 执行期间有效 —— C 侧对字符串参数都是当场拷贝，不持有。
 */
private inline fun <R> String.cString(block: (CPointer<ByteVar>) -> R): R =
    (this + '\u0000').encodeToByteArray().usePinned { block(it.addressOf(0)) }

private fun lastError(): String = ll_ui_last_error()?.toKString().orEmpty()

/** 界面上的文字全靠它。一个都加载不上不算致命（窗口照常可用），但必须留下明确日志。 */
private fun loadFont(log: Logger) {
    var loaded: String? = null
    FONT_FAMILY.cString { family ->
        for (path in FONT_CANDIDATES) {
            if (path.cString { ll_ui_load_font(it, family) } == 0) {
                loaded = path
                break
            }
            log.warn { "字体不可用：$path（${lastError()}）" }
        }
    }
    if (loaded != null) {
        log.info { "已加载字体：$loaded（家族名 $FONT_FAMILY）" }
    } else {
        log.error { "没有可用字体，界面上的文字画不出来。候选：${FONT_CANDIDATES.joinToString()}" }
    }
}

/**
 * 声明数据模型字段并订阅变更，返回全部订阅句柄（失败返回 null）。
 *
 * 订阅之后，只要命令改了某个 [Property]，新值就会自动推回视图并触发重绘 ——
 * 视图侧不需要任何「手动刷新」调用，这就是 MVVM 里的那条单向数据流。
 */
private fun bindModel(viewModel: UiProbeViewModel, log: Logger): List<Property.Subscription>? {
    val subscriptions = mutableListOf<Property.Subscription>()
    for (field in viewModel.fields) {
        val code = field.bindToView()
        if (code != 0) {
            log.error { "声明数据模型字段失败：${field.name}（${lastError()}）" }
            return null
        }
        subscriptions += field.observe { field.pushToView() }
    }
    return subscriptions
}

/** 把字段当前的初始值声明进视图层的数据模型。 */
private fun ModelField.bindToView(): Int = when (this) {
    is ModelField.Text -> name.cString { n -> source.value.cString { v -> ll_ui_model_bind_string(n, v) } }
    is ModelField.Integer -> name.cString { n -> ll_ui_model_bind_int(n, source.value) }
    is ModelField.Flag -> name.cString { n -> ll_ui_model_bind_bool(n, if (source.value) 1 else 0) }
}

/** 字段值变了：写入视图层数据模型并让它重绘。 */
private fun ModelField.pushToView(): Int = when (this) {
    is ModelField.Text -> name.cString { n -> source.value.cString { v -> ll_ui_model_set_string(n, v) } }
    is ModelField.Integer -> name.cString { n -> ll_ui_model_set_int(n, source.value) }
    is ModelField.Flag -> name.cString { n -> ll_ui_model_set_bool(n, if (source.value) 1 else 0) }
}

/** 文档里 `data-event-click="xxx"` 的回调；本函数不能捕获任何外部变量，上下文只能走 user_data。 */
private fun handleAction(userData: COpaquePointer?, name: CPointer<ByteVar>?) {
    val viewModel = userData?.asStableRef<UiProbeViewModel>()?.get() ?: return
    // name 由库内部持有、只在本次调用期间有效，立刻转成 Kotlin 字符串
    viewModel.dispatch(name?.toKString().orEmpty())
}
