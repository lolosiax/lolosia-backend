package moe.lolosia.web.database.model

import io.github.smyrgeorge.sqlx4k.annotation.Id
import io.github.smyrgeorge.sqlx4k.annotation.Table
import kotlin.time.Instant

/**
 * `sys_user_permission`：直接授予单个用户的权限规则（不经过组）。
 *
 * 与 [SysRolePermissionEntity] 同类，只是主体换成用户；判定时两者合并，来源不参与优先级。
 *
 * @author 洛洛希雅Lolosia
 */
@Table("sys_user_permission")
data class SysUserPermissionEntity(
    @Id
    val id: Long = 0L,
    val userId: String,
    val permission: String,
    val createdAt: Instant? = null,
    val createdBy: String? = null,
    val updatedAt: Instant? = null,
    val updatedBy: String? = null,
    val deleted: Boolean = false,
)
