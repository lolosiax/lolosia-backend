package moe.lolosia.android.ui

import moe.lolosia.web.ui.image.ImageLoader

/**
 * Android 图片登记：把下载好的字节交给 native 内存表，解码由 native 侧的 BitmapFactory 完成。
 *
 * @author 洛洛希雅Lolosia
 */
internal class AndroidImageLoader : ImageLoader {

    override fun register(path: String, bytes: ByteArray): String? = runCatching {
        NativeUi.nativeRegisterImage(path, bytes)
        path
    }.getOrNull()
}
