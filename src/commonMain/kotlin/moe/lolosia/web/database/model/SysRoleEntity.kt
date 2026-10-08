package moe.lolosia.web.database.model

import io.github.smyrgeorge.sqlx4k.annotation.Id
import io.github.smyrgeorge.sqlx4k.annotation.Table
import kotlin.time.Instant

/**
 * `sys_role`：组表（`type` 是组的稳定代号，`role_name` 是展示名）。
 *
 * `parentId` 指向父组，实现**组继承**（判定时递归合并祖先组的规则，见 `service.permission`）。
 *
 * @author 洛洛希雅Lolosia
 */
@Table("sys_role")
data class SysRoleEntity(
    @Id
    val id: Long = 0L,
    val roleName: String? = null,
    val type: String? = null,
    val parentId: Long? = null,
    val createdAt: Instant? = null,
    val createdBy: String? = null,
    val updatedAt: Instant? = null,
    val updatedBy: String? = null,
    val deleted: Boolean = false,
)
