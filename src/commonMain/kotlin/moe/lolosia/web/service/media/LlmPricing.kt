package moe.lolosia.web.service.media

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
 * 高峰 = 北京时间周一~周五 9:00–12:00、14:00–18:00；其余（含周末、法定节假日）空闲半价。
 * **只用于预估与预算拦截**；正式计费与台账一律以响应 `usage` 为准。
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

    /** 是否处于高峰时段。 */
    fun isPeak(at: Instant = Clock.System.now()): Boolean {
        val t = at.toLocalDateTime(BEIJING)
        if (t.dayOfWeek.ordinal >= 5) return false // MONDAY=0 … SATURDAY=5
        return t.hour in 9..11 || t.hour in 14..17
    }

    /** 北京时间当天的键（`yyyy-MM-dd`），用于日累计的重置判定。 */
    fun dayKey(at: Instant = Clock.System.now()): String {
        val t = at.toLocalDateTime(BEIJING)
        return "${t.year}-${(t.month.ordinal + 1).toString().padStart(2, '0')}-${t.day.toString().padStart(2, '0')}"
    }

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
