package moe.lolosia.web.ui.client

import kotlinx.cinterop.*
import moe.lolosia.thirdparty.RgbaImage
import moe.lolosia.thirdparty.gif.Gif
import moe.lolosia.thirdparty.jpeg.Jpeg
import moe.lolosia.thirdparty.png.Png
import moe.lolosia.thirdparty.webp.Webp
import moe.lolosia.web.ui.native.*
import moe.lolosia.web.ui.platform.FrameResult
import moe.lolosia.web.ui.platform.UiBackend
import moe.lolosia.web.util.classpath.Classpath
import moe.lolosia.web.util.image.Image
import moe.lolosia.web.util.image.ImageFormat
import platform.posix.memcpy

/**
 * Windows 视图层后端：把 [UiBackend] 落到 `ll_ui_*` C ABI。
 *
 * 文档从 classpath 读，字体从磁盘读；业务状态全部留在 Kotlin。
 *
 * @param classpath 内嵌资源。
 * @author 洛洛希雅Lolosia
 */
class WindowsUiBackend(private val classpath: Classpath) : UiBackend {

    private var actionRef: StableRef<ClientActionHolder>? = null
    private var resourceRef: StableRef<ClientResourceHolder>? = null

    /** 注册 classpath 资源与图片解码回调；必须在 [loadFont] 之前调用。 */
    fun installResources() {
        val ref = StableRef.create(ClientResourceHolder(classpath))
        resourceRef = ref
        ll_ui_set_resource_loader(
            staticCFunction(::handleReadFile),
            staticCFunction(::handleDecodeImage),
            ref.asCPointer(),
        )
    }

    override fun create(title: String, width: Int, height: Int): Boolean =
        title.cString { ll_ui_create(it, width, height) == 0 }

    override fun loadFont(filePath: String, family: String): Boolean =
        filePath.cString { path -> family.cString { name -> ll_ui_load_font(path, name) == 0 } }

    override fun loadDocument(resourcePath: String): Boolean {
        val rml = classpath.read(resourcePath)?.decodeToString() ?: return false
        return rml.cString { ll_ui_load_document(it) == 0 }
    }

    override fun loadDocumentText(rml: String): Boolean =
        rml.cString { ll_ui_load_document(it) == 0 }

    override fun bindString(name: String, value: String): Boolean =
        name.cString { n -> value.cString { v -> ll_ui_model_bind_string(n, v) == 0 } }

    override fun bindInt(name: String, value: Int): Boolean =
        name.cString { ll_ui_model_bind_int(it, value) == 0 }

    override fun bindBool(name: String, value: Boolean): Boolean =
        name.cString { ll_ui_model_bind_bool(it, if (value) 1 else 0) == 0 }

    override fun setString(name: String, value: String): Boolean =
        name.cString { n -> value.cString { v -> ll_ui_model_set_string(n, v) == 0 } }

    override fun setInt(name: String, value: Int): Boolean =
        name.cString { ll_ui_model_set_int(it, value) == 0 }

    override fun setBool(name: String, value: Boolean): Boolean =
        name.cString { ll_ui_model_set_bool(it, if (value) 1 else 0) == 0 }

    override fun getString(name: String): String? = name.cString { n ->
        val size = ll_ui_model_get_string(n, null, 0)
        if (size < 0) return@cString null
        if (size == 0) return@cString ""
        val buffer = ByteArray(size)
        val written = buffer.usePinned { ll_ui_model_get_string(n, it.addressOf(0), size) }
        if (written != size) null else buffer.decodeToString()
    }

    override fun getInt(name: String): Int? = memScoped {
        val out = alloc<IntVar>()
        if (name.cString { ll_ui_model_get_int(it, out.ptr) } == 0) out.value else null
    }

    override fun getBool(name: String): Boolean? = memScoped {
        val out = alloc<IntVar>()
        if (name.cString { ll_ui_model_get_bool(it, out.ptr) } == 0) out.value != 0 else null
    }

    override fun setActionHandler(handler: (String) -> Unit) {
        actionRef?.dispose()
        val holder = ClientActionHolder(handler)
        val ref = StableRef.create(holder)
        actionRef = ref
        ll_ui_set_action_callback(staticCFunction(::handleClientAction), ref.asCPointer())
    }

    override fun frame(): FrameResult = when (ll_ui_frame()) {
        0 -> FrameResult.Continue
        1 -> FrameResult.Close
        else -> FrameResult.Error
    }

    override fun wake(): Boolean = ll_ui_wake() == 0

    override fun lastError(): String = ll_ui_last_error()?.toKString().orEmpty()

    override fun destroy() {
        ll_ui_destroy()
        actionRef?.dispose()
        actionRef = null
        resourceRef?.dispose()
        resourceRef = null
    }
}

/** 动作回调的宿主上下文；staticCFunction 不能捕获外部变量。 */
private class ClientActionHolder(val handler: (String) -> Unit)

/** 资源回调的宿主上下文。 */
private class ClientResourceHolder(val classpath: Classpath)

/** 文档里 `data-event-click="action('x')"` 的回调。 */
private fun handleClientAction(userData: COpaquePointer?, name: CPointer<ByteVar>?) {
    val holder = userData?.asStableRef<ClientActionHolder>()?.get() ?: return
    holder.handler(name?.toKString().orEmpty())
}

/** 读 classpath 资源；两次调用约定。 */
private fun handleReadFile(
    userData: COpaquePointer?,
    path: CPointer<ByteVar>?,
    out: CPointer<UByteVar>?,
    capacity: Int,
): Int {
    val holder = userData?.asStableRef<ClientResourceHolder>()?.get() ?: return -1
    val name = path?.toKString() ?: return -1
    val bytes = holder.classpath.read(name) ?: return -1
    if (out == null || capacity < bytes.size) return bytes.size
    bytes.usePinned { memcpy(out, it.addressOf(0), bytes.size.convert()) }
    return bytes.size
}

/** 解码图片；复用仓库里的 C 编解码门面。 */
private fun handleDecodeImage(
    userData: COpaquePointer?,
    bytes: CPointer<UByteVar>?,
    size: Int,
    out: CPointer<UByteVar>?,
    capacity: Int,
    outWidth: CPointer<IntVar>?,
    outHeight: CPointer<IntVar>?,
): Int {
    if (userData?.asStableRef<ClientResourceHolder>()?.get() == null) return -1
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

/** 按文件头识别结果分派到对应的解码门面；识别不出返回 `null`。 */
private fun decodeRgba(format: ImageFormat, bytes: ByteArray): RgbaImage? = when (format) {
    ImageFormat.PNG -> Png.decode(bytes)
    ImageFormat.JPEG -> Jpeg.decode(bytes)
    ImageFormat.GIF -> Gif.decode(bytes)
    ImageFormat.WEBP -> Webp.decode(bytes)
    ImageFormat.UNKNOWN -> null
}

/** 按 UTF-8 传入并补终止符；C 侧只在本调用期间读取。 */
private inline fun <R> String.cString(block: (CPointer<ByteVar>) -> R): R =
    (this + '\u0000').encodeToByteArray().usePinned { block(it.addressOf(0)) }
