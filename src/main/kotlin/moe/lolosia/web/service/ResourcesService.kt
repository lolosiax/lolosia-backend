package moe.lolosia.web.service

import moe.lolosia.web.api.ResourcesApi
import moe.lolosia.web.config.SConfig
import moe.lolosia.web.util.bundle.TreeElementMap
import moe.lolosia.web.util.bundle.TreeElementMap.Companion.getOrNull
import moe.lolosia.web.util.bundle.bundleScope
import moe.lolosia.web.util.property.MutableProperty
import moe.lolosia.web.util.property.Property
import moe.lolosia.web.util.session.Context
import moe.lolosia.web.util.success
import jakarta.annotation.PostConstruct
import kotlinx.coroutines.*
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.core.io.ResourceLoader
import org.springframework.http.*
import org.springframework.stereotype.Service
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.awaitBody
import org.springframework.web.reactive.function.client.awaitExchange
import org.springframework.web.server.ServerWebExchange
import java.io.File
import java.io.FileInputStream
import java.io.OutputStream
import java.net.URI
import java.security.MessageDigest
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.imageio.ImageIO
import kotlin.io.path.Path
import kotlin.io.path.absolute
import kotlin.io.path.extension
import kotlin.io.path.name
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.core.io.FileSystemResource
import java.nio.file.Path

@Service
class ResourcesService {

    final var resourcesMeta = TreeElementMap<String, FileData>()
        private set

    final var metadataChecking by MutableProperty(false)
        private set

    val logger = LoggerFactory.getLogger(ResourcesService::class.java)

    val fileCount by fileCount0
    val fileProcessed by fileProcessed0
    val byteCount by byteCount0
    val byteProcessed by byteProcessed0

    @Autowired
    private lateinit var resourceLoader: ResourceLoader

    companion object {
        private val WEBP_CACHE_DIR = File("work/cache/webp")
        private val IMAGE_MIME_TYPES = setOf(
            "image/png", "image/jpeg", "image/jpg", "image/gif",
            "image/bmp", "image/tiff", "image/webp"
        )
    }

    /** 按 MD5 粒度的 WebP 生成锁 */
    private val webpLocks = ConcurrentHashMap<String, Mutex>()

    /** 用于元数据写入的全局锁 */
    private val metadataMutex = Mutex()

    suspend fun get(
        exchange: ServerWebExchange,
        baseDir: String,
        url: String,
        webp: Boolean = false
    ): ResponseEntity<*> {

        val location = Path("${baseDir}${url}").absolute()
        val res = resourceLoader.getResource("file:$location")
        if (!res.exists()) return ResponseEntity.notFound().build<Any>()

        val mime = MediaTypeFactory.getMediaType(location.name)
            .orElse(MediaType.APPLICATION_OCTET_STREAM)!!

        // WebP 模式：仅对图片类型生效
        if (webp && mime.type == "image" && mime.subtype != "webp") {
            try {
                val md5 = resolveMd5(location)
                if (md5 != null) {
                    val webpFile = ensureWebp(location, md5)
                    return ResponseEntity.ok()
                        .cacheControl(CacheControl.maxAge(1, TimeUnit.HOURS))
                        .contentType(MediaType.valueOf("image/webp"))
                        .contentLength(webpFile.length())
                        .body(FileSystemResource(webpFile))
                }
            } catch (e: Exception) {
                logger.warn("WebP 转换失败 (${location.name})，回退原图: ${e.message}")
            }
        }

        return ResponseEntity.ok()
            .cacheControl(CacheControl.maxAge(1, TimeUnit.HOURS))
            .contentType(mime)
            .contentLength(res.contentLength())
            .body(res)
    }

    suspend fun checkFiles(ctx: Context, path: String): ResponseEntity<ByteArray> {

        var root: TreeElementMap<String, FileData>? = resourcesMeta

        path.split("/")
            .filter { it.isNotEmpty() }
            .forEach { root = root.getOrNull(it) }

        if (root == null) return success(null)

        val rs = root!!.flatMap { k, it ->
            if (it == null) return@flatMap null
            bundleScope {
                use(it::md5)
                use(it::lastUpdate)
                use(it::size)
                "name" set k.joinToString("/")
            }
        }

        return success(rs)
    }

    // ═══════════════════════════════════════
    //  WebP 缓存支持
    // ═══════════════════════════════════════

    /**
     * 解析文件 MD5，优先查 metadata 缓存，未命中则实时计算并回写。
     */
    private suspend fun resolveMd5(filePath: Path): String? {
        return withContext(Dispatchers.IO) {
            val file = filePath.toFile()
            if (!file.isFile) return@withContext null

            // 从 metadata 树查找
            val relPath = filePath.toString().replace('\\', '/')
            // 格式如 "work/resources/images/foo.png" → 切掉 "work" 前缀
            val parts = relPath.split("/").drop(1) // 去掉 "work"
            if (parts.isNotEmpty()) {
                var node: TreeElementMap<String, FileData>? = resourcesMeta
                for (part in parts) {
                    node = node?.getOrNull(part)
                    if (node == null) break
                }
                val cached = node?.get()
                if (cached != null) return@withContext cached.md5
            }

            // 未命中 → 实时计算 MD5 并回写到 metadata
            val md5 = file.inputStream().use { stream ->
                val digest = MessageDigest.getInstance("MD5")
                val buf = ByteArray(8192)
                var read: Int
                while (stream.read(buf).also { read = it } != -1) {
                    digest.update(buf, 0, read)
                }
                HexFormat.of().formatHex(digest.digest())
            }

            // 回写 metadata（加锁防并发 refreshMetadata）
            metadataMutex.withLock {
                var node = resourcesMeta
                for (part in parts) {
                    node = node[part] // TreeElementMap.get 自动创建子节点
                }
                node.set(FileData(file.lastModified(), file.length(), md5))
            }

            return@withContext md5
        }
    }

    /**
     * 确保 WebP 缓存文件存在，不存在则生成（按 MD5 粒度加锁）。
     */
    private suspend fun ensureWebp(sourcePath: Path, md5: String): File {
        WEBP_CACHE_DIR.mkdirs()
        val cacheFile = File(WEBP_CACHE_DIR, "${md5}.webp")

        if (cacheFile.exists() && cacheFile.length() > 0) return cacheFile

        withContext(Dispatchers.IO) {
            val lock = webpLocks.getOrPut(md5) { Mutex() }
            lock.withLock {
                // 双重检查：拿到锁后可能别的线程已经生成好了
                if (cacheFile.exists() && cacheFile.length() > 0) {
                    return@withLock
                }

                val src = sourcePath.toFile()
                logger.info("生成 WebP 缓存: ${src.name} → ${cacheFile.name}")
                val image = ImageIO.read(src)
                    ?: throw IllegalStateException("无法读取图片: ${src.name}")
                ImageIO.write(image, "webp", cacheFile)
            }
            // 清理不再使用的锁条目（惰性）
            webpLocks.remove(md5, lock)

        }
        return cacheFile
    }

    // ═══════════════════════════════════════
    //  定时清理过期 WebP 缓存
    // ═══════════════════════════════════════

    /** 启动时清理一次 */
    @EventListener(ApplicationReadyEvent::class)
    fun cleanWebpCacheOnStartup() {
        cleanExpiredWebpCache()
    }

    /** 每天凌晨 4:00 清理超 7 天未访问的 WebP 缓存 */
    @Scheduled(cron = "0 0 4 * * ?")
    fun cleanExpiredWebpCache() {
        if (!WEBP_CACHE_DIR.isDirectory) return
        val cutoff = System.currentTimeMillis() - 7L * 24 * 60 * 60 * 1000
        var deleted = 0
        WEBP_CACHE_DIR.listFiles()?.forEach { file ->
            if (file.isFile && file.lastModified() < cutoff) {
                if (file.delete()) deleted++
            }
        }
        if (deleted > 0) {
            logger.info("已清理 $deleted 个过期 WebP 缓存文件")
        }
    }

    @PostConstruct
    fun init() {
        CoroutineScope(Dispatchers.IO).launch {
            refreshMetadata()
        }
    }

    /**
     * 更新资源信息
     */
    fun refreshMetadata() {
        synchronized(this) {
            if (metadataChecking) return
        }
        metadataChecking = true
        fileCount0.value = 0
        fileProcessed0.value = 0
        byteCount0.value = 0
        byteProcessed0.value = 0
        logger.info("开始校检资源文件完整性……")
        try {
            val map = TreeElementMap<String, FileData>()
            val resDir = File("work/resources")
            resDir.mkdirs()
            countFiles(resDir)
            logger.info("已找到 $fileCount 个文件，共 ${byteCount / 1048576L}MB。")
            refreshMetadata0(map["resources"], resDir)
            logger.info("校检资源文件完成！")
            resourcesMeta = map
        } catch (e: Throwable) {
            logger.error("校检资源文件失败: ${e.message}")
            throw e
        } finally {
            metadataChecking = false
        }
    }

    /**
     * 对指定文件夹进行同步
     * @param url 远程服务器URL路径
     * @param pos 本地 TreeMap 对应文件夹路径
     * @param dir 本地文件系统文件夹路径
     */
    suspend fun resourcesSync(
        url: String,
        pos: List<String>,
        dir: File,
    ): SyncJob {
        val serverRoot = TreeElementMap<String, FileData>()
        var localRoot = resourcesMeta
        pos.filter { it.isNotEmpty() }.forEach { localRoot = localRoot[it] }
        val files = ResourcesApi.checkFiles(url) ?: emptyList()
        if (files.isEmpty()) {
            logger.warn("远程服务器返回资源${url}为空。")
        }
        files.forEach { meta ->
            var node = serverRoot
            meta["name"]?.toString()
                ?.split("/")
                ?.filter { it.isNotEmpty() }
                ?.forEach { node = node[it] }
                ?: return@forEach
            val ele = FileData(
                (meta["lastUpdate"] as Number).toLong(),
                (meta["size"] as Number).toLong(),
                meta["md5"] as String
            )
            node.set(ele)
        }

        val jobs = localRoot.zip(serverRoot).flatMap { k, node ->
            // k 相对路径

            val file = dir.resolve(k.joinToString("/"))
            if (node == null) return@flatMap null
            val (local, remote) = node
            if (remote == null && local == null) return@flatMap null
            FileSyncJob(this, pos + k, (listOf(url) + k).joinToString("/"), file, local, remote)
        }

        return SyncJobImpl(jobs)
    }

    data class FileData(
        val lastUpdate: Long,
        val size: Long,
        val md5: String,
    )


    interface SyncJob {
        val byteCount: Property<Long>
        val fileCount: Property<Long>
        val byteProcessed: Property<Long>
        val fileProcessed: Property<Long>
        suspend fun run()
    }
}


private val fileCount0 = MutableProperty(0L)
private val fileProcessed0 = MutableProperty(0L)
private val byteCount0 = MutableProperty(0L)
private val byteProcessed0 = MutableProperty(0L)

private fun countFiles(file: File) {
    if (file.isDirectory) {
        file.listFiles()?.forEach(::countFiles)
        return
    }
    fileCount0.value += 1
    byteCount0.value += file.length()
}


private fun refreshMetadata0(node: TreeElementMap<String, ResourcesService.FileData>, file: File) {
    if (file.isDirectory) {
        file.listFiles()?.forEach {
            refreshMetadata0(node[it.name], it)
        }
        return
    }
    val os = MD5OutputStream()
    file.inputStream().use { it.transferTo(os) }

    node.set(ResourcesService.FileData(file.lastModified(), file.length(), os.result))
    fileProcessed0.value += 1
}

private class MD5OutputStream : OutputStream() {
    private val util = MessageDigest.getInstance("MD5")
    override fun write(b: Int) {
        util.update(b.toByte())
        byteProcessed0.value += 1
    }

    override fun write(b: ByteArray, off: Int, len: Int) {
        util.update(b, off, len)
        byteProcessed0.value += len
    }

    val result: String
        get() {
            return HexFormat.of().formatHex(util.digest())
        }
}

/**
 * @param svc 资源服务
 * @param path 本地 TreeMap 所对应文件路径
 * @param url 远程服务器文件路径
 * @param file 本地文件系统文件路径
 * @param local 本地文件信息
 * @param remote 远程文件信息
 */
private class FileSyncJob(
    val svc: ResourcesService,
    val path: List<String>,
    val url: String,
    val file: File,
    val local: ResourcesService.FileData?,
    val remote: ResourcesService.FileData?,
) {
    val byteProcessed = MutableProperty(0L)
    val logger = LoggerFactory.getLogger(ResourcesService::class.java)
    var exception: Throwable? = null

    suspend fun run() {
        var retry = 0;
        exception = null
        suspend fun doRetry() {
            try {
                run0()
                exception = null
            } catch (e: Throwable) {
                exception = e
                logger.error("同步文件时发生异常", e)
            }
        }
        while (retry < 3) {
            doRetry()
            if (exception == null) return
            retry++
        }
        throw exception!!
    }

    private suspend fun run0() {
        var item = svc.resourcesMeta
        path.filter { it.isNotEmpty() }.forEach { item = item[it] }
        // 远程文件不存在时，直接删除
        if (remote == null) {
            if (file.exists()) file.delete()
            item.remove()
            return
        }
        // 本地文件不存在或版本不一致时，下载文件。
        if (local == null || local.md5 != remote.md5) {
            byteProcessed.value = 0
            ResourcesApi.downloadFiles(url, file, byteProcessed)
            item.set(remote)
        }
    }
}

private class SyncJobImpl(val jobs: List<FileSyncJob>) : ResourcesService.SyncJob, CoroutineScope {
    override val coroutineContext = Dispatchers.IO

    override val byteCount: MutableProperty<Long> = MutableProperty(
        jobs.fold(0L) { acc, it -> acc + (it.remote?.size ?: 0L) }
    )
    override val fileCount: MutableProperty<Long> = MutableProperty(jobs.size.toLong())
    override val byteProcessed: MutableProperty<Long> = MutableProperty(0L)
    override val fileProcessed: MutableProperty<Long> = MutableProperty(0L)

    val mSucceeded: MutableSet<FileSyncJob> = mutableSetOf()
    val mFailed: MutableSet<FileSyncJob> = mutableSetOf()

    override suspend fun run() {
        val kJob = mutableListOf<Job>()
        (jobs.toSet() - mSucceeded).map {
            kJob += (this as CoroutineScope).launch {
                try {
                    val listener = it.byteProcessed.addChangeListener { o, n ->
                        if (n <= o) return@addChangeListener
                        synchronized(byteProcessed) {
                            byteProcessed.value += (n - o)
                        }
                    }
                    it.run()
                    fileProcessed.value += 1
                    mSucceeded += it
                    it.byteProcessed.removeListener(listener)
                } catch (e: Throwable) {
                    synchronized(byteProcessed) {
                        byteProcessed.value -= it.byteProcessed.value
                    }
                    mFailed += it
                    throw e
                }
            }
        }
        joinAll(*kJob.toTypedArray())
    }

}