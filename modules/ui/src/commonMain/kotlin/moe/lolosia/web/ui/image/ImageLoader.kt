package moe.lolosia.web.ui.image

/**
 * 把已下载的图片字节登记成视图层可引用的资源路径。
 *
 * 取字节由 `RemoteApi` 负责（与其它接口共用同一套 HTTP/信封），本接口只处理
 * 「字节 → 视图层资源」这一段；Windows 在 RmlUi 的读文件回调里查内存表，
 * Android 走 JNI 的同一套回调。
 *
 * @author 洛洛希雅Lolosia
 */
interface ImageLoader {

    /** 登记资源；成功返回视图层可用的路径，失败返回 `null`。 */
    fun register(path: String, bytes: ByteArray): String?
}
