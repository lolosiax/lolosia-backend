package moe.lolosia.web.service

import moe.lolosia.web.util.fs.FileStore
import moe.lolosia.web.util.image.Image
import moe.lolosia.web.util.image.ImageCodecException
import moe.lolosia.web.util.image.ImageFormat
import moe.lolosia.web.util.router.Service
import okio.ByteString.Companion.toByteString

/**
 * WebP 磁盘缓存：按源文件内容的 MD5 存到 `<workDir>/cache/webp/{md5}.webp`。
 *
 * 只落盘不占内存（开发板内存小），命中直接返回；转换失败返回 `null`，调用方回退原图。
 * 与旧 JVM 版的缓存路径和键完全一致，已有的缓存文件可直接复用。
 *
 * @author 洛洛希雅Lolosia
 */
@Service
class WebpCache(private val store: FileStore) {

    /** 取 [source] 的 WebP 版本；无法转换时返回 `null`。 */
    fun get(source: ByteArray): ByteArray? {
        val path = "cache/webp/${source.toByteString().md5().hex()}.webp"
        store.read(path)?.let { return it }
        val webp = try {
            Image(source).toByteArray(ImageFormat.WEBP)
        } catch (e: ImageCodecException) {
            return null
        }
        store.write(path, webp)
        return webp
    }
}
