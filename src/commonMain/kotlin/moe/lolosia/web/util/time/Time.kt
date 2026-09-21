package moe.lolosia.web.util.time

import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Clock
import kotlin.time.Duration

/**
 * 时间工具。
 *
 * 两条铁律：
 * - **日志**用 [nowLocal]（人看本地时间）；
 * - **库里 `timestamp` 列**一律用 [nowUtc]：sqlx4k 的 MySQL 连接会话时区是 `+00:00`
 *   （`select @@session.time_zone` 实测为 `+00:00`），写进去的裸值会被当成 UTC，
 *   若写本地时间就会与库端 `now()` 差一个时区（实测差 8 小时）。
 *
 * `timestamp` 列内部存的是瞬时值，各客户端按自己的会话时区显示（JVM 版连的是 `+08:00`，
 * 读同一条数据仍显示本地时间），所以按 UTC 写入是两端一致的。
 *
 * @author 洛洛希雅Lolosia
 */
public fun nowLocal(): LocalDateTime = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault())

/** 写库用的「现在」（UTC，与驱动会话时区一致）。 */
public fun nowUtc(): LocalDateTime = Clock.System.now().toLocalDateTime(TimeZone.UTC)

public fun nowEpochMs(): Long = Clock.System.now().toEpochMilliseconds()

/**
 * 时间加时长（**按 UTC 计算**，与写库口径一致）。
 *
 * kotlinx-datetime 只给 `LocalDateTime` 提供了「日期单位」的加法，`+ Duration` 需借 Instant 中转。
 */
public fun LocalDateTime.plusDuration(duration: Duration): LocalDateTime =
    (toInstant(TimeZone.UTC) + duration).toLocalDateTime(TimeZone.UTC)
