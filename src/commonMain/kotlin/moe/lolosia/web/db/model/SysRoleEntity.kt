package moe.lolosia.web.db.model

import io.github.smyrgeorge.sqlx4k.annotation.Id
import io.github.smyrgeorge.sqlx4k.annotation.Table
import kotlin.time.Instant

/**
 * `sys_role`：角色表。
 *
 * @author 洛洛希雅Lolosia
 */
@Table("sys_role")
public data class SysRoleEntity(
    @Id
    val id: Long = 0L,
    val roleName: String? = null,
    val type: String? = null,
    val createdAt: Instant? = null,
    val createdBy: String? = null,
    val updatedAt: Instant? = null,
    val updatedBy: String? = null,
    val deleted: Boolean = false,
)
