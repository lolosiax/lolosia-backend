package moe.lolosia.web.service.system

import kotlin.time.Clock
import kotlin.time.Instant
import kotlin.uuid.Uuid
import moe.lolosia.web.database.repository.SysUserRepository
import moe.lolosia.web.database.repository.SysUserRoleRepository
import moe.lolosia.web.database.repository.ViewUserRoleRepository
import moe.lolosia.web.dto.ROLE_ID_DEFAULT
import moe.lolosia.web.dto.UserCreateReq
import moe.lolosia.web.dto.UserInfoRow
import moe.lolosia.web.dto.UserListResult
import moe.lolosia.web.dto.UserListRow
import moe.lolosia.web.dto.UserRow
import moe.lolosia.web.dto.UserUpdateReq
import moe.lolosia.web.dto.toBool
import moe.lolosia.web.dto.toInfoRow
import moe.lolosia.web.dto.toListRow
import moe.lolosia.web.dto.toRow
import moe.lolosia.web.util.crypto.BCrypt
import moe.lolosia.web.util.router.Injectable
import moe.lolosia.web.util.session.Context

/**
 * 用户服务（迁移自旧 JVM 版的 `UserService`，返回值由 `Bundle` 改为强类型 DTO）。
 *
 * [ctx] 是请求上下文，**首参透传**（与旧族 `ctx: Context` 同位）：语句执行句柄从它取（`ctx.db`），
 * 需要事务时用 `ctx.transaction { }`，本层不持有连接池。
 *
 * 与旧版的**有意差异**（都记在 `doc/开发记忆.md`）：
 * - 出参不再带 `password`（旧版把 bcrypt 哈希回传了）；
 * - `delete` 除了软删角色绑定，**同时软删 `sys_user`**（旧版只删绑定，用户仍能登录）；
 * - 时间字段输出 `yyyy-MM-dd HH:mm:ss`（固定 +08:00），与旧版 `@JsonFormat` 一致；
 * - 旧版依赖 Ebean `@SoftDelete` 自动过滤，这里 `deleted = 0` 由仓库 SQL 显式写；
 * - 头像上传（`avatarPathOf` / `saveAvatar`）与 `my*`（需登录态）不在这里，属 P1 / 0.8。
 *
 * `operatorId` 是「谁在操作」（审计列的 `created_by` / `updated_by`），仍**显式传参**而不从
 * `ctx.userId` 隐式取 —— 注册链路（`LoginService.register`）没有登录态，隐式取会让「记谁名下」
 * 这件事变得不明显。传 `null` 表示没有操作者。
 *
 * @author 洛洛希雅Lolosia
 */
@Injectable
public class UserService(
    private val users: SysUserRepository,
    private val bindings: SysUserRoleRepository,
    private val view: ViewUserRoleRepository,
) {

    /**
     * 分页列表：`view_user_role` 分页 + 每行补上该用户的 `sys_user` 字段。
     *
     * 视图行与用户是一对一（`view_user_role` 内连接 `sys_user`），所以按 `userId` 逐行取用户；
     * `@Query` 不支持 `IN (列表)`（见 §2-13），N+1 是这条限制下的必然代价（默认每页 10 行）。
     *
     * 与旧版的差异：旧版把关键字拆成「用户 id 集合 OR 角色 id 集合」再过滤视图，这里直接在视图上
     * `LIKE`（多匹配了 `type` 一列），语义等价、SQL 少两条。
     */
    public suspend fun list(ctx: Context, pageNo: Int = 1, pageSize: Int = 10, keys: String? = null): UserListResult {
        val pattern = likePattern(keys)
        val total = view.countByKeys(ctx.db, pattern).getOrThrow()
        val rows = view.findAllByKeys(ctx.db, pattern, pageSize, (pageNo - 1) * pageSize).getOrThrow()
        val list = rows.map { row -> row.toListRow(users.findOneById(ctx.db, row.userId).getOrThrow()) }
        return UserListResult(list, total)
    }

    /** 按关键字模糊搜索用户（旧版限制 10 条，关键字为空则返回前 10 条）。 */
    public suspend fun searching(ctx: Context, keys: String?, limit: Int = SEARCH_LIMIT): List<UserRow> =
        users.findAllByKeys(ctx.db, likePattern(keys), limit).getOrThrow().map { it.toRow() }

    /** 按 id 批量取用户（循环单条：`@Query` 不支持 `IN`；查不到的直接略过，与旧版一致）。 */
    public suspend fun get(ctx: Context, ids: List<String>): List<UserRow> =
        ids.mapNotNull { users.findOneById(ctx.db, it).getOrThrow() }.map { it.toRow() }

    /**
     * 新建用户：手机号 / 邮箱至少一个，用户名缺省取手机号或邮箱，密码按 bcrypt(cost=10) 入库，
     * 随后建角色绑定，最后从视图读回列表行。
     */
    public suspend fun create(ctx: Context, req: UserCreateReq, operatorId: String? = null): UserListRow {
        val phone = req.phone
        val email = req.email
        if (phone == null && email == null) throw IllegalArgumentException("请输入手机号或邮箱")

        val userName = req.userName ?: phone ?: email!!
        val exists = users.countByUserNameOrPhoneOrEmail(ctx.db, userName, phone, email).getOrThrow() > 0
        if (exists) throw IllegalStateException("同名用户已存在")

        val id = Uuid.random().toString()
        val now = Clock.System.now()
        val operator = operatorId ?: id
        users.executeInsert(
            context = ctx.db,
            id = id,
            userName = userName,
            password = req.password?.let(::hash),
            realName = req.realName,
            phone = phone,
            email = email,
            avatar = null,
            isUse = req.isUse.toBool() ?: true,
            createdAt = now,
            createdBy = operator,
            updatedAt = now,
            updatedBy = operator,
        ).getOrThrow()
        bindings.executeInsert(ctx.db, id, req.roleId, now, operator, now, operator).getOrThrow()

        val row = view.findOneByUserId(ctx.db, id).getOrThrow()
            ?: error("用户已写入但视图读不回来：id=$id")
        return row.toListRow(users.findOneById(ctx.db, id).getOrThrow())
    }

    /** 单个用户详情：`sys_user` 字段 + 角色 id；没有任何绑定时给旧版的默认角色 id。 */
    public suspend fun info(ctx: Context, id: String): UserInfoRow {
        val user = users.findOneById(ctx.db, id).getOrThrow() ?: throw NoSuchElementException("找不到用户")
        val roleId = bindings.findOneByUserId(ctx.db, id).getOrThrow()?.roleId ?: ROLE_ID_DEFAULT
        return user.toInfoRow(roleId)
    }

    /**
     * 修改用户：`null` 的字段保持原值（读-改-写），`password` 为空则不动密码；
     * `roleId` 给了就顺带改绑定（没绑定则补一条）。
     */
    public suspend fun update(ctx: Context, req: UserUpdateReq, operatorId: String? = null) {
        val current = users.findOneById(ctx.db, req.id).getOrThrow() ?: throw NoSuchElementException("用户不存在")
        val now = Clock.System.now()
        val password = req.password?.takeIf { it.isNotEmpty() }?.let(::hash) ?: current.password

        users.executeUpdateById(
            context = ctx.db,
            id = current.id,
            userName = req.userName ?: current.userName,
            password = password,
            realName = req.realName ?: current.realName,
            phone = req.phone ?: current.phone,
            email = req.email ?: current.email,
            avatar = current.avatar,
            isUse = req.isUse.toBool() ?: current.isUse,
            updatedAt = now,
            updatedBy = operatorId ?: current.updatedBy,
        ).getOrThrow()

        req.roleId?.let { assignRole(ctx, current.id, it, operatorId, now) }
    }

    /** 删除用户：软删角色绑定 + 软删用户本身（循环单条）。 */
    public suspend fun delete(ctx: Context, ids: List<String>, operatorId: String? = null) {
        val now = Clock.System.now()
        for (id in ids) {
            bindings.executeSoftDeleteByUserId(ctx.db, id, now, operatorId).getOrThrow()
            users.executeSoftDeleteById(ctx.db, id, now, operatorId).getOrThrow()
        }
    }

    /**
     * 改密码：库里已有密码时必须先验旧密码；库里没密码（空）则直接设置，与旧版一致。
     *
     * `updatedBy` 记本人（旧版只更新 `updated_at`，审计列语义上应是操作者）。
     */
    public suspend fun changePassword(ctx: Context, id: String, origin: String, target: String) {
        val user = users.findOneById(ctx.db, id).getOrThrow() ?: throw NoSuchElementException("用户不存在")
        val encoded = user.password
        if (!encoded.isNullOrEmpty() && !BCrypt.checkpw(origin, encoded)) {
            throw IllegalStateException("密码错误！")
        }
        users.executeUpdateById(
            context = ctx.db,
            id = user.id,
            userName = user.userName,
            password = hash(target),
            realName = user.realName,
            phone = user.phone,
            email = user.email,
            avatar = user.avatar,
            isUse = user.isUse,
            updatedAt = Clock.System.now(),
            updatedBy = user.id,
        ).getOrThrow()
    }

    /** 改绑定：有绑定就改角色，没有就补一条（旧版 `update` 里的角色分支）。 */
    private suspend fun assignRole(ctx: Context, userId: String, roleId: Long, operatorId: String?, now: Instant) {
        val binding = bindings.findOneByUserId(ctx.db, userId).getOrThrow()
        when {
            binding == null ->
                bindings.executeInsert(ctx.db, userId, roleId, now, operatorId, now, operatorId).getOrThrow()

            binding.roleId != roleId ->
                bindings.executeUpdateRoleIdByUserId(ctx.db, userId, roleId, now, operatorId).getOrThrow()

            else -> Unit
        }
    }

    /** bcrypt 哈希（cost 与旧版 `BCryptPasswordEncoder(10)` 一致）。 */
    private fun hash(plain: String): String = BCrypt.hashpw(plain, BCrypt.gensalt())

    public companion object {
        /** `searching` 旧版固定返回 10 条。 */
        public const val SEARCH_LIMIT: Int = 10
    }
}
