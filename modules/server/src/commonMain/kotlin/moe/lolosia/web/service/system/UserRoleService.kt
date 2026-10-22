package moe.lolosia.web.service.system

import moe.lolosia.web.database.repository.SysRoleRepository
import moe.lolosia.web.database.repository.SysUserRoleRepository
import moe.lolosia.web.database.repository.ViewUserRoleRepository
import moe.lolosia.web.dto.ROLE_ID_UNKNOWN
import moe.lolosia.web.dto.UserRoleRow
import moe.lolosia.web.dto.toUserRoleRow
import moe.lolosia.web.util.router.Injectable
import moe.lolosia.web.util.router.Service
import moe.lolosia.web.util.session.Context
import kotlin.time.Clock

/**
 * 用户-角色服务（迁移自旧 JVM 版的 `UserRoleService`）。
 *
 * [ctx] 是请求上下文，**首参透传**：语句执行句柄从它取（`ctx.db`），本层不持有连接池。
 *
 * 与旧版的差异：
 * - **`getByUserId` 按形参 `userId` 查**（旧版忽略形参、硬用登录人 `ctx.user.id`，是历史 bug，
 *   前端拿它查「指定用户」的角色时会返回错人）；
 * - 查不到绑定时的兜底：`roleId = 0`，`userId` 为被查的那个 id（旧版把 `userId` 写成数字 0）；
 * - 旧版那个没人调用的 `create`（直接插绑定）已经去掉 —— 插绑定在 [getRoleByUserId] 自动补角色时内部完成。
 *
 * @author 洛洛希雅Lolosia
 */
@Injectable
@Service
class UserRoleService(
    private val bindings: SysUserRoleRepository,
    private val roles: SysRoleRepository,
    private val view: ViewUserRoleRepository,
) {

    /** 取某用户的角色绑定视图；没有绑定时返回 `roleId = 0` 的兜底行。 */
    suspend fun getByUserId(ctx: Context, userId: String): UserRoleRow =
        view.findOneByUserId(ctx.db, userId).getOrThrow()?.toUserRoleRow()
            ?: UserRoleRow(userId = userId, roleId = ROLE_ID_UNKNOWN)

    /**
     * 取用户的组；**没有任何绑定时自动补一条「普通用户」组**（旧版行为，登录链路依赖它）。
     *
     * @throws NoSuchElementException 库里连 `user` 组都没有（数据没初始化）
     */
    suspend fun getRoleByUserId(ctx: Context, userId: String): UserRoleRow {
        if (bindings.countByUserId(ctx.db, userId).getOrThrow() == 0L) {
            val user = roles.findAllByType(ctx.db, USER).getOrThrow().firstOrNull()
                ?: throw NoSuchElementException("找不到用户角色")
            val now = Clock.System.now()
            bindings.executeInsert(ctx.db, userId, user.id, now, userId, now, userId).getOrThrow()
        }
        return getByUserId(ctx, userId)
    }

    private companion object {
        /** 自动补绑定时用的组类型。 */
        const val USER: String = "user"
    }
}
