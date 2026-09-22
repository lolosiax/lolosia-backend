package moe.lolosia.web.dto

import kotlinx.serialization.Serializable
import moe.lolosia.web.db.model.SysRoleEntity
import moe.lolosia.web.db.model.SysUserEntity
import moe.lolosia.web.db.model.ViewUserRoleEntity
import moe.lolosia.web.util.time.toDisplayString

/**
 * 用户 / 角色 / 绑定相关的接口 DTO（入参与出参）。
 *
 * 约定：
 * - 字段名沿用旧 JVM 版的 JSON 键（`roleType`、`isUse`、`list` / `rows` 这类历史命名也一并保留），
 *   避免前端跟着改；
 * - **出参一律不含 `password`**（旧版把 bcrypt 哈希回传给了前端，这里不再回传）；
 * - 时间字段是**展示字符串** `yyyy-MM-dd HH:mm:ss`（固定 +08:00），见 [toDisplayString]；
 *   库里的类型仍是 `kotlin.time.Instant`；
 * - 这些是"接口契约"类型，不放业务规则，业务在 `service/system/`。
 *
 * @author 洛洛希雅Lolosia
 */

/** 查不到角色绑定时 `roleId` 的哨兵值（旧版沿用）。 */
public const val ROLE_ID_UNKNOWN: Long = 0L

/** 用户没有任何角色绑定时 `getUserInfo` 给出的默认角色 id（旧版沿用，即「教师」）。 */
public const val ROLE_ID_DEFAULT: Long = 3L

/** `sys_user` 的安全视图（不含 `password`）。 */
@Serializable
public data class UserRow(
    val id: String,
    val userName: String,
    val realName: String? = null,
    val phone: String? = null,
    val email: String? = null,
    val avatar: String? = null,
    val isUse: Boolean? = null,
    val createdAt: String? = null,
    val createdBy: String? = null,
    val updatedAt: String? = null,
    val updatedBy: String? = null,
    val deleted: Boolean = false,
)

/**
 * 用户列表行：`view_user_role` 的一行 + 该用户的 `sys_user` 字段（旧版是两者 Bundle 合并）。
 *
 * `id` 是**用户 id**（视图里的 `id` 是绑定 id，合并时被用户覆盖）；`roleType` 与 `type` 同值，
 * 旧版两个键都给了前端，故都保留。
 */
@Serializable
public data class UserListRow(
    val id: String,
    val userId: String,
    val userName: String? = null,
    val realName: String? = null,
    val phone: String? = null,
    val email: String? = null,
    val avatar: String? = null,
    val isUse: Boolean? = null,
    val roleId: Long? = null,
    val roleName: String? = null,
    val type: String? = null,
    val roleType: String? = null,
    val createdAt: String? = null,
    val updatedAt: String? = null,
)

/** 分页用户列表（旧版键名 `list` / `total`）。 */
@Serializable
public data class UserListResult(
    val list: List<UserListRow>,
    val total: Long,
)

/** 单个用户详情：`sys_user` 字段 + 其角色 id（旧版是扁平 Bundle）。 */
@Serializable
public data class UserInfoRow(
    val id: String,
    val userName: String,
    val realName: String? = null,
    val phone: String? = null,
    val email: String? = null,
    val avatar: String? = null,
    val isUse: Boolean? = null,
    val createdAt: String? = null,
    val updatedAt: String? = null,
    val roleId: Long = ROLE_ID_DEFAULT,
)

/** `sys_role` 一行；`menuId` / `menuTitle` 是已弃用的菜单字段，仅为前端兼容保留空数组。 */
@Serializable
public data class RoleRow(
    val id: Long,
    val roleName: String? = null,
    val type: String? = null,
    val createdAt: String? = null,
    val createdBy: String? = null,
    val updatedAt: String? = null,
    val updatedBy: String? = null,
    val deleted: Boolean = false,
    val menuId: List<Long> = emptyList(),
    val menuTitle: List<String> = emptyList(),
)

/** 分页角色列表（旧版键名 `rows` / `total`，与用户列表的 `list` 不同名，属历史遗留）。 */
@Serializable
public data class RolePageResult(
    val rows: List<RoleRow>,
    val total: Long,
)

/** 用户-角色绑定视图的一行；`roleType` 与 `type` 同值（旧版两个键都给）。 */
@Serializable
public data class UserRoleRow(
    val id: Long = 0L,
    val userId: String,
    val userName: String? = null,
    val realName: String? = null,
    val phone: String? = null,
    val email: String? = null,
    val avatar: String? = null,
    val roleId: Long = ROLE_ID_UNKNOWN,
    val roleName: String? = null,
    val type: String? = null,
    val roleType: String? = null,
    val createdAt: String? = null,
    val updatedAt: String? = null,
)

/** 新建用户入参。`userName` 缺席时取手机号或邮箱；`roleId` 必填（旧版直接解引用）。 */
@Serializable
public data class UserCreateReq(
    val userName: String? = null,
    val password: String? = null,
    val realName: String? = null,
    val phone: String? = null,
    val email: String? = null,
    val isUse: Boolean? = null,
    val roleId: Long,
)

/** 修改用户入参：**字段为 `null` = 不修改**；`password` 为空串 / `null` = 不改密码。 */
@Serializable
public data class UserUpdateReq(
    val id: String,
    val userName: String? = null,
    val password: String? = null,
    val realName: String? = null,
    val phone: String? = null,
    val email: String? = null,
    val isUse: Boolean? = null,
    val roleId: Long? = null,
)

@Serializable
public data class RoleCreateReq(
    val roleName: String? = null,
    val type: String? = null,
)

/** 修改角色入参：**字段为 `null` = 不修改**。 */
@Serializable
public data class RoleUpdateReq(
    val id: Long,
    val roleName: String? = null,
    val type: String? = null,
)

// ===== 实体 → DTO =====

public fun SysUserEntity.toRow(): UserRow = UserRow(
    id = id,
    userName = userName,
    realName = realName,
    phone = phone,
    email = email,
    avatar = avatar,
    isUse = isUse,
    createdAt = createdAt?.toDisplayString(),
    createdBy = createdBy,
    updatedAt = updatedAt?.toDisplayString(),
    updatedBy = updatedBy,
    deleted = deleted,
)

public fun SysUserEntity.toInfoRow(roleId: Long): UserInfoRow = UserInfoRow(
    id = id,
    userName = userName,
    realName = realName,
    phone = phone,
    email = email,
    avatar = avatar,
    isUse = isUse,
    createdAt = createdAt?.toDisplayString(),
    updatedAt = updatedAt?.toDisplayString(),
    roleId = roleId,
)

public fun SysRoleEntity.toRow(): RoleRow = RoleRow(
    id = id,
    roleName = roleName,
    type = type,
    createdAt = createdAt?.toDisplayString(),
    createdBy = createdBy,
    updatedAt = updatedAt?.toDisplayString(),
    updatedBy = updatedBy,
    deleted = deleted,
)

/** 视图行 + 用户行合并成列表行；[user] 为 `null` 表示账户已不存在（旧版同此兜底）。 */
public fun ViewUserRoleEntity.toListRow(user: SysUserEntity?): UserListRow = UserListRow(
    id = user?.id ?: userId,
    userId = userId,
    userName = user?.userName ?: userName,
    realName = user?.realName ?: MISSING_ACCOUNT,
    phone = user?.phone ?: phone,
    email = user?.email ?: email,
    avatar = user?.avatar ?: avatar,
    isUse = user?.isUse ?: false,
    roleId = roleId,
    roleName = roleName,
    type = type,
    roleType = type,
    createdAt = (user?.createdAt ?: createdAt)?.toDisplayString(),
    updatedAt = (user?.updatedAt ?: updatedAt)?.toDisplayString(),
)

public fun ViewUserRoleEntity.toUserRoleRow(): UserRoleRow = UserRoleRow(
    id = id,
    userId = userId,
    userName = userName,
    realName = realName,
    phone = phone,
    email = email,
    avatar = avatar,
    roleId = roleId ?: ROLE_ID_UNKNOWN,
    roleName = roleName,
    type = type,
    roleType = type,
    createdAt = createdAt?.toDisplayString(),
    updatedAt = updatedAt?.toDisplayString(),
)

/** 账户不存在时列表里的占位姓名（旧版同此）。 */
private const val MISSING_ACCOUNT: String = "<账户不存在>"
