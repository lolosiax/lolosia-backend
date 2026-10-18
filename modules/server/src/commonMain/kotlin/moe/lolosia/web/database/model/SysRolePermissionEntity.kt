package moe.lolosia.web.database.model

import io.github.smyrgeorge.sqlx4k.annotation.Id
import io.github.smyrgeorge.sqlx4k.annotation.Table
import kotlin.time.Instant

/**
 * `sys_role_permission`：组（`sys_role`）上挂的权限规则。
 *
 * [permission] 是规则原文（`+a.b.*` / `-a.b.c` / `a.b.c`），解析与判定见 `service/permission/`。
 *
 * @author 洛洛希雅Lolosia
 */
@Table("sys_role_permission")
data class SysRolePermissionEntity(
    @Id
    val id: Long = 0L,
    val roleId: Long,
    val permission: String,
    val createdAt: Instant? = null,
    val createdBy: String? = null,
    val updatedAt: Instant? = null,
    val updatedBy: String? = null,
    val deleted: Boolean = false,
)
