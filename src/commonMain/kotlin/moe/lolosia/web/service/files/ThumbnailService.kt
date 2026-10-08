package moe.lolosia.web.service.files

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import moe.lolosia.web.database.Database
import moe.lolosia.web.database.repository.MediaAssetRepository
import moe.lolosia.web.dto.FileThumbEvent
import moe.lolosia.web.dto.SignedFile
import moe.lolosia.web.service.SseService
import moe.lolosia.web.service.permission.PermissionResolver
import moe.lolosia.web.service.permission.Permissions
import moe.lolosia.web.settings.AppSettings
import moe.lolosia.web.util.log.Log
import moe.lolosia.web.util.router.Injectable
import moe.lolosia.web.util.router.Service
import moe.lolosia.web.util.time.nowEpochMs
import kotlin.time.Clock

/**
 * 缩略图后台生成服务。
 *
 * **一条工作协程**串行处理：机械盘只有 30MB/s，并发解码只会让磁头来回跳、总体更慢。
 * 队列用「优先队列 + 去重集合」实现：
 * - 用户主动打开目录 → [prioritize]，该目录的图片插到**队首**，立刻生成；
 * - 后台全量扫描 → 按配置间隔递归扫描，把变了或没生成过的图片排到**队尾**。
 *
 * 生成完成后通过 SSE 事件 `files:thumb` 通知前端（见 [FileThumbEvent]），前端据此把对应项
 * 标成「已有缩略图」并刷新图片。
 *
 * 整个服务由 `files.thumbnail.enabled` 开关控制：**开发机与线上机只能开一台**，否则两边同时
 * 往同一份缓存目录写，会互相覆盖、也会把机械盘的两端都拖慢。
 *
 * 扫描判定「文件有没有变」只用源文件的 size + mtime（记在缓存元数据里，见 [ThumbnailCache]），
 * 不去读原图内容算哈希 —— 那样在 30MB/s 的盘上根本跑不动。
 *
 * @author 洛洛希雅Lolosia
 */
@Injectable
@Service
class ThumbnailService(
    settings: AppSettings,
    private val sse: SseService,
    private val browser: FileBrowser,
    private val signer: FileSigner,
    private val db: Database,
    private val resolver: PermissionResolver,
    private val assets: MediaAssetRepository,
) {

    private val log = Log.of("Thumb")

    private val config = settings.files.thumbnail
    private val cache = browser.cache

    /** 待生成队列（队首优先）。 */
    private val mutex = Mutex()
    private val queue = ArrayDeque<String>()

    /** 已在队列里的路径，避免同一张图被反复入队。 */
    private val queued = mutableSetOf<String>()

    /** 只用来唤醒工作协程（conflated：多次入队合并成一次唤醒）。 */
    private val signal = Channel<Unit>(Channel.CONFLATED)

    /** 自持作用域：队列与循环都活在请求之外，进程退出即停（与 SessionManager / SseService 同构）。 */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** 是否已启用（未启用时所有入口都是空操作）。 */
    val enabled: Boolean get() = config.enabled

    /** 已生成档位。 */
    val sizes: List<Int> get() = cache.orderedSizes

    /** 启动后台工作协程与周期性全量扫描；未启用时只记一条日志。 */
    fun start() {
        if (!config.enabled) {
            log.info { "缩略图生成未启用（files.thumbnail.enabled=false）" }
            return
        }
        val interval = config.scanIntervalSeconds.coerceAtLeast(MIN_SCAN_INTERVAL_SECONDS)
        log.info { "缩略图生成已启用：根=${browser.root} 档位=${cache.orderedSizes} 扫描间隔=${interval}s" }
        scope.launch { workerLoop() }
        scope.launch { scanLoop(interval) }
    }

    /**
     * 用户主动打开目录：后台立刻把该目录（只本层，不含子目录）里的图片插到队首。
     *
     * 非阻塞、也不抛异常 —— 调用方是接口，不该因为扫描失败而失败。
     */
    fun prioritize(relative: String) {
        if (!config.enabled) return
        scope.launch { enqueueDir(relative) }
    }

    /** 同步等待一次完整扫描（调试 / 冒烟用）。 */
    suspend fun scanNow() {
        if (!config.enabled) return
        scanAll()
    }

    /**
     * 取**原始分辨率**的 WebP 预览：命中缓存直接返回，没有就现生成一次并落盘。
     *
     * 与档位缩略图不同，它不进后台队列 —— 只有用户真的打开这张图，才付这一次「解码原图 +
     * 全尺寸编码」的代价（55MP 的照片在这块板子上要几秒、产物十几 MB），生成一次之后一直命中缓存。
     *
     * **仍受 `files.thumbnail.enabled` 约束**：它和档位缩略图共用同一份缓存目录，而那份目录
     * 同一时间只能有一台机器在写（见配置注释）。开关关着时直接返回 `null`，由前端回退到原图直链。
     *
     * 不是图片、文件过大、解不出图同样返回 `null`。缓存写不进去（磁盘满 / 文件系统不允许）
     * 不影响本次返回：预览是现算现给的，落盘只是省下一次的计算。
     */
    suspend fun fullPreview(relative: String): ByteArray? {
        if (!config.enabled) return null
        val entry = browser.stat(relative) ?: return null
        if (entry.directory || !entry.image || entry.size <= 0L) return null
        if (entry.size > config.maxSourceBytes) {
            log.info { "跳过过大的图片：$relative（${entry.size} 字节 > ${config.maxSourceBytes}）" }
            return null
        }

        val meta = cache.readMeta(relative)
        if (isFullFresh(meta, entry.size, entry.mtime)) {
            cache.readFull(relative)?.let { return it }
        }

        // 解码 + 全尺寸编码是纯 CPU 的重活，挪到默认调度器上跑，别占着请求线程
        return withContext(Dispatchers.Default) {
            val source = browser.readSource(relative) ?: return@withContext null
            val set = ThumbnailGenerator.encodeFull(source, config.quality) ?: run {
                log.info { "不是可解码的图片，跳过：$relative" }
                return@withContext null
            }
            val item = set.items.firstOrNull() ?: return@withContext null
            // 落盘失败只记日志（writeAt 里已 warn），本次照样把算出来的字节发出去
            if (cache.writeFull(relative, item.bytes)) {
                cache.writeMeta(
                    ThumbnailMeta(
                        source = relative,
                        size = entry.size,
                        mtime = entry.mtime,
                        width = meta?.width?.takeIf { it > 0 } ?: set.sourceWidth,
                        height = meta?.height?.takeIf { it > 0 } ?: set.sourceHeight,
                        sizes = meta?.sizes.orEmpty(),
                        sample = meta?.sample,
                        full = true,
                        generatedAt = meta?.generatedAt ?: nowEpochMs(),
                    )
                )
            }
            log.info { "原始分辨率预览已生成：$relative（${item.bytes.size} 字节，${set.sourceWidth}x${set.sourceHeight}）" }
            item.bytes
        }
    }

    /** 原始分辨率预览是否还对得上源文件（判断口径与缩略图一致：大小 + 修改时间）。 */
    private fun isFullFresh(meta: ThumbnailMeta?, size: Long, mtime: Long): Boolean =
        meta != null && meta.full && meta.size == size && meta.mtime == mtime

    private suspend fun enqueueDir(relative: String) {
        val entries = runCatching { browser.list(relative, null, null).entries }
            .onFailure { log.warn(it) { "扫描目录失败：$relative" } }
            .getOrNull() ?: return
        enqueue(entries.filter { it.image && !it.directory && it.size > 0L }.map { it.path }, urgent = true)
    }

    private suspend fun enqueue(paths: List<String>, urgent: Boolean) {
        if (paths.isEmpty()) return
        val added = mutex.withLock {
            var count = 0
            for (path in paths) {
                if (!queued.add(path)) continue
                if (urgent) queue.addFirst(path) else queue.addLast(path)
                count++
            }
            count
        }
        if (added > 0) signal.trySend(Unit)
    }

    private suspend fun workerLoop() {
        while (scope.isActive) {
            val path = mutex.withLock {
                val next = queue.removeFirstOrNull()
                if (next != null) queued.remove(next)
                next
            }
            if (path == null) {
                signal.receive()
                continue
            }
            runCatching { process(path) }.onFailure { log.warn(it) { "生成缩略图失败：$path" } }
        }
    }

    private suspend fun scanLoop(intervalSeconds: Int) {
        // 启动后先让开一段时间：首次访问往往就在这几秒里，别和它抢机械盘
        delay(STARTUP_DELAY_MS)
        while (scope.isActive) {
            runCatching { scanAll() }.onFailure { log.warn(it) { "后台全量扫描失败" } }
            delay(intervalSeconds * 1000L)
        }
    }

    /** 全量扫描：递归找图片，把「没生成过」或「已变更」的排到队尾。 */
    private suspend fun scanAll() {
        log.info { "开始后台全量扫描：${browser.root}" }
        val images = browser.collectImages("")
        val stale = ArrayList<String>()
        for (entry in images) {
            if (entry.size <= 0L || entry.size > config.maxSourceBytes) continue
            val meta = cache.readMeta(entry.path)
            // 缩略图齐全、且采样也在，才算新鲜；老缓存的缩略图是好的、只差采样，也排进来补
            if (cache.isFresh(meta, entry.size, entry.mtime) && !meta?.sample.isNullOrEmpty()) continue
            stale.add(entry.path)
        }
        log.info { "扫描完成：图片 ${images.size} 张，其中待生成 ${stale.size} 张" }
        enqueue(stale, urgent = false)
    }

    /** 处理单个文件：检查新鲜度 → 读原图 → 生成 → 落盘 → 通知前端。 */
    private suspend fun process(relative: String) {
        val entry = browser.stat(relative) ?: return
        if (entry.directory || !entry.image || entry.size <= 0L) return
        if (entry.size > config.maxSourceBytes) {
            log.info { "跳过过大的图片：$relative（${entry.size} 字节 > ${config.maxSourceBytes}）" }
            return
        }

        val meta = cache.readMeta(relative)
        if (cache.isFresh(meta, entry.size, entry.mtime)) {
            // 缩略图本身没问题，只差采样（升级前生成的缓存没有这一项）：解一档小图补上就行，
            // 不为 64 个像素把原图重读重解一遍。
            if (meta?.sample.isNullOrEmpty()) backfillSample(relative, meta)
            return
        }

        // 源文件变了：旧的全分辨率预览也对不上了，直接删掉，等下次打开时按新内容重建
        val unchanged = meta != null && meta.size == entry.size && meta.mtime == entry.mtime
        if (meta != null && !unchanged) cache.deleteFull(relative)

        val source = browser.readSource(relative) ?: return
        val set = ThumbnailGenerator.generate(source, cache.orderedSizes, config.quality)
        if (set == null) {
            log.info { "不是可解码的图片，跳过：$relative" }
            return
        }

        val generated = ArrayList<Int>(set.items.size)
        for (item in set.items) {
            if (cache.write(relative, item.size, item.bytes)) generated.add(item.size)
        }
        if (generated.isEmpty()) return
        cache.writeMeta(
            ThumbnailMeta(
                source = relative,
                size = entry.size,
                mtime = entry.mtime,
                width = set.sourceWidth,
                height = set.sourceHeight,
                sizes = generated.sorted(),
                sample = set.sample,
                // 只是档位不全（源文件没变）的话，之前生成过的全分辨率预览依然有效，别把标记丢了
                full = unchanged && meta.full,
                generatedAt = nowEpochMs(),
            )
        )
        syncSample(relative, set.sample)
        log.info { "缩略图已生成：$relative -> ${generated.sorted()}" }
        notify(listOf(relative))
    }

    /**
     * 只补采样：读一档已经落盘的小图（几十 KB）解出 8×8，写回缓存元数据与库。
     *
     * 只服务于**升级前**生成的缓存 —— 那时还没有采样字段，而新鲜度只看 size/mtime，
     * 不主动补的话这些图永远拿不到采样。
     */
    private suspend fun backfillSample(relative: String, meta: ThumbnailMeta?) {
        if (meta == null) return
        val smallest = meta.sizes.minOrNull() ?: return
        val bytes = cache.read(relative, smallest) ?: return
        val sample = ThumbnailGenerator.sample(bytes) ?: return
        cache.writeMeta(meta.copy(sample = sample))
        syncSample(relative, sample)
        log.info { "采样已回填：$relative" }
    }

    /**
     * 把采样同步进 `media_asset`：本服务手上只有路径，所以按 path 单条 update。
     *
     * 行还不存在（媒体索引尚未登记这个文件）时影响 0 行 —— 索引探测时会自己从缓存
     * 元数据里把采样读出来补上，两个方向都不会漏。库不可用就静静跳过。
     */
    private suspend fun syncSample(relative: String, sample: String?) {
        if (sample.isNullOrEmpty()) return
        val exec = runCatching { db.driver }.getOrNull() ?: return
        runCatching { assets.executeUpdateSampleByPath(exec, relative, sample, Clock.System.now()).getOrThrow() }
            .onFailure { log.warn(it) { "写入采样失败：$relative" } }
    }

    /**
     * 推一帧 `files:thumb`，让前端把命中的项标成已有缩略图（附带新签发的直链签名）。
     *
     * **只推给有 `lolosia.files.read` 的连接**：负载里的 `path` + `sig` 足够取到缩略图与原图，
     * 推给无权用户等于把机械盘的读权限白送（见 [FileSigner]）。
     */
    private suspend fun notify(paths: List<String>) {
        if (paths.isEmpty()) return
        val exp = signer.expireAt()
        val files = paths.map { SignedFile(path = it, exp = exp, sig = signer.sign(it, exp)) }
        val payload = Json.encodeToJsonElement(FileThumbEvent.serializer(), FileThumbEvent(files))
        sse.broadcast(THUMB_EVENT, payload) { mayReadFiles(it) }
    }

    /** 该用户是否拥有文件管理器的读权限（判定口径与 `FileController` 上的 `@Permission` 一致）。 */
    private suspend fun mayReadFiles(userId: String): Boolean =
        Permissions.granted(resolver.rulesOfUser(db.driver, userId), FILE_READ)

    companion object {
        /** 推给前端的 SSE 事件名。 */
        const val THUMB_EVENT: String = "files:thumb"

        /**
         * `files/thumb` 的 `size` 传这个值时，要的是**原始分辨率的 WebP 预览**，而不是缩略图档位。
         *
         * 与 `files.thumbnail.sizes` 里的数字档位是两回事，所以用字面量而不是数字。
         */
        const val FULL_SIZE: String = "full"

        /** 与 `FileController` 各接口上的 `@Permission` 同一个节点。 */
        private const val FILE_READ: String = "lolosia.files.read"

        private const val MIN_SCAN_INTERVAL_SECONDS: Int = 60
        private const val STARTUP_DELAY_MS: Long = 15_000
    }
}
