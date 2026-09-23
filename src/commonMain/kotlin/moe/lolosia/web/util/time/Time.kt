package moe.lolosia.web.util.time

import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * 当前本地时间，仅用于日志展示。
 *
 * 写库的时间列一律用 `kotlin.time.Instant`（取值直接 `Clock.System.now()`），
 * 时区语义由类型保证；不要用 `LocalDateTime` 表示库中的时间。
 *
 * @author 洛洛希雅Lolosia
 */
public fun nowLocal(): LocalDateTime = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault())

/** 当前 epoch 毫秒（非库时间，如会话的「最后访问时间」）。 */
public fun nowEpochMs(): Long = Clock.System.now().toEpochMilliseconds()

/** 接口里时间字段的展示时区：固定 `+08:00`（与旧 JVM 版的 `@JsonFormat(timezone = "GMT+8")` 一致）。 */
public val DISPLAY_ZONE: TimeZone = TimeZone.of("+08:00")

/**
 * 时间 → 接口返回给前端的展示字符串（`yyyy-MM-dd HH:mm:ss`）。
 *
 * 旧 JVM 版用 `@JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")` 输出，
 * 前端直接把该字段当字符串显示 —— 换成 ISO-8601 会改变页面观感，故保持一致。
 * **只用于出参 DTO**；写库/比对仍然是 `Instant`。
 */
public fun Instant.toDisplayString(zone: TimeZone = DISPLAY_ZONE): String {
    val t = toLocalDateTime(zone)
    return "${t.year}-${(t.month.ordinal + 1).pad2()}-${t.day.pad2()} " +
        "${t.hour.pad2()}:${t.minute.pad2()}:${t.second.pad2()}"
}

private fun Int.pad2(): String = if (this < 10) "0$this" else "$this"
