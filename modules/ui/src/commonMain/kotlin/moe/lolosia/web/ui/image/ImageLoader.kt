package moe.lolosia.web.ui.image

/**
 * 异步图片加载：远程取字节、缓存、解码后交给视图层。
 *
 * 本期先定义接口；Windows 在 M2 接入内存资源注册，Android 走 BitmapFactory。
 *
 * @author 洛洛希雅Lolosia
 */
interface ImageLoader {

    /** 取图片字节；失败返回 `null`。 */
    suspend fun load(url: String, token: String? = null): ByteArray?

    /** 把字节登记成视图层可引用的资源路径；失败返回 `null`。 */
    fun register(path: String, bytes: ByteArray): String?
}
