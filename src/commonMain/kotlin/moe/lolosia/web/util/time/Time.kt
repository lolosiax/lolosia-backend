package moe.lolosia.web.util.time

import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Clock

/**
 * 时间工具。
 *
 * **约定**
 * - 库里所有时间列一律用 **`kotlin.time.Instant`**（取值直接 `Clock.System.now()`），
 *   **不要用 `LocalDateTime`**：sqlx4k 原生支持 Instant，编码走 `Instant.toTimestampString(TimeZone.UTC)`、
 *   解码走 `Column.asInstant()`（其注释明确写着「没有偏移量就按 UTC 解释」），全程按 UTC 往返，
 *   时区语义由类型系统保证，业务代码不需要记得「写库要用 UTC」。
 * - 只有**日志**用 [nowLocal] 展示本地时间（人看的）。
 *
 * 背景：sqlx4k 的 MySQL 连接会话时区是 `+00:00`（`select @@session.time_zone` 可验），
 * 而列是 `TIMESTAMP`（库内按会话时区换算）—— 裸 `LocalDateTime` 会被当成 UTC 存，于是与库端 `now()`
 * 差一个时区（实测差 8 小时）。用 Instant 后不存在这个问题。
 *
 * @author 洛洛希雅Lolosia
 */
public fun nowLocal(): LocalDateTime = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault())

/** epoch 毫秒（会话内的「最后访问时间」这类非库时间用）。 */
public fun nowEpochMs(): Long = Clock.System.now().toEpochMilliseconds()
