package moe.lolosia.web.db.model

import io.github.smyrgeorge.sqlx4k.annotation.Id
import io.github.smyrgeorge.sqlx4k.annotation.Table
import kotlin.time.Instant

/**
 * `view_user_role`：用户 + 角色 + 绑定的联表视图（只读，不写）。
 *
 * 视图内部已过滤 `sys_user.deleted != 1` / `sys_role.deleted != 1`，`deleted` 来自 `sys_user_roles`。
 *
 * @author 洛洛希雅Lolosia
 */
@Table("view_user_role")
public data class ViewUserRoleEntity(
    @Id
    val id: Long = 0L,
    val userId: String,
    val userName: String? = null,
    val realName: String? = null,
    val phone: String? = null,
    val email: String? = null,
    val avatar: String? = null,
    val roleId: Long? = null,
    val roleName: String? = null,
    val type: String? = null,
    val createdAt: Instant? = null,
    val updatedAt: Instant? = null,
    val createdBy: String? = null,
    val updatedBy: String? = null,
    val deleted: Boolean = false,
)
