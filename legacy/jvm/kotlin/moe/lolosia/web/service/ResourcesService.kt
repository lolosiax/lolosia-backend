package moe.lolosia.web.service

import moe.lolosia.web.annotation.Service
import org.slf4j.LoggerFactory
import java.io.File
import java.security.MessageDigest
import java.util.HexFormat
import java.util.concurrent.ConcurrentHashMap
import javax.imageio.ImageIO
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * 资源加载服务：`/res/` → `work/` 的静态文件解析。
 *
 * - **无内存缓存**：开发板内存小，每次直接读文件系统；
 *   HTTP 缓存协商（Last-Modified/ETag → 304）由响应头 + ConditionalHeaders 插件完成，零内存开销。
 * - **WebP 磁盘缓存**：`?webp=true` 时把图片转成 webp 存 `work/cache/webp/{md5}.webp`，
 *   只占磁盘不占内存，命中后直接返回。
 *
 * @author 洛洛希雅Lolosia
 * @since 2024-12-02 09:51
 */
@Service
class ResourcesService {

    private val logger = LoggerFactory.getLogger(ResourcesService::class.java)

    private val root = File("work")
    private val webpCacheDir = File("work/cache/webp")

    /** 按 MD5 粒度的 WebP 生成锁 */
    private val webpLocks = ConcurrentHashMap<String, Mutex>()

    private val imageTypes = setOf("png", "jpg", "jpeg", "gif", "bmp", "tiff", "webp")

    /**
     * 解析请求的资源文件。
     * @param path 相对 work 目录的路径（来自 /res/{path...}）
     * @param webp 为 true 时尝试返回 WebP 版本（磁盘缓存）
     * @return 可服务的文件，不存在或越界时返回 null
     */
    suspend fun resolve(path: String, webp: Boolean): File? {
        var file = File(root, path).normalize()
        // 路径穿越防护：解析后必须仍在 work 目录内
        if (!file.absolutePath.startsWith(root.absolutePath + File.separator)) return null

        if (file.isDirectory) file = File(file, "index.html")
        if (!file.isFile) return null

        if (!webp) return file
        val ext = file.extension.lowercase()
        if (ext !in imageTypes || ext == "webp") return file

        return try {
            ensureWebp(file)
        } catch (e: Throwable) {
            logger.warn("WebP 转换失败 (${file.name})，回退原图: ${e.message}")
            file
        }
    }

    /** 确保 WebP 缓存文件存在，不存在则生成（按 MD5 粒度加锁） */
    private suspend fun ensureWebp(source: File): File {
        val md5 = md5(source)
        webpCacheDir.mkdirs()
        val cacheFile = File(webpCacheDir, "$md5.webp")
        if (cacheFile.exists() && cacheFile.length() > 0) return cacheFile

        withContext(Dispatchers.IO) {
            val lock = webpLocks.getOrPut(md5) { Mutex() }
            lock.withLock {
                // 双重检查：拿到锁后可能别的请求已经生成
                if (cacheFile.exists() && cacheFile.length() > 0) return@withLock
                logger.info("生成 WebP 缓存: ${source.name} → ${cacheFile.name}")
                val image = ImageIO.read(source)
                    ?: throw IllegalStateException("无法读取图片: ${source.name}")
                ImageIO.write(image, "webp", cacheFile)
            }
            // 惰性清理不再使用的锁条目
            webpLocks.remove(md5, lock)
        }
        return cacheFile
    }

    private fun md5(file: File): String = file.inputStream().use { stream ->
        val digest = MessageDigest.getInstance("MD5")
        val buf = ByteArray(8192)
        var read: Int
        while (stream.read(buf).also { read = it } != -1) {
            digest.update(buf, 0, read)
        }
        HexFormat.of().formatHex(digest.digest())
    }
}
