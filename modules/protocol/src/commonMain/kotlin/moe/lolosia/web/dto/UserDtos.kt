package moe.lolosia.web.dto

import kotlinx.serialization.Serializable

/**
 * 用户相关的跨端契约。
 *
 * 端侧只需要读当前登录用户，故这里只保留 [UserInfoRow] 与角色 id 常量；
 * 用户管理的入参 / 分页行仍留在服务端的 `SystemDtos.kt`。
 *
 * @author 洛洛希雅Lolosia
 */

/** 查不到角色绑定时 `roleId` 的哨兵值。 */
const val ROLE_ID_UNKNOWN: Long = 0L

/** 用户没有任何组绑定时给出的默认组 id（最低权限的「访客」）。 */
const val ROLE_ID_DEFAULT: Long = 5L

/** 注册时给新用户的组 id（固定 4，即「普通用户」，见 `sql/sys_role.sql`）。 */
const val ROLE_ID_USER: Long = 4L

/** 单个用户详情：`sys_user` 的安全字段 + 其角色 id。 */
@Serializable
data class UserInfoRow(
    val id: String,
    val userName: String,
    val realName: String? = null,
    val phone: String? = null,
    val email: String? = null,
    val avatar: String? = null,
    val isUse: Int? = null,
    val createdAt: String? = null,
    val updatedAt: String? = null,
    val roleId: Long = ROLE_ID_DEFAULT,
)
