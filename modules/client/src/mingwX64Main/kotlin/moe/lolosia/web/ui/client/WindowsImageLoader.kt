package moe.lolosia.web.ui.client

import moe.lolosia.web.ui.image.ImageLoader
import moe.lolosia.web.util.log.Log

/**
 * Windows 侧图片登记：写进 [WindowsUiBackend] 的资源表，由 RmlUi 读文件回调取走。
 *
 * @author 洛洛希雅Lolosia
 */
class WindowsImageLoader(private val backend: WindowsUiBackend) : ImageLoader {

    override fun register(path: String, bytes: ByteArray): String? {
        if (!backend.registerImage(path, bytes)) {
            Log.of("UiClient").warn { "图片登记失败：$path (${bytes.size} bytes)" }
            return null
        }
        return path
    }
}
