package moe.lolosia.web.dto

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import moe.lolosia.web.database.model.SysRoleEntity
import moe.lolosia.web.database.model.SysUserEntity
import moe.lolosia.web.database.model.ViewUserRoleEntity
import moe.lolosia.web.util.time.toDisplayString

/**
 * 用户 / 角色 / 绑定相关的接口 DTO（入参与出参）。
 *
 * 约定（**以前端副本 `F:\Develop\Node\lolosia-web` 为准**，见 `AGENTS.md` §8）：
 * - 字段名与分页键沿用前端读的键：用户分页是 `{data, total}`、角色分页是 `{rows, total}`；
 * - `isUse` 线上是 **`1` / `0`**（前端表格 `row.isUse === 1` 判启用、编辑表单用 `1`/`0` 单选）；
 * - **出参一律不含 `password`**（旧版把 bcrypt 哈希回传给了前端，这里不再回传）；
 * - 时间字段是**展示字符串** `yyyy-MM-dd HH:mm:ss`（固定 +08:00），见 [toDisplayString]；
 *   库里的类型仍是 `kotlin.time.Instant`；
 * - 入参里 `null` = 不修改（由服务层的读-改-写实现）。
 *
 * @author 洛洛希雅Lolosia
 */

/** 查不到角色绑定时 `roleId` 的哨兵值（旧版沿用）。 */
const val ROLE_ID_UNKNOWN: Long = 0L

/** 用户没有任何组绑定时 `getUserInfo` 给出的默认组 id（最低权限的「访客」）。 */
const val ROLE_ID_DEFAULT: Long = 5L

/** 注册（`/api/register`）时给新用户的组 id（固定 4，即「普通用户」，见 `sql/sys_role.sql`）。 */
const val ROLE_ID_USER: Long = 4L

/** `sys_user` 的安全视图（不含 `password`）。 */
@Serializable
data class UserRow(
    val id: String,
    val userName: String,
    val realName: String? = null,
    val phone: String? = null,
    val email: String? = null,
    val avatar: String? = null,
    val isUse: Int? = null,
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
data class UserListRow(
    val id: String,
    val userId: String,
    val userName: String? = null,
    val realName: String? = null,
    val phone: String? = null,
    val email: String? = null,
    val avatar: String? = null,
    val isUse: Int? = null,
    val roleId: Long? = null,
    val roleName: String? = null,
    val type: String? = null,
    val roleType: String? = null,
    val createdAt: String? = null,
    val updatedAt: String? = null,
)

/** 分页用户列表；键名 `data` / `total` 是前端 `userManagement/index.vue` 读的（`data.data`）。 */
@Serializable
data class UserListResult(
    val data: List<UserListRow>,
    val total: Long,
)

/** 单个用户详情：`sys_user` 字段 + 其角色 id（旧版是扁平 Bundle）。 */
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

/**
 * `sys_role` 一行（一个「组」）；`parentId` 是父组 id（组继承，`null` 为根基组）。
 *
 * `menuId` / `menuTitle` 是已弃用的菜单字段，仅为前端兼容保留空数组。
 */
@Serializable
data class RoleRow(
    val id: Long,
    val roleName: String? = null,
    val type: String? = null,
    val parentId: Long? = null,
    val createdAt: String? = null,
    val createdBy: String? = null,
    val updatedAt: String? = null,
    val updatedBy: String? = null,
    val deleted: Boolean = false,
    val menuId: List<Long> = emptyList(),
    val menuTitle: List<String> = emptyList(),
)

/** 分页角色列表（键名 `rows` / `total`，与用户列表的 `data` 不同名，属前端历史遗留）。 */
@Serializable
data class RolePageResult(
    val rows: List<RoleRow>,
    val total: Long,
)

/** 用户-角色绑定视图的一行；`roleType` 与 `type` 同值（旧版两个键都给）。 */
@Serializable
data class UserRoleRow(
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
    /** 生效的权限规则串（所属组 + 用户直授合并）；只有 `/user/myRole` 填它，其余接口为 `null`。 */
    val rules: List<String>? = null,
)

/** 只改数据、不返回资源的接口统一回它（旧版是 `data: null`，前端只取 `msg`）。 */
@Serializable
class EmptyResult

/** 新建资源后回传主键（旧版 `/user/create` 的 `{id}`）。 */
@Serializable
data class IdRow(val id: String)

// ===== 入参 =====

/** 新建用户入参。`userName` 缺席时取手机号或邮箱；`roleId` 必填（旧版直接解引用）。 */
@Serializable
data class UserCreateReq(
    val userName: String? = null,
    val password: String? = null,
    val realName: String? = null,
    val phone: String? = null,
    val email: String? = null,
    val isUse: Int? = null,
    val roleId: Long,
)

/** 修改用户入参：**字段为 `null` = 不修改**；`password` 为空串 / `null` = 不改密码。 */
@Serializable
data class UserUpdateReq(
    val id: String,
    val userName: String? = null,
    val password: String? = null,
    val realName: String? = null,
    val phone: String? = null,
    val email: String? = null,
    val isUse: Int? = null,
    val roleId: Long? = null,
)

@Serializable
data class UserListReq(
    val pageNo: Int? = null,
    val pageSize: Int? = null,
    val keys: String? = null,
)

@Serializable
data class SearchReq(val keys: String? = null)

/** 按 id 批量取用户（`/user/get`）；数组里允许有 `null`（旧版如此）。 */
@Serializable
data class IdListReq(val idList: List<String?> = emptyList())

/**
 * 批量删除（`/user/delete`）。前端两种形状都发过：单行删除是数组 `["<uuid>"]`，
 * 多选删除是逗号串 `"<uuid>,<uuid>"`，这里统一收成 id 列表。
 */
@Serializable
data class IdsReq(
    @Serializable(with = IdsSerializer::class)
    val ids: List<String> = emptyList(),
)

/** 数组 / 逗号串 / 单条字符串都读成 id 列表（写法参考 [moe.lolosia.web.dto.CaptchaIdSerializer]）。 */
object IdsSerializer : KSerializer<List<String>> {
    private val delegate: KSerializer<List<String>> = ListSerializer(String.serializer())

    override val descriptor: SerialDescriptor = delegate.descriptor

    override fun serialize(encoder: Encoder, value: List<String>) {
        encoder.encodeString(value.joinToString(","))
    }

    override fun deserialize(decoder: Decoder): List<String> {
        val json = decoder as? JsonDecoder ?: return splitIds(decoder.decodeString())
        return when (val element = json.decodeJsonElement()) {
            is JsonArray -> element.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.map(String::trim)
                .filter(String::isNotEmpty)

            else -> splitIds((element as? JsonPrimitive)?.contentOrNull.orEmpty())
        }
    }

    private fun splitIds(raw: String): List<String> =
        raw.split(',').map(String::trim).filter(String::isNotEmpty)
}

@Serializable
data class UserIdReq(val userId: String)

/** 改密码；`id` 缺席时改的是登录人自己的密码。 */
@Serializable
data class UpdatePasswordReq(
    val id: String? = null,
    val origin: String,
    val target: String,
)

@Serializable
data class RolePageReq(
    val pageNo: Int? = null,
    val pageSize: Int? = null,
    val roleName: String? = null,
)

/** 新建组入参；`parentId` 是父组 id（组继承），不传即根基组。 */
@Serializable
data class RoleCreateReq(
    val roleName: String? = null,
    val type: String? = null,
    val parentId: Long? = null,
)

/** 修改组入参：**字段为 `null` = 不修改**。 */
@Serializable
data class RoleUpdateReq(
    val id: Long,
    val roleName: String? = null,
    val type: String? = null,
    val parentId: Long? = null,
)

@Serializable
data class RoleDestroyReq(val id: Long)

// ===== 实体 → DTO =====

fun SysUserEntity.toRow(): UserRow = UserRow(
    id = id,
    userName = userName,
    realName = realName,
    phone = phone,
    email = email,
    avatar = avatar,
    isUse = isUse.toFlag(),
    createdAt = createdAt?.toDisplayString(),
    createdBy = createdBy,
    updatedAt = updatedAt?.toDisplayString(),
    updatedBy = updatedBy,
    deleted = deleted,
)

fun SysUserEntity.toInfoRow(roleId: Long): UserInfoRow = UserInfoRow(
    id = id,
    userName = userName,
    realName = realName,
    phone = phone,
    email = email,
    avatar = avatar,
    isUse = isUse.toFlag(),
    createdAt = createdAt?.toDisplayString(),
    updatedAt = updatedAt?.toDisplayString(),
    roleId = roleId,
)

fun SysRoleEntity.toRow(): RoleRow = RoleRow(
    id = id,
    roleName = roleName,
    type = type,
    parentId = parentId,
    createdAt = createdAt?.toDisplayString(),
    createdBy = createdBy,
    updatedAt = updatedAt?.toDisplayString(),
    updatedBy = updatedBy,
    deleted = deleted,
)

/** 视图行 + 用户行合并成列表行；[user] 为 `null` 表示账户已不存在（旧版同此兜底）。 */
fun ViewUserRoleEntity.toListRow(user: SysUserEntity?): UserListRow = UserListRow(
    id = user?.id ?: userId,
    userId = userId,
    userName = user?.userName ?: userName,
    realName = user?.realName ?: MISSING_ACCOUNT,
    phone = user?.phone ?: phone,
    email = user?.email ?: email,
    avatar = user?.avatar ?: avatar,
    isUse = (user?.isUse ?: false).toFlag(),
    roleId = roleId,
    roleName = roleName,
    type = type,
    roleType = type,
    createdAt = (user?.createdAt ?: createdAt)?.toDisplayString(),
    updatedAt = (user?.updatedAt ?: updatedAt)?.toDisplayString(),
)

fun ViewUserRoleEntity.toUserRoleRow(): UserRoleRow = UserRoleRow(
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

/** 布尔 → 接口上的 `1` / `0`（前端按数字判启用）。 */
internal fun Boolean?.toFlag(): Int? = this?.let { if (it) 1 else 0 }

/** 接口上的 `1` / `0` → 布尔（`0` 为 false）。 */
internal fun Int?.toBool(): Boolean? = this?.let { it != 0 }

/** 账户不存在时列表里的占位姓名（旧版同此）。 */
private const val MISSING_ACCOUNT: String = "<账户不存在>"
