package moe.lolosia.web.service.media

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import moe.lolosia.web.dto.MediaAnalyzeUsage
import moe.lolosia.web.dto.MediaAnalyzeUsageItem
import moe.lolosia.web.settings.AppSettings
import moe.lolosia.web.util.llm.ChatUsage
import moe.lolosia.web.util.router.Injectable
import moe.lolosia.web.util.router.Service
import kotlin.time.Clock

/**
 * 全局 token 台账（`doc/vision-autotag-design.md` §6.4）。
 *
 * 每次外发请求响应里的 `usage` 累加进来：进程内计数 + 按 provider 汇总 + **今日**累计
 * （跨过北京时间午夜自动归零）。`MediaAnalyzeService` 在每批请求前用它做**日配额拦截**。
 *
 * 计费口径以响应 `usage` 为准（[LlmPricing.cost] 只用于估算展示）。
 *
 * @author 洛洛希雅Lolosia
 */
@Injectable
@Service
class TokenLedger(settings: AppSettings) {

    private val config = settings.files.media.vision
    private val mutex = Mutex()

    private var requests = 0L
    private var promptTokens = 0L
    private var completionTokens = 0L
    private var cacheHitTokens = 0L
    private val byProvider = mutableMapOf<String, Counter>()

    private var dayKey: String = LlmPricing.dayKey()
    private var todayPrompt = 0L
    private var todayCompletion = 0L

    /** 记一次调用；`usage` 为 `null`（provider 未回传）时只计一次请求数。 */
    suspend fun record(provider: String, usage: ChatUsage?) {
        mutex.withLock {
            rollDay()
            requests++
            val p = usage?.promptTokens ?: 0
            val c = usage?.completionTokens ?: 0
            val h = usage?.cacheHitTokens ?: 0
            promptTokens += p
            completionTokens += c
            cacheHitTokens += h
            todayPrompt += p
            todayCompletion += c
            val counter = byProvider.getOrPut(provider) { Counter() }
            counter.requests++
            counter.prompt += p
            counter.completion += c
        }
    }

    /** 今日输入 token 是否已达/超过日配额。 */
    suspend fun overDailyBudget(): Boolean = mutex.withLock {
        rollDay()
        config.budget.dailyPromptTokenLimit > 0 && todayPrompt >= config.budget.dailyPromptTokenLimit
    }

    /** 今日已消耗的输入 token。 */
    suspend fun todayPromptTokens(): Long = mutex.withLock {
        rollDay()
        todayPrompt
    }

    /** 台账快照。 */
    suspend fun snapshot(): MediaAnalyzeUsage = mutex.withLock {
        rollDay()
        val peak = LlmPricing.isPeak(Clock.System.now())
        MediaAnalyzeUsage(
            requests = requests,
            promptTokens = promptTokens,
            completionTokens = completionTokens,
            cacheHitTokens = cacheHitTokens,
            todayCost = LlmPricing.cost(todayPrompt, 0, todayCompletion, peak),
            overBudget = config.budget.dailyPromptTokenLimit > 0 && todayPrompt >= config.budget.dailyPromptTokenLimit,
            items = byProvider.map { (id, c) ->
                MediaAnalyzeUsageItem(id, c.requests, c.prompt, c.completion)
            }.sortedByDescending { it.promptTokens },
        )
    }

    /** 跨过北京时间午夜就把「今日」计数归零。 */
    private fun rollDay() {
        val today = LlmPricing.dayKey()
        if (today == dayKey) return
        dayKey = today
        todayPrompt = 0
        todayCompletion = 0
    }

    /** 按 provider 的累计计数。 */
    private class Counter {
        var requests: Long = 0
        var prompt: Long = 0
        var completion: Long = 0
    }
}
