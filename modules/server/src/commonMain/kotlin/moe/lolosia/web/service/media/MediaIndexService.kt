package moe.lolosia.web.service.media

import io.github.smyrgeorge.sqlx4k.QueryExecutor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import moe.lolosia.web.database.Database
import moe.lolosia.web.database.model.RATING_UNKNOWN
import moe.lolosia.web.database.repository.MediaAssetRepository
import moe.lolosia.web.dto.FileEntry
import moe.lolosia.web.dto.MediaIndexEvent
import moe.lolosia.web.service.SseService
import moe.lolosia.web.service.files.FileBrowser
import moe.lolosia.web.service.files.parentOf
import moe.lolosia.web.service.files.resolve
import moe.lolosia.web.service.permission.PermissionResolver
import moe.lolosia.web.service.permission.Permissions
import moe.lolosia.web.settings.AppSettings
import moe.lolosia.web.util.log.Log
import moe.lolosia.web.util.media.ContentHash
import moe.lolosia.web.util.media.isMediaName
import moe.lolosia.web.util.media.kindOf
import moe.lolosia.web.util.router.Injectable
import moe.lolosia.web.util.router.Service
import moe.lolosia.web.util.time.nowEpochMs
import okio.FileSystem
import kotlin.time.Clock

/**
 * 媒体索引的**本地**扫描服务：把浏览根下的图片 / 视频 / 音频登记成「一个文件一行」，
 * 抽出可筛选的元数据，并按目录 / 文件名规则打标签与评级。
 *
 * 三条硬边界（`doc/media-index-design.md` §5 / §8.2）：
 * - **不出网**：只做 stat / `quick_id` / EXIF probe / 规则打标，标签与描述要等用户显式点
 *   「全盘分析」才外发（那是 P3 的 [MediaAnalyzeService] 的事，这里不认识 worker）；
 * - **零读取优先**：size + mtime 都没变就直接返回，一个字节都不读（[process] 的第一件事）；
 * - **库不可用不崩**：每次取执行句柄都走 [driverOrNull]，拿不到就等下一条。
 *
 * 队列与工作协程照抄 [moe.lolosia.web.service.files.ThumbnailService]：一条工作协程串行处理
 * （机械盘只有 30MB/s，并发只会让磁头来回跳），用户打开目录时 [requestScan] 把该目录插到队首。
 *
 * @author 洛洛希雅Lolosia
 */
@Injectable
@Service
class MediaIndexService(
    settings: AppSettings,
    private val browser: FileBrowser,
    private val db: Database,
    private val sse: SseService,
    private val resolver: PermissionResolver,
    private val assets: MediaAssetRepository,
    private val probe: MediaProbe,
    private val tagService: MediaTagService,
) {

    private val log = Log.of("Media")
    private val config = settings.files.media
    private val fs: FileSystem = FileSystem.SYSTEM

    /** 待索引队列（队首优先）。 */
    private val mutex = Mutex()
    private val queue = ArrayDeque<String>()
    private val queued = mutableSetOf<String>()

    /** 只用来唤醒工作协程（conflated：多次入队合并成一次唤醒）。 */
    private val signal = Channel<Unit>(Channel.CONFLATED)

    /** 自持作用域：队列与循环都活在请求之外，进程退出即停。 */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** 是否已启用（未启用时所有入口都是空操作）。 */
    val enabled: Boolean get() = config.enabled

    /** 启动后台工作协程与周期性全量扫描；未启用时只记一条日志。 */
    fun start() {
        if (!config.enabled) {
            log.info { "媒体索引未启用（files.media.enabled=false）" }
            return
        }
        val interval = intervalSeconds()
        log.info {
            "媒体索引已启用：根=${browser.root} 扫描间隔=${interval}s 探测器版本=${probe.version} " +
                "规则=${if (config.rules.enabled) "开" else "关"}"
        }
        scope.launch { workerLoop() }
        scope.launch { scanLoop(interval) }
    }

    /**
     * 请求扫描：`relative` 为空表示整个浏览根（本地全盘），否则只插队处理该目录的本层文件。
     *
     * 非阻塞、不抛异常 —— 调用方是接口，不该因为扫描失败而失败。
     */
    fun requestScan(relative: String?) {
        if (!config.enabled) return
        scope.launch {
            if (relative.isNullOrEmpty()) scanAll() else enqueueDir(relative)
        }
    }

    /** 同步跑一轮完整扫描（调试 / 冒烟用）。 */
    suspend fun scanNow() {
        if (!config.enabled) return
        scanAll()
    }

    /** 同步索引单个文件（调试 / 冒烟用）。 */
    suspend fun indexNow(relative: String) {
        if (!config.enabled) return
        val exec = driverOrNull() ?: return
        runCatching { process(exec, relative, tagService.loadRules(exec)) }
            .onFailure { log.warn(it) { "索引失败：$relative" } }
    }

    private fun intervalSeconds(): Int = config.indexIntervalSeconds.coerceAtLeast(MIN_INTERVAL_SECONDS)

    private suspend fun enqueueDir(relative: String) {
        val entries = runCatching { browser.list(relative, null, null).entries }
            .onFailure { log.warn(it) { "扫描目录失败：$relative" } }
            .getOrNull() ?: return
        enqueue(entries.filter { !it.directory && it.size > 0L && isMediaName(it.name) }.map { it.path }, urgent = true)
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
        // 规则集整轮扫描只加载一次；队列排空（说明这一轮结束）后丢掉，下一轮重新取
        var ruleSet: MediaRuleSet? = null
        while (scope.isActive) {
            val path = mutex.withLock {
                val next = queue.removeFirstOrNull()
                if (next != null) queued.remove(next)
                next
            }
            if (path == null) {
                ruleSet = null
                signal.receive()
                continue
            }
            val exec = driverOrNull()
            if (exec == null) {
                log.warn { "数据库不可用，媒体索引稍后重试：$path" }
                delay(RETRY_DELAY_MS)
                continue
            }
            val rules = ruleSet ?: tagService.loadRules(exec).also { ruleSet = it }
            runCatching { process(exec, path, rules) }
                .onFailure { log.warn(it) { "索引媒体失败：$path" } }
        }
    }

    private suspend fun scanLoop(intervalSeconds: Int) {
        // 启动后先让开一段时间：首次访问往往就在这几秒里，别和它抢机械盘
        delay(STARTUP_DELAY_MS)
        while (scope.isActive) {
            runCatching { scanAll() }.onFailure { log.warn(it) { "媒体索引全量扫描失败" } }
            delay(intervalSeconds * 1000L)
        }
    }

    /**
     * 全量扫描：递归找媒体文件入队，再逐目录比对「已登记但本轮没见到」的行（§5.3）。
     *
     * 比对用的是**文件系统列表**而不是「队列处理结果」，所以和工作协程并行跑也不会互相干扰。
     */
    private suspend fun scanAll() {
        val exec = driverOrNull() ?: return
        log.info { "开始媒体索引扫描：${browser.root}" }
        val files = browser.collectMedia("")
        val dirs = browser.collectDirs("")
        enqueue(files.map { it.path }, urgent = false)
        markMissing(exec, dirs, files)
        log.info { "媒体索引扫描完成：媒体 ${files.size} 个，目录 ${dirs.size} 个" }
    }

    /**
     * 缺失与删除（§5.3）：**不要一发现不见就删行** —— 媒体盘可能只是临时不可达。
     *
     * 连续 N 次扫描（`missingScanLimit`）仍未见才软删；时间折算成
     * `missingScanLimit × indexIntervalSeconds`，所以 schema 不需要计数器列。
     */
    private suspend fun markMissing(exec: QueryExecutor, dirs: List<String>, files: List<FileEntry>) {
        val byDir = files.groupBy { parentOf(it.path) }
        val expireMs = config.missingScanLimit.toLong() * intervalSeconds() * 1000L
        val nowMs = nowEpochMs()
        val now = Clock.System.now()
        for (dir in dirs) {
            val rows = assets.findAllByDirPath(exec, dir).getOrElse { continue }
            if (rows.isEmpty()) continue
            val seen = byDir[dir].orEmpty().mapTo(HashSet()) { it.path }
            for (row in rows) {
                if (row.mergedInto != null) continue
                if (row.path in seen) {
                    if (row.missingSince != null) {
                        assets.executeRestoreById(exec, row.id, now).getOrThrow()
                        log.info { "媒体已恢复：${row.path}" }
                    }
                    continue
                }
                val missingSince = row.missingSince
                when {
                    missingSince == null ->
                        assets.executeUpdateMissingSinceById(exec, row.id, now, now).getOrThrow()

                    nowMs - missingSince.toEpochMilliseconds() >= expireMs -> {
                        assets.executeSoftDeleteById(exec, row.id, now, null).getOrThrow()
                        log.info { "媒体连续 ${config.missingScanLimit} 轮未见到，已软删：${row.path}" }
                    }
                }
            }
        }
    }

    /**
     * 索引一个文件（§5.2 的合并算法）。
     *
     * 顺序：新鲜度短路 → `quick_id` 定身份（改名 / 移动零成本保住标签）→ 探测元数据 →
     * 应用规则（标签 + 评级）→ 通知前端。
     */
    private suspend fun process(exec: QueryExecutor, relative: String, ruleSet: MediaRuleSet) {
        val entry = browser.stat(relative) ?: return
        if (entry.directory || !isMediaName(entry.name) || entry.size <= 0L) return
        val name = entry.name
        val kind = kindOf(name)
        val now = Clock.System.now()

        // 软删过的行被重新发现（文件回来了）：复活它，标签与评级原样（§5.3）
        val found = assets.findOneByPathIncludingDeleted(exec, relative).getOrElse { return }
        var row = found
        if (found != null && found.deleted) {
            assets.executeRestoreById(exec, found.id, now).getOrThrow()
            row = found.copy(deleted = false, missingSince = null)
            log.info { "媒体已重新发现：$relative" }
        }

        // (1) 零读取路径：size + mtime 没变、探测器没升版 → 什么都不做
        if (row != null && row.size == entry.size && row.mtime == entry.mtime && row.probeVersion == probe.version) return

        // (2) 算 quick_id 并据此判断「改了 mtime 之后内容到底变没变」
        val quickId = ContentHash.quick(fs, resolve(browser.rootPath, relative), entry.size)
        val hit = quickId?.let { assets.findOneByQuickId(exec, it).getOrNull() }
        val dirPath = parentOf(relative) ?: ""
        when {
            hit != null && hit.path != relative -> {
                // 改名 / 移动：标签、评级、向量、EXIF 全部原样保留
                assets.executeUpdateLocationById(
                    exec, hit.id, relative, dirPath, name, entry.size, entry.mtime, quickId, now, null,
                ).getOrThrow()
                if (row != null && row.id != hit.id) {
                    assets.executeMergeIntoById(exec, row.id, hit.id, now).getOrThrow()
                }
                log.info { "媒体已改名 / 移动：${hit.path} -> $relative" }
                notify(
                    MediaIndexEvent(
                        relative,
                        hit.rating,
                        true,
                        hit.takenAt?.toEpochMilliseconds() ?: 0L,
                        hit.sampleRgb565.orEmpty(),
                    )
                )
                return
            }

            row == null -> {
                assets.executeInsert(
                    exec, relative, dirPath, name, kind.id, entry.size, entry.mtime, quickId,
                    RATING_UNKNOWN, false, now, null, now, null,
                ).getOrThrow()
                row = assets.findOneByPath(exec, relative).getOrElse { null }
                log.info { "发现新媒体：$relative（$kind）" }
            }

            else -> {
                // 同路径、内容变了：更新位置与新鲜度，派生数据随后整批重做
                assets.executeUpdateLocationById(
                    exec, row.id, relative, dirPath, name, entry.size, entry.mtime, quickId, now, null,
                ).getOrThrow()
                log.info { "媒体内容已变化，重新探测：$relative" }
            }
        }
        if (row == null) {
            log.warn { "媒体行写入后读不回来：$relative" }
            return
        }

        // (3) 探测元数据（只读文件头，几百 KB）
        val head = browser.readSource(relative, config.probeMaxBytes)
        val result = if (head == null) MediaProbeResult() else probe.probe(kind, head)
        assets.executeUpdateProbeById(
            context = exec,
            id = row.id,
            kind = kind.id,
            width = result.width,
            height = result.height,
            durationMs = null,
            takenAt = result.fields?.takenAt,
            takenAtLocal = result.fields?.takenAtLocal,
            takenAtOffsetMin = result.fields?.takenAtOffsetMin,
            cameraMake = result.fields?.cameraMake,
            cameraModel = result.fields?.cameraModel,
            lens = result.fields?.lens,
            aperture = result.fields?.aperture,
            exposureTime = result.fields?.exposureTime,
            iso = result.fields?.iso,
            focalLength = result.fields?.focalLength,
            gpsLat = result.fields?.gps?.latitude,
            gpsLon = result.fields?.gps?.longitude,
            orientation = result.fields?.orientation,
            metadataJson = MediaMetadata.json(result.fields?.entries.orEmpty()),
            probeAt = now,
            probeVersion = probe.version,
            updatedAt = now,
            updatedBy = null,
        ).getOrThrow()

        // 8×8 采样由缩略图服务在生成缩略图时顺带产出、写在缓存元数据里，这里抄进库。
        // 缩略图比索引先跑完时，缩略图服务回写会因为「行还不存在」影响 0 行 —— 那条路就是这里补的。
        // 反过来（索引先跑）也不用担心：缩略图生成完会自己按 path 回写。
        val sample = browser.cache.readMeta(relative)?.sample?.takeIf { it.isNotEmpty() }
        if (sample != null) assets.executeUpdateSampleByPath(exec, relative, sample, now).getOrThrow()

        // (4) 规则打标 + 规则评级（只写 source=rule 的行，人工行一个都不动）
        val ruleRating = tagService.applyRules(exec, ruleSet, row.id, relative, name, null)
        val manual = row.ratingSource == SOURCE_MANUAL
        val rating = if (manual) row.rating else (ruleRating ?: row.rating)
        val ratingSource = when {
            manual -> SOURCE_MANUAL
            ruleRating != null -> SOURCE_RULE
            else -> row.ratingSource
        }
        val analysisSkip = RatingPolicy.outboundBlocked(rating, row.analysisSkip)
        if (rating != row.rating || ratingSource != row.ratingSource || analysisSkip != row.analysisSkip) {
            assets.executeUpdateRatingById(exec, row.id, rating, ratingSource, analysisSkip, now, null).getOrThrow()
        }
        if (ratingSource != null) tagService.projectRating(exec, row.id, rating, ratingSource, null)

        notify(MediaIndexEvent(relative, rating, true, result.fields?.takenAt?.toEpochMilliseconds() ?: 0L, sample.orEmpty()))
    }

    /**
     * 推一帧 `media:index`，让前端就地更新遮罩与元数据。
     *
     * **只推给有 `lolosia.media.read` 的连接** —— 负载里的 `path` + `rating` 本身就是要保护的东西。
     */
    private suspend fun notify(event: MediaIndexEvent) {
        val payload = Json.encodeToJsonElement(MediaIndexEvent.serializer(), event)
        sse.broadcast(MEDIA_INDEX_EVENT, payload) { mayReadMedia(it) }
    }

    /** 判定口径与 `MediaController` 上的 `@Permission` 一致。 */
    private suspend fun mayReadMedia(userId: String): Boolean =
        Permissions.granted(resolver.rulesOfUser(db.driver, userId), MEDIA_READ)

    /** 后台线程专用的执行句柄：没连上就回 `null`，不让后台循环因为 503 而中断。 */
    private fun driverOrNull(): QueryExecutor? = runCatching { db.driver }.getOrNull()

    companion object {
        private const val MIN_INTERVAL_SECONDS: Int = 300
        private const val STARTUP_DELAY_MS: Long = 20_000
        private const val RETRY_DELAY_MS: Long = 30_000
    }
}
