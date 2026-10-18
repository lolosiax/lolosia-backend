package moe.lolosia.web.service.media

import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * DeepSeek 价目表与「空闲 / 高峰」时段判定（`doc/vision-autotag-design.md` §3.5）。
 *
 * 口径（元 / 百万 token）：
 *
 * | | 空闲 | 高峰 |
 * | --- | --- | --- |
 * | 输入（缓存命中） | 0.02 | 0.04 |
 * | 输入（缓存未命中） | 1 | 2 |
 * | 输出 | 4 | 8 |
 *
 * 高峰 = 北京时间**周一~周五**（**不含中国法定节假日**）9:00–12:00、14:00–18:00；
 * 其余时段（含周末、法定节假日全天）空闲半价。周末即使调休上班也按谷段计（需求口径如此）。
 *
 * **只用于预估、预算拦截与仪表盘展示**；正式计费与台账一律以响应 `usage` 为准。
 *
 * @author 洛洛希雅Lolosia
 */
object LlmPricing {

    private val BEIJING: TimeZone = TimeZone.of("+08:00")

    private const val MISS_OFF_PEAK: Double = 1.0
    private const val MISS_PEAK: Double = 2.0
    private const val HIT_OFF_PEAK: Double = 0.02
    private const val HIT_PEAK: Double = 0.04
    private const val OUT_OFF_PEAK: Double = 4.0
    private const val OUT_PEAK: Double = 8.0

    /**
     * 中国法定节假日的**放假日期**（`yyyy-MM-dd`）。来源：国务院办公厅历年「部分节假日安排的通知」。
     *
     * 周末恒为谷段，故这里只列「放假」的日子（含落在工作日的、以及跨到周末的连休）；
     * **调休上班的周末不列入**（按需求口径仍算谷段）。跨年后需补录当年通知。
     */
    private val HOLIDAYS: Set<String> = setOf(
        // 2024
        "2024-01-01",
        "2024-02-10", "2024-02-11", "2024-02-12", "2024-02-13", "2024-02-14", "2024-02-15", "2024-02-16", "2024-02-17",
        "2024-04-04", "2024-04-05", "2024-04-06",
        "2024-05-01", "2024-05-02", "2024-05-03", "2024-05-04", "2024-05-05",
        "2024-06-10",
        "2024-09-15", "2024-09-16", "2024-09-17",
        "2024-10-01", "2024-10-02", "2024-10-03", "2024-10-04", "2024-10-05", "2024-10-06", "2024-10-07",
        // 2025
        "2025-01-01",
        "2025-01-28", "2025-01-29", "2025-01-30", "2025-01-31", "2025-02-01", "2025-02-02", "2025-02-03", "2025-02-04",
        "2025-04-04", "2025-04-05", "2025-04-06",
        "2025-05-01", "2025-05-02", "2025-05-03", "2025-05-04", "2025-05-05",
        "2025-05-31", "2025-06-01", "2025-06-02",
        "2025-10-01", "2025-10-02", "2025-10-03", "2025-10-04", "2025-10-05", "2025-10-06", "2025-10-07", "2025-10-08",
        // 2026
        "2026-01-01", "2026-01-02", "2026-01-03",
        "2026-02-15", "2026-02-16", "2026-02-17", "2026-02-18", "2026-02-19", "2026-02-20", "2026-02-21", "2026-02-22", "2026-02-23",
        "2026-04-04", "2026-04-05", "2026-04-06",
        "2026-05-01", "2026-05-02", "2026-05-03", "2026-05-04", "2026-05-05",
        "2026-06-19", "2026-06-20", "2026-06-21",
        "2026-09-25", "2026-09-26", "2026-09-27",
        "2026-10-01", "2026-10-02", "2026-10-03", "2026-10-04", "2026-10-05", "2026-10-06", "2026-10-07",
    )

    /** 是否处于高峰时段（北京时间周一~周五、非节假日、9–12 / 14–18）。 */
    fun isPeak(at: Instant = Clock.System.now()): Boolean {
        val t = at.toLocalDateTime(BEIJING)
        if (t.dayOfWeek.ordinal >= 5) return false // MONDAY=0 … SATURDAY=5
        if (dayKey(at) in HOLIDAYS) return false
        return isPeakHour(t.hour)
    }

    /** 北京时间当天的键（`yyyy-MM-dd`），用于日累计的重置判定。 */
    fun dayKey(at: Instant = Clock.System.now()): String {
        val t = at.toLocalDateTime(BEIJING)
        return buildDay(t.year, t.month.ordinal + 1, t.day)
    }

    /** 北京时间小时桶（`yyyy-MM-ddTHH`），供按小时聚合的峰谷计价用。 */
    fun hourBucket(at: Instant = Clock.System.now()): String {
        val t = at.toLocalDateTime(BEIJING)
        return "${buildDay(t.year, t.month.ordinal + 1, t.day)}T${t.hour.toString().padStart(2, '0')}"
    }

    /**
     * 按小时桶判定是否高峰：[dayPart] 是 `yyyy-MM-dd` 的日期部分，[hour] 是 0–23 的小时。
     *
     * 用于把**已经落库的小时桶**还原成当时的峰谷档位（逐桶计价）。
     */
    fun isPeakBucket(dayPart: String, hour: Int): Boolean {
        if (!isPeakHour(hour)) return false
        if (dayPart in HOLIDAYS) return false
        val date = runCatching { LocalDate.parse(dayPart) }.getOrNull() ?: return false
        return date.dayOfWeek.ordinal < 5
    }

    /** 高峰时段的小时（北京时间）：9–11 与 14–17。 */
    private fun isPeakHour(hour: Int): Boolean = hour in 9..11 || hour in 14..17

    private fun buildDay(year: Int, month: Int, day: Int): String =
        "$year-${month.toString().padStart(2, '0')}-${day.toString().padStart(2, '0')}"

    /** 按用量估算成本（元）。[cacheHitTokens] 从 [promptTokens] 里划出，余下按未命中计。 */
    fun cost(
        promptTokens: Long,
        cacheHitTokens: Long,
        completionTokens: Long,
        peak: Boolean,
    ): Double {
        val hit = cacheHitTokens.coerceIn(0, promptTokens)
        val miss = promptTokens - hit
        val (pMiss, pHit, pOut) = if (peak) {
            Triple(MISS_PEAK, HIT_PEAK, OUT_PEAK)
        } else {
            Triple(MISS_OFF_PEAK, HIT_OFF_PEAK, OUT_OFF_PEAK)
        }
        return (miss * pMiss + hit * pHit + completionTokens * pOut) / 1_000_000.0
    }
}
