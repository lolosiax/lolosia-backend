package moe.lolosia.web.database.model

import io.github.smyrgeorge.sqlx4k.annotation.Id
import io.github.smyrgeorge.sqlx4k.annotation.Table
import kotlin.time.Instant

/**
 * `sys_cache`：程序用的键值缓存（按 `path` 分组、`tag` 定位，`expires_at` 过期，可随时清空）。
 *
 * - 列名按 snake_case 自动映射（`expiresAt` ↔ `expires_at`），无需逐个 `@Column`；
 * - `id` 是自增主键：`@Id` 默认 `insert = false`，即 INSERT 不带它、由库生成后回填（MySQL 走 `LAST_INSERT_ID()`）；
 * - 时间列一律用 `Instant`。
 *
 * @author 洛洛希雅Lolosia
 */
@Table("sys_cache")
public data class SysCacheEntity(
    @Id
    val id: Long = 0L,
    val path: String,
    val tag: String? = null,
    val value: String? = null,
    val expiresAt: Instant? = null,
    val createdBy: String? = null,
    val createdAt: Instant? = null,
    val updatedBy: String? = null,
    val updatedAt: Instant? = null,
    val deleted: Boolean? = null,
)
