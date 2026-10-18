package moe.lolosia.android.ui

import moe.lolosia.web.ui.platform.FrameResult
import moe.lolosia.web.ui.platform.UiBackend

/**
 * Android 视图层后端：把 [UiBackend] 的同步调用翻译成 JNI 命令。
 *
 * 帧循环由 `liblolosia.so` 的 SDL 线程持有，因此 [frame] 不会从 Kotlin 侧调用；
 * 除 [getString]（表单回读，内部同步等待一帧）外，其它调用都只是投递命令。
 *
 * @author 洛洛希雅Lolosia
 */
internal class AndroidUiBackend : UiBackend {

    private var actionHandler: ((String) -> Unit)? = null

    override fun create(title: String, width: Int, height: Int): Boolean = true

    override fun loadFont(filePath: String, family: String): Boolean = true

    override fun loadDocument(resourcePath: String): Boolean {
        NativeUi.nativeLoadDocument(resourcePath)
        return true
    }

    override fun loadDocumentText(rml: String): Boolean {
        NativeUi.nativeLoadDocumentText(rml)
        return true
    }

    override fun bindString(name: String, value: String): Boolean {
        NativeUi.nativeBindString(name, value)
        return true
    }

    override fun bindInt(name: String, value: Int): Boolean {
        NativeUi.nativeBindInt(name, value)
        return true
    }

    override fun bindBool(name: String, value: Boolean): Boolean {
        NativeUi.nativeBindBool(name, value)
        return true
    }

    override fun setString(name: String, value: String): Boolean {
        NativeUi.nativeSetString(name, value)
        return true
    }

    override fun setInt(name: String, value: Int): Boolean {
        NativeUi.nativeSetInt(name, value)
        return true
    }

    override fun setBool(name: String, value: Boolean): Boolean {
        NativeUi.nativeSetBool(name, value)
        return true
    }

    override fun getString(name: String): String? = NativeUi.nativeGetString(name)

    override fun getInt(name: String): Int? = null

    override fun getBool(name: String): Boolean? = null

    override fun setActionHandler(handler: (String) -> Unit) {
        actionHandler = handler
    }

    override fun frame(): FrameResult = FrameResult.Continue

    override fun wake(): Boolean {
        NativeUi.nativeWake()
        return true
    }

    override fun lastError(): String = ""

    override fun destroy() = Unit

    /** native 文档动作 → 业务入口；由 [AndroidUiHost] 调到主线程。 */
    fun dispatchAction(name: String) {
        actionHandler?.invoke(name)
    }
}
