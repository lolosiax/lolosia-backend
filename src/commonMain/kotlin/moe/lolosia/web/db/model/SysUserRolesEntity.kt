package moe.lolosia.web.db.model

import io.github.smyrgeorge.sqlx4k.annotation.Id
import io.github.smyrgeorge.sqlx4k.annotation.Table
import kotlin.time.Instant

/**
 * `sys_user_roles`：用户-角色绑定（用户 id 是 UUID 文本，角色 id 是自增 int）。
 *
 * @author 洛洛希雅Lolosia
 */
@Table("sys_user_roles")
public data class SysUserRolesEntity(
    @Id
    val id: Long = 0L,
    val userId: String,
    val roleId: Long,
    val createdAt: Instant? = null,
    val createdBy: String? = null,
    val updatedAt: Instant? = null,
    val updatedBy: String? = null,
    val deleted: Boolean = false,
)
