package moe.lolosia.web.service.dashboard

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import moe.lolosia.web.database.Database
import moe.lolosia.web.database.repository.SysTokenUsageRepository
import moe.lolosia.web.service.media.LlmPricing
import moe.lolosia.web.service.media.TokenLedger
import moe.lolosia.web.settings.AppSettings
import moe.lolosia.web.util.log.Log
import moe.lolosia.web.util.router.Injectable
import moe.lolosia.web.util.router.Service
import moe.lolosia.web.util.session.Context
import kotlin.time.Clock

/**
 * Token 用量统计服务：把 [TokenLedger] 里的累计用量按 `dashboard.sampleIntervalSeconds` 采样，
 * 得到「今日累计」曲线（内存环形缓冲，保留约 1 小时），并把增量按**北京时间小时桶**累加落库
 * `sys_token_usage`；对外给仪表盘两件事 —— 今日 token 数与累计成本（逐桶按峰谷计价）。
 *
 * 与 [SystemMetricService] 的差别：token 是**事件驱动**而不是周期采样，且曲线是全天的**累计值**
 * （读数是今日累计，不是瞬时速率）。基线在启动时从库读回（进程重启今日计数不归零）。
 *
 * 库不可用时静默跳过落库/聚合（纯派生数据，同 `AccessStatService` 的立场），不影响曲线。
 *
 * @author 洛洛希雅Lolosia
 */
@Injectable
@Service
class TokenUsageService(
    private val db: Database,
    private val usages: SysTokenUsageRepository,
    private val ledger: TokenLedger,
    settings: AppSettings,
) {

    /** 累计汇总：token 数与成本（元）。 */
    data class Totals(val tokens: Long, val cost: Double)

    private val log = Log.of("Token")
    private val config = settings.dashboard
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val intervalSeconds = config.sampleIntervalSeconds.coerceAtLeast(1)
    private val capacity = (3600 / intervalSeconds).coerceAtLeast(60)

    /** 采样协程写、请求协程读，共享可变状态一律串行访问。 */
    private val mutex = Mutex()

    /** 今日累计 token 曲线（环形缓冲）。 */
    private val series = DoubleArray(capacity)
    private var head = 0
    private var size = 0

    /** 上一次采到的台账累计值，用来求增量。 */
    private var lastRequests = 0L
    private var lastPrompt = 0L
    private var lastCompletion = 0L
    private var lastHit = 0L

    /** 北京时间当天的键与今日累计；启动时从库读回基线，跨日归零。 */
    private var dayKey: String = LlmPricing.dayKey()
    private var todayTotal = 0L

    /** 待落库的小时桶增量（仅采样协程访问）。 */
    private val pending = HashMap<String, Delta>()

    /** 启动后台采样；未启用时只记一条日志。 */
    fun start() {
        if (!config.enabled) {
            log.info { "token 用量统计未启用（dashboard.enabled=false）" }
            return
        }
        log.info { "token 用量统计已启用：间隔=${intervalSeconds}s" }
        scope.launch { sampleLoop() }
    }

    /** 今日（北京时间）累计 token。 */
    suspend fun todayTokens(ctx: Context): Long = mutex.withLock {
        rollDay()
        todayTotal
    }

    /** 累计 token 与累计成本（元）；成本按每个小时桶的峰谷分开计。 */
    suspend fun totals(ctx: Context): Totals {
        val rows = runCatching { usages.findAll(ctx.db).getOrThrow() }.getOrNull().orEmpty()
        var tokens = 0L
        var cost = 0.0
        for (row in rows) {
            tokens += row.promptTokens + row.completionTokens
            val dayPart = row.hourBucket.substringBefore('T')
            val hour = row.hourBucket.substringAfter('T', "").toIntOrNull() ?: 0
            cost += LlmPricing.cost(
                promptTokens = row.promptTokens,
                cacheHitTokens = row.cacheHitTokens,
                completionTokens = row.completionTokens,
                peak = LlmPricing.isPeakBucket(dayPart, hour),
            )
        }
        return Totals(tokens, cost)
    }

    /**
     * 今日累计 token 曲线，按 [range] 截取内存缓冲的窗口
     * （`30m` / `1h` / `6h` / `24h`，环形缓冲只覆盖约 1 小时，超出部分取到多少算多少），
     * 并降采样到不超过 [TARGET_POINTS] 个点。
     */
    suspend fun curve(ctx: Context, range: String): List<Double> = mutex.withLock {
        val n = size
        if (n <= 0) return emptyList()
        val minutes = when (range) {
            "30m" -> 30
            "6h" -> 360
            "24h" -> 1440
            else -> 60
        }
        val orderStart = if (n < capacity) 0 else head
        val windowLen = minOf(n, (minutes * 60 / intervalSeconds).coerceAtLeast(1))
        val from = n - windowLen
        downsample(series, orderStart, from, windowLen)
    }

    private suspend fun sampleLoop() {
        var seeded = false
        while (scope.isActive) {
            if (!seeded) seeded = runCatching { seedToday() }.getOrDefault(false)
            runCatching { sampleOnce() }.onFailure { log.warn(it) { "token 用量采样失败" } }
            delay(intervalSeconds * 1000L)
        }
    }

    /** 启动时把「今日」基线从库里读回（进程重启后今日计数不归零）；库不可用返回 `false`。 */
    private suspend fun seedToday(): Boolean {
        val prefix = LlmPricing.dayKey()
        val sum = usages.findAll(db.driver).getOrThrow()
            .filter { it.hourBucket.startsWith(prefix) }
            .sumOf { it.promptTokens + it.completionTokens }
        mutex.withLock {
            dayKey = prefix
            todayTotal = sum
        }
        return true
    }

    /** 采一次：求台账增量、累入今日曲线，增量非零就按小时桶记进待落库。 */
    private suspend fun sampleOnce() {
        val snap = ledger.snapshot()
        val dr = (snap.requests - lastRequests).coerceAtLeast(0)
        val dp = (snap.promptTokens - lastPrompt).coerceAtLeast(0)
        val dc = (snap.completionTokens - lastCompletion).coerceAtLeast(0)
        val dh = (snap.cacheHitTokens - lastHit).coerceAtLeast(0)
        lastRequests = snap.requests
        lastPrompt = snap.promptTokens
        lastCompletion = snap.completionTokens
        lastHit = snap.cacheHitTokens

        val consumed = dp + dc
        mutex.withLock {
            rollDay()
            if (consumed > 0) todayTotal += consumed
            series[head] = todayTotal.toDouble()
            head = (head + 1) % capacity
            if (size < capacity) size++
        }

        if (dr > 0 || dp > 0 || dc > 0 || dh > 0) {
            val bucket = LlmPricing.hourBucket()
            val delta = pending.getOrPut(bucket) { Delta() }
            delta.requests += dr
            delta.prompt += dp
            delta.hit += dh
            delta.completion += dc
            flush()
        }
    }

    /** 把待落库的增量逐桶 upsert 后清空；库不可用时保留本批（下一轮再写）。 */
    private suspend fun flush() {
        if (pending.isEmpty()) return
        val exec = runCatching { db.driver }.getOrNull() ?: return
        val now = Clock.System.now()
        for ((bucket, delta) in pending) {
            runCatching {
                usages.executeUpsert(exec, bucket, delta.requests, delta.prompt, delta.hit, delta.completion, now).getOrThrow()
            }.onFailure { log.warn(it) { "写入 token 用量失败" } }
        }
        pending.clear()
    }

    /** 跨过北京时间午夜就把今日累计归零。 */
    private fun rollDay() {
        val today = LlmPricing.dayKey()
        if (today == dayKey) return
        dayKey = today
        todayTotal = 0
    }

    /** 把环形缓冲的一段按时间顺序取出并降采样到不超过 [TARGET_POINTS] 个点。 */
    private fun downsample(src: DoubleArray, orderStart: Int, from: Int, len: Int): List<Double> {
        if (len <= TARGET_POINTS) {
            return (0 until len).map { src[(orderStart + from + it) % capacity] }
        }
        val step = len.toDouble() / TARGET_POINTS
        return (0 until TARGET_POINTS).map { bucket ->
            val startIndex = (bucket * step).toInt()
            val endIndex = ((bucket + 1) * step).toInt().coerceAtMost(len).coerceAtLeast(startIndex + 1)
            var sum = 0.0
            for (i in startIndex until endIndex) sum += src[(orderStart + from + i) % capacity]
            sum / (endIndex - startIndex)
        }
    }

    /** 某个小时桶待落库的增量。 */
    private class Delta {
        var requests: Long = 0L
        var prompt: Long = 0L
        var hit: Long = 0L
        var completion: Long = 0L
    }

    private companion object {
        /** 曲线降采样后的目标点数上限（与 `SystemMetricService.TARGET_POINTS` 一致）。 */
        const val TARGET_POINTS: Int = 30
    }
}
