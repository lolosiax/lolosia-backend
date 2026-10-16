package moe.lolosia.web.ui

import kotlinx.cinterop.*
import moe.lolosia.thirdparty.RgbaImage
import moe.lolosia.thirdparty.gif.Gif
import moe.lolosia.thirdparty.jpeg.Jpeg
import moe.lolosia.thirdparty.png.Png
import moe.lolosia.thirdparty.webp.Webp
import moe.lolosia.web.ui.experiment.ModelField
import moe.lolosia.web.ui.experiment.UiProbeViewModel
import moe.lolosia.web.ui.mvvm.Property
import moe.lolosia.web.ui.native.*
import moe.lolosia.web.util.classpath.Classpath
import moe.lolosia.web.util.classpath.installPlatformClasspath
import moe.lolosia.web.util.image.Image
import moe.lolosia.web.util.image.ImageFormat
import moe.lolosia.web.util.log.Log
import moe.lolosia.web.util.log.Logger
import platform.posix.memcpy

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
    // 资源回调同理：classpath 引用也经 user_data 传过去
    val resourcesRef = StableRef.create(UiResourceHolder(classpath))
    var subscriptions: List<Property.Subscription> = emptyList()
    try {
        // 资源回调要排在加载字体之前：字体同样走 RmlUi 的文件接口，
        // 而资源回调是文件接口判断「这个路径是不是宿主资源」的依据。
        ll_ui_set_resource_loader(
            staticCFunction(::handleReadFile),
            staticCFunction(::handleDecodeImage),
            resourcesRef.asCPointer(),
        )
        loadFont(log)

        val bound = bindModel(viewModel, log)
        if (bound == null) return 1
        subscriptions = bound
        ll_ui_set_action_callback(staticCFunction(::handleAction), viewModelRef.asCPointer())

        if (document.cString { ll_ui_load_document(it) } != 0) {
            log.error { "加载文档失败：${lastError()}" }
            return 1
        }

        // 调试器要在文档之后启用：它自己往上下文里挂几个文档，晚加载才画在最上层。
        // 启不来不算致命（界面照常可用），但要说清楚。
        if (ll_ui_enable_debugger() == 0) {
            log.info { "内置调试器已启用：F8 开关（元素树 / 数据模型 / 日志 / 元素边框）" }
        } else {
            log.warn { "内置调试器启用失败：${lastError()}" }
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

            // 表单控件改过值：等这一帧的事件处理（含视图层写回数据模型）跑完再读回来，
            // 免得当场读到写之前的旧值。
            if (viewModel.viewSyncPending) {
                viewModel.viewSyncPending = false
                viewModel.fields.forEach { it.pullFromView(log) }
                viewModel.onFormSynced()
            }
        }
    } finally {
        subscriptions.forEach { it.cancel() }
        viewModelRef.dispose()
        resourcesRef.dispose()
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
    is ModelField.Decimal -> name.cString { n -> ll_ui_model_bind_float(n, source.value) }
    is ModelField.Flag -> name.cString { n -> ll_ui_model_bind_bool(n, if (source.value) 1 else 0) }
}

/** 字段值变了：写入视图层数据模型并让它重绘。 */
private fun ModelField.pushToView(): Int = when (this) {
    is ModelField.Text -> name.cString { n -> source.value.cString { v -> ll_ui_model_set_string(n, v) } }
    is ModelField.Integer -> name.cString { n -> ll_ui_model_set_int(n, source.value) }
    is ModelField.Decimal -> name.cString { n -> ll_ui_model_set_float(n, source.value) }
    is ModelField.Flag -> name.cString { n -> ll_ui_model_set_bool(n, if (source.value) 1 else 0) }
}

/**
 * 把视图层（表单控件）可能刚改过的值读回 [source]。
 *
 * 这是 MVVM 里那条反向数据流：视图不是只读的，输入框里敲的字、滑杆拖到的位置都写进了
 * 视图层的数据模型，宿主不读回来就会一直拿着旧值 —— 而且下次推送时会把用户的输入盖掉。
 * 单个字段读失败只记一条日志，不影响其它字段。
 */
private fun ModelField.pullFromView(log: Logger) {
    if (this is ModelField.Text) {
        val next = readModelString(name)
        if (next == null) log.error { "读回字段失败：$name（${lastError()}）" } else source.value = next
        return
    }
    if (this is ModelField.Integer) {
        val next = readModelInt(name)
        if (next == null) log.error { "读回字段失败：$name（${lastError()}）" } else source.value = next
        return
    }
    if (this is ModelField.Decimal) {
        val next = readModelFloat(name)
        if (next == null) log.error { "读回字段失败：$name（${lastError()}）" } else source.value = next
        return
    }
    if (this is ModelField.Flag) {
        val next = readModelBool(name)
        if (next == null) log.error { "读回字段失败：$name（${lastError()}）" } else source.value = next
    }
}

/** 读回字符串字段；与资源回调同一套「两次调用」约定（先问字节数，再取内容）。失败返回 null。 */
private fun readModelString(name: String): String? = name.cString { n ->
    val size = ll_ui_model_get_string(n, null, 0)
    if (size < 0) return@cString null
    if (size == 0) return@cString ""
    val buffer = ByteArray(size)
    val written = buffer.usePinned { ll_ui_model_get_string(n, it.addressOf(0), size) }
    if (written != size) null else buffer.decodeToString()
}

private fun readModelInt(name: String): Int? = memScoped {
    val out = alloc<IntVar>()
    if (name.cString { ll_ui_model_get_int(it, out.ptr) } == 0) out.value else null
}

private fun readModelFloat(name: String): Float? = memScoped {
    val out = alloc<FloatVar>()
    if (name.cString { ll_ui_model_get_float(it, out.ptr) } == 0) out.value else null
}

private fun readModelBool(name: String): Boolean? = memScoped {
    val out = alloc<IntVar>()
    if (name.cString { ll_ui_model_get_bool(it, out.ptr) } == 0) out.value != 0 else null
}

/** 文档里 `data-event-click="xxx"` 的回调；本函数不能捕获任何外部变量，上下文只能走 user_data。 */
private fun handleAction(userData: COpaquePointer?, name: CPointer<ByteVar>?) {
    val viewModel = userData?.asStableRef<UiProbeViewModel>()?.get() ?: return
    // name 由库内部持有、只在本次调用期间有效，立刻转成 Kotlin 字符串
    viewModel.dispatch(name?.toKString().orEmpty())
}

/** 资源回调要用的宿主上下文：资源从 classpath 取（发布时是内嵌 zip，磁盘上并不存在）。 */
private class UiResourceHolder(val classpath: Classpath)

/**
 * 读一个资源文件（C 侧 RmlUi 要加载文档、样式、图片时都会走这里）。
 *
 * 两次调用约定：`out` 为空或容量不足时只回所需字节数，容量够才写数据。
 * 返回负数是失败（文件不存在等），C 侧会把它当「资源缺失」处理。
 */
private fun handleReadFile(
    userData: COpaquePointer?,
    path: CPointer<ByteVar>?,
    out: CPointer<UByteVar>?,
    capacity: Int,
): Int {
    val holder = userData?.asStableRef<UiResourceHolder>()?.get() ?: return -1
    val name = path?.toKString() ?: return -1
    val bytes = holder.classpath.read(name) ?: return -1
    if (out == null || capacity < bytes.size) return bytes.size
    bytes.usePinned { memcpy(out, it.addressOf(0), bytes.size.convert()) }
    return bytes.size
}

/**
 * 把编码字节解成 RGBA8，复用 thirdparty 下各图像库门面（与 `Image` 内部用的是同一批实现）。
 *
 * 两次调用约定同上；第一次（`out` 为空）只按文件头报尺寸，不解码像素，
 * 所以这里先用 `Image.sizeOf`，避免为了探大小白解一遍。
 */
private fun handleDecodeImage(
    userData: COpaquePointer?,
    bytes: CPointer<UByteVar>?,
    size: Int,
    out: CPointer<UByteVar>?,
    capacity: Int,
    outWidth: CPointer<IntVar>?,
    outHeight: CPointer<IntVar>?,
): Int {
    if (userData?.asStableRef<UiResourceHolder>()?.get() == null) return -1
    if (bytes == null || size <= 0) return -1

    val data = ByteArray(size).also { buffer -> buffer.usePinned { memcpy(it.addressOf(0), bytes, size.convert()) } }
    val dimensions = Image.sizeOf(data) ?: return -1
    outWidth?.pointed?.value = dimensions.width
    outHeight?.pointed?.value = dimensions.height

    val needed = dimensions.width * dimensions.height * 4
    if (out == null || capacity < needed) return needed

    val rgba = decodeRgba(Image.typeOf(data), data) ?: return -1
    if (rgba.pixels.size != needed) return -1
    rgba.pixels.usePinned { memcpy(out, it.addressOf(0), needed.convert()) }
    return needed
}

/** 按文件头识别结果分派到对应的解码门面；识别不出（或格式不支持）返回 `null`。 */
private fun decodeRgba(format: ImageFormat, bytes: ByteArray): RgbaImage? = when (format) {
    ImageFormat.PNG -> Png.decode(bytes)
    ImageFormat.JPEG -> Jpeg.decode(bytes)
    ImageFormat.GIF -> Gif.decode(bytes)
    ImageFormat.WEBP -> Webp.decode(bytes)
    ImageFormat.UNKNOWN -> null
}
