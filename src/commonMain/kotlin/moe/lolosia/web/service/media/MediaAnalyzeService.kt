package moe.lolosia.web.service.media

import io.github.smyrgeorge.sqlx4k.QueryExecutor
import kotlinx.coroutines.CancellationException
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
import moe.lolosia.web.database.model.ANALYZE_DONE
import moe.lolosia.web.database.model.ANALYZE_FAILED
import moe.lolosia.web.database.model.ANALYZE_NONE
import moe.lolosia.web.database.model.ANALYZE_QUEUED
import moe.lolosia.web.database.model.ANALYZE_RUNNING
import moe.lolosia.web.database.model.ANALYZE_SKIPPED
import moe.lolosia.web.database.model.MediaAssetEntity
import moe.lolosia.web.database.repository.MediaAssetRepository
import moe.lolosia.web.database.repository.MediaAssetTagRepository
import moe.lolosia.web.dto.MediaAnalyzeEvent
import moe.lolosia.web.dto.MediaAnalyzeReq
import moe.lolosia.web.dto.MediaAnalyzeStatus
import moe.lolosia.web.dto.MediaAnalyzeUsage
import moe.lolosia.web.dto.MessageResult
import moe.lolosia.web.service.SseService
import moe.lolosia.web.service.files.FileBrowser
import moe.lolosia.web.service.files.ThumbnailGenerator
import moe.lolosia.web.service.media.worker.DeepSeekVisionProvider
import moe.lolosia.web.service.media.worker.META_INVALID
import moe.lolosia.web.service.media.worker.META_VALID
import moe.lolosia.web.service.media.worker.NAMESPACE_ATTRIBUTE
import moe.lolosia.web.service.media.worker.NAMESPACE_SCENE
import moe.lolosia.web.service.media.worker.NAMESPACE_SERIES
import moe.lolosia.web.service.media.worker.NoopTaggingProvider
import moe.lolosia.web.service.media.worker.TaggingProvider
import moe.lolosia.web.service.media.worker.sceneTagsOf
import moe.lolosia.web.service.media.worker.seriesTagOf
import moe.lolosia.web.service.permission.PermissionResolver
import moe.lolosia.web.service.permission.Permissions
import moe.lolosia.web.settings.AppSettings
import moe.lolosia.web.util.llm.ChatClient
import moe.lolosia.web.util.llm.ChatUsage
import moe.lolosia.web.util.log.Log
import moe.lolosia.web.util.router.Injectable
import moe.lolosia.web.util.router.Service
import kotlin.concurrent.Volatile
import kotlin.time.Clock

/**
 * 外发分析作业（视觉自动打标，`doc/vision-autotag-design.md` §7.1）。
 *
 * 它是 [MediaIndexService] 的**下游**：本地索引已把文件登记成行，这里才把（降采样后的）素材
 * 交给外部分析。三条硬约束：
 * - **两道合规闸门**：入队与发包**各查一次** `rating != explicit && !analysis_skip`，任一不过就不出网；
 * - **批量 + 本地降采样**：一批多图一请求摊薄系统提示，图片优先用已缓存的低清缩略图（省上行、零解码）；
 * - **可核的记账**：每次响应 `usage` 累加进 [TokenLedger]，并在日配额 / 单次作业上限处**暂停**。
 *
 * 队列与工作协程照抄 [MediaIndexService] / [moe.lolosia.web.service.files.ThumbnailService]：
 * 一条（或多条，`vision.concurrency`）工作协程串行取批，进程退出即停。
 *
 * @author 洛洛希雅Lolosia
 */
@Injectable
@Service
class MediaAnalyzeService(
    settings: AppSettings,
    private val browser: FileBrowser,
    private val db: Database,
    private val sse: SseService,
    private val resolver: PermissionResolver,
    private val assets: MediaAssetRepository,
    private val bindings: MediaAssetTagRepository,
    private val tagService: MediaTagService,
    private val ledger: TokenLedger,
) {

    private val log = Log.of("Vision")
    private val config = settings.files.media.vision
    private val thumbnail = settings.files.thumbnail

    /** 打标后端：未启用或配成 `noop` 时是空实现。 */
    private val provider: TaggingProvider = run {
        val useDeepSeek = config.enabled && config.provider.equals("deepseek", ignoreCase = true)
        if (!useDeepSeek) {
            NoopTaggingProvider
        } else {
            val llm = settings.llm.copy(
                model = config.model.ifBlank { settings.llm.model },
                temperature = 0.0,
            )
            DeepSeekVisionProvider(ChatClient(llm), config)
        }
    }

    /** 待分析队列（asset id）。 */
    private val mutex = Mutex()
    private val queue = ArrayDeque<Long>()
    private val queued = mutableSetOf<Long>()

    /** 只用来唤醒工作协程（conflated：多次入队合并成一次唤醒）。 */
    private val signal = Channel<Unit>(Channel.CONFLATED)

    /** 自持作用域：队列与循环都活在请求之外，进程退出即停。 */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // 运行期计数（单调递增，允许轻微读旧）
    @Volatile private var processedCount = 0L
    @Volatile private var failedCount = 0L
    @Volatile private var skippedCount = 0L
    @Volatile private var runPrompt = 0L
    @Volatile private var runCompletion = 0L
    @Volatile private var pausedReason = ""

    /** 是否已启用（未启用时所有入口都是空操作）。 */
    val enabled: Boolean get() = config.enabled

    /** 启动工作协程，并把库里残留的 `queued` 行拉回内存续跑；未启用时只记一条日志。 */
    fun start() {
        if (!config.enabled) {
            log.info { "外发分析未启用（files.media.vision.enabled=false）" }
            return
        }
        val a = config.analyze
        log.info {
            "外发分析已启用：provider=${provider.id} 批量=${batchSize()} detail=${config.detail} " +
                "并发=${config.concurrency.coerceAtLeast(1)} 维度=" +
                buildList {
                    if (a.composition) add("构图")
                    if (a.validity) add("有效性")
                    if (a.subject) add("题材")
                    if (a.caption) add("描述")
                    if (a.attributes) add("属性")
                }.joinToString("/")
        }
        scope.launch { resume() }
        repeat(config.concurrency.coerceAtLeast(1)) { scope.launch { workerLoop() } }
    }

    /**
     * 对某个范围入队分析。入队只落 `queued` 状态并入内存队列，真正外发在后台工作协程里。
     *
     * 闸门在**这里**先查一次：`rating = explicit` 或 `analysis_skip` 的直接标 `skipped`，不进队。
     */
    suspend fun start(req: MediaAnalyzeReq, operator: String?): MessageResult {
        if (!config.enabled) return MessageResult("外发分析未启用（files.media.vision.enabled=false）")
        val exec = driverOrNull() ?: return MessageResult("数据库暂不可用，稍后再试")
        val (relative, _) = browser.requirePath(req.path)
        val dirs = if (req.recursive) browser.collectDirs(relative) else listOf(relative)
        val now = Clock.System.now()
        var queuedCount = 0
        var skipped = 0
        for (dir in dirs) {
            val rows = assets.findAllByDirPath(exec, dir).getOrElse { continue }
            for (row in rows) {
                if (row.kind != KIND_IMAGE) continue
                if (RatingPolicy.outboundBlocked(row.rating, row.analysisSkip)) {
                    assets.executeUpdateAnalyzeById(exec, row.id, ANALYZE_SKIPPED, null, null, now).getOrThrow()
                    skipped++
                    continue
                }
                if (!req.reanalyze && row.analyzeState == ANALYZE_DONE) continue
                assets.executeUpdateAnalyzeById(exec, row.id, ANALYZE_QUEUED, null, null, now).getOrThrow()
                enqueue(row.id)
                queuedCount++
            }
        }
        val hint = if (skipped > 0) "，闸门跳过 $skipped 个" else ""
        return MessageResult("已排队 $queuedCount 个文件$hint，分析结果将通过 $MEDIA_ANALYZE_EVENT 推送")
    }

    /** 取消排队：清空内存队列，并把那些行的状态复位为 `none`（正在处理的批会自然跑完）。 */
    suspend fun cancel(): MessageResult {
        val ids = mutex.withLock {
            val list = queue.toList()
            queue.clear()
            queued.clear()
            list
        }
        val exec = driverOrNull()
        if (exec != null) {
            val now = Clock.System.now()
            for (id in ids) runCatching { assets.executeUpdateAnalyzeById(exec, id, ANALYZE_NONE, null, null, now) }
        }
        return MessageResult("已取消 ${ids.size} 个待分析项")
    }

    /** 运行状态快照。 */
    suspend fun status(): MediaAnalyzeStatus {
        val size = mutex.withLock { queue.size }
        return MediaAnalyzeStatus(
            enabled = config.enabled,
            provider = provider.id,
            running = size > 0,
            queued = size,
            processed = processedCount,
            failed = failedCount,
            skipped = skippedCount,
            promptTokens = runPrompt,
            completionTokens = runCompletion,
            offPeak = !LlmPricing.isPeak(),
            message = pausedReason,
        )
    }

    /** 台账快照（全局累计 + 按 provider 汇总 + 今日估算花费）。 */
    suspend fun usage(): MediaAnalyzeUsage = ledger.snapshot()

    // ---- 后台 ----

    private suspend fun resume() {
        val exec = driverOrNull() ?: return
        val rows = runCatching { assets.findAllByAnalyzeState(exec, ANALYZE_QUEUED, RESUME_LIMIT).getOrThrow() }
            .getOrElse { return }
        val now = Clock.System.now()
        var restored = 0
        for (row in rows) {
            if (RatingPolicy.outboundBlocked(row.rating, row.analysisSkip)) {
                assets.executeUpdateAnalyzeById(exec, row.id, ANALYZE_SKIPPED, null, null, now)
                continue
            }
            enqueue(row.id)
            restored++
        }
        if (restored > 0) log.info { "外发分析续跑：恢复 $restored 条 queued" }
    }

    private suspend fun workerLoop() {
        while (scope.isActive) {
            val empty = mutex.withLock { queue.isEmpty() }
            if (empty) {
                signal.receive()
                continue
            }
            val pause = pauseReason()
            if (pause != null) {
                pausedReason = pause
                delay(PAUSE_MS)
                continue
            }
            pausedReason = ""
            val batch = mutex.withLock {
                val out = ArrayList<Long>(batchSize())
                while (out.size < batchSize() && queue.isNotEmpty()) {
                    val id = queue.removeFirst()
                    queued.remove(id)
                    out.add(id)
                }
                out
            }
            if (batch.isEmpty()) continue
            runCatching { process(batch) }.onFailure { log.warn(it) { "外发分析批处理失败" } }
        }
    }

    /** 取当前暂停原因（超配额 / 超单次作业上限），没有则 `null`。 */
    private suspend fun pauseReason(): String? {
        if (ledger.overDailyBudget()) return "已达日输入 token 配额，暂停（可在配置里放宽）"
        if (config.budget.maxPromptTokensPerRun > 0 && runPrompt >= config.budget.maxPromptTokensPerRun) {
            return "已达单次作业输入 token 上限，暂停"
        }
        return null
    }

    /**
     * 处理一批：闸门复查 → 取图（本地降采样）→ 近重复去重 → 一次外发 → 逐图落标签 / 描述 / 状态。
     *
     * 去重只在本批内做：近似图只发代表图，其余（[INHERIT]）继承代表图的结果。
     */
    private suspend fun process(ids: List<Long>) {
        val exec = driverOrNull() ?: return
        val items = ArrayList<TaggingProvider.Item>()
        val reps = ArrayList<MediaAssetEntity>()
        val hashes = ArrayList<Long?>()
        val dups = HashMap<Int, MutableList<MediaAssetEntity>>()
        val now = Clock.System.now()

        for (id in ids) {
            val row = assets.findOneById(exec, id).getOrElse { null } ?: continue
            // 发包前的第二道闸门：入队后用户可能刚把它设成 explicit / 禁止外发
            if (RatingPolicy.outboundBlocked(row.rating, row.analysisSkip)) {
                assets.executeUpdateAnalyzeById(exec, row.id, ANALYZE_SKIPPED, null, null, now).getOrThrow()
                skippedCount++
                continue
            }
            val hash = NearDup.hash(row.sampleRgb565 ?: browser.cache.readMeta(row.path)?.sample)
            val repIndex = hash?.let { h ->
                hashes.indexOfFirst { other -> other != null && NearDup.distance(other, h) <= config.prefilter.dedupHamming }
            } ?: -1
            if (repIndex >= 0) {
                dups.getOrPut(repIndex) { ArrayList() }.add(row)
                continue
            }
            val loaded = loadForUpload(row.path)
            if (loaded == null) {
                assets.executeUpdateAnalyzeById(exec, row.id, ANALYZE_FAILED, now, provider.id, now).getOrThrow()
                failedCount++
                continue
            }
            assets.executeUpdateAnalyzeById(exec, row.id, ANALYZE_RUNNING, null, null, now).getOrThrow()
            items.add(TaggingProvider.Item(row.path, loaded.first, loaded.second))
            reps.add(row)
            hashes.add(hash)
        }
        if (items.isEmpty()) return

        val output = try {
            provider.tag(items)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            log.warn(e) { "外发分析请求失败（${items.size} 张）" }
            val failAt = Clock.System.now()
            for (rep in reps) runCatching {
                assets.executeUpdateAnalyzeById(exec, rep.id, ANALYZE_FAILED, failAt, provider.id, failAt)
            }
            for (list in dups.values) for (dup in list) runCatching {
                assets.executeUpdateAnalyzeById(exec, dup.id, ANALYZE_FAILED, failAt, provider.id, failAt)
            }
            failedCount += (reps.size + dups.values.sumOf { it.size })
            return
        }

        ledger.record(provider.id, output.usage)
        output.usage?.let { addRun(it) }

        for ((index, rep) in reps.withIndex()) {
            val result = output.results.getOrNull(index) ?: TaggingProvider.Result()
            apply(exec, rep, result, provider.id)
            processedCount++
            notify(buildEvent(rep.path, ANALYZE_DONE, result))
            for (dup in dups[index].orEmpty()) {
                apply(exec, dup, result, "dedup-from:${rep.path}")
                processedCount++
                notify(buildEvent(dup.path, ANALYZE_DONE, result))
            }
        }
    }

    /**
     * 读一张图的**上传档**字节：优先用已缓存的低清缩略图（省上行、零解码）；
     * 缓存缺失才现解一次原图、降采样后编码。
     */
    private suspend fun loadForUpload(relative: String): Pair<ByteArray, String>? {
        val edge = config.prefilter.maxUploadEdge.coerceAtLeast(64)
        val size = browser.cache.orderedSizes.lastOrNull { it <= edge } ?: browser.cache.orderedSizes.minOrNull()
        if (size != null) browser.cache.read(relative, size)?.let { return it to "image/webp" }

        val entry = browser.stat(relative) ?: return null
        if (entry.directory || !entry.image || entry.size <= 0L) return null
        if (entry.size > thumbnail.maxSourceBytes) return null
        val source = browser.readSource(relative) ?: return null
        return withContext(Dispatchers.Default) {
            val set = ThumbnailGenerator.generate(source, listOf(edge), thumbnail.quality, null)
            val item = set?.items?.firstOrNull() ?: return@withContext null
            item.bytes to "image/webp"
        }
    }

    /** 落一张图的结果：先清 `model` 来源旧标签（重跑语义），再写标签 / 描述 / 状态。 */
    private suspend fun apply(
        exec: QueryExecutor,
        row: MediaAssetEntity,
        result: TaggingProvider.Result,
        providerId: String,
    ) {
        val now = Clock.System.now()
        runCatching { bindings.executeSoftDeleteByAssetIdAndSource(exec, row.id, SOURCE_MODEL, now).getOrThrow() }
            .onFailure { log.warn(it) { "清除旧模型标签失败：${row.path}" } }
        for ((namespace, name) in tagRefs(result)) {
            runCatching {
                tagService.attach(exec, row.id, namespace, name, SOURCE_MODEL, providerId, providerId)
            }.onFailure { log.warn(it) { "写入模型标签失败：${row.path} $namespace:$name" } }
        }
        val caption = result.caption?.trim()?.ifEmpty { null }
        if (caption != null && row.captionSource != SOURCE_MANUAL) {
            runCatching {
                assets.executeUpdateCaptionById(exec, row.id, caption, SOURCE_MODEL, now, providerId).getOrThrow()
            }.onFailure { log.warn(it) { "写入描述失败：${row.path}" } }
        }
        runCatching {
            assets.executeUpdateAnalyzeById(exec, row.id, ANALYZE_DONE, now, providerId, now).getOrThrow()
        }.onFailure { log.warn(it) { "写入分析状态失败：${row.path}" } }
    }

    /** 把模型的判定映射成「命名空间:名字」标签（只映射配置里开启的维度）。 */
    private fun tagRefs(result: TaggingProvider.Result): List<Pair<String, String>> {
        val a = config.analyze
        val out = ArrayList<Pair<String, String>>()
        if (a.composition) sceneTagsOf(result.count).forEach { out.add(NAMESPACE_SCENE to it) }
        if (a.validity) result.valid?.let { out.add(NAMESPACE_META to if (it) META_VALID else META_INVALID) }
        if (a.subject) seriesTagOf(result.subject)?.let { out.add(NAMESPACE_SERIES to it) }
        if (a.attributes) result.attributes.forEach { out.add(NAMESPACE_ATTRIBUTE to it) }
        return out.distinct()
    }

    private fun buildEvent(path: String, state: String, result: TaggingProvider.Result): MediaAnalyzeEvent =
        MediaAnalyzeEvent(
            path = path,
            state = state,
            tags = tagRefs(result).map { "${it.first}:${it.second}" },
            caption = result.caption.orEmpty(),
        )

    private suspend fun enqueue(id: Long) {
        val added = mutex.withLock { if (queued.add(id)) { queue.addLast(id); true } else false }
        if (added) signal.trySend(Unit)
    }

    private fun addRun(usage: ChatUsage) {
        runPrompt += usage.promptTokens
        runCompletion += usage.completionTokens
    }

    private fun batchSize(): Int {
        val configured = config.batchSize.coerceAtLeast(1)
        val max = provider.maxBatch
        return if (max > 0) minOf(configured, max) else configured
    }

    /** 推一帧 `media:analyze`；只推给有 `lolosia.media.read` 的连接。 */
    private suspend fun notify(event: MediaAnalyzeEvent) {
        val payload = Json.encodeToJsonElement(MediaAnalyzeEvent.serializer(), event)
        sse.broadcast(MEDIA_ANALYZE_EVENT, payload) { mayReadMedia(it) }
    }

    private suspend fun mayReadMedia(userId: String): Boolean =
        Permissions.granted(resolver.rulesOfUser(db.driver, userId), MEDIA_READ)

    /** 后台线程专用的执行句柄：没连上就回 `null`，不让后台循环因为 503 而中断。 */
    private fun driverOrNull(): QueryExecutor? = runCatching { db.driver }.getOrNull()

    private companion object {
        /** 单次启动最多恢复多少条库里的 `queued` 行。 */
        const val RESUME_LIMIT: Int = 50_000

        /** 暂停状态下的重检间隔。 */
        const val PAUSE_MS: Long = 30_000

        /** 只分析图片（视频 / 音频暂不支持视觉分析）。 */
        const val KIND_IMAGE: String = "image"
    }
}
