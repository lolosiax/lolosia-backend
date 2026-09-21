package moe.lolosia.web.db.model

import io.github.smyrgeorge.sqlx4k.annotation.Id
import io.github.smyrgeorge.sqlx4k.annotation.Table
import kotlinx.datetime.LocalDateTime

/**
 * `sys_sessions`：会话表，`data` 存整个会话的 JSON。
 *
 * `id` 由应用生成（UUID 文本），故标 `@Id(insert = true)` 让生成器把它写进 INSERT。
 *
 * @author 洛洛希雅Lolosia
 */
@Table("sys_sessions")
public data class SessionEntity(
    @Id(insert = true)
    val id: String,
    val data: String = "{}",
    val createdAt: LocalDateTime? = null,
    val updatedAt: LocalDateTime? = null,
    val deleted: Boolean = false,
)
