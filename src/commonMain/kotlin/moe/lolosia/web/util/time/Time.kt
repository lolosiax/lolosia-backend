package moe.lolosia.web.util.time

import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Clock

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
