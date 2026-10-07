package moe.lolosia.web.util.session

import moe.lolosia.web.database.model.SysUserEntity
import moe.lolosia.web.database.model.ViewUserRoleEntity
import moe.lolosia.web.database.repository.SysUserRepository
import moe.lolosia.web.database.repository.ViewUserRoleRepository

/**
 * 当前登录用户（请求级，挂在 [Context.user] 上）。
 *
 * **一次请求只查一次、结果缓存在本实例里** —— 与旧 JVM 版 `UserInfo` 的 `by lazy` 同义，
 * 只是这里因为仓库是 `suspend` 的，改成「首次访问时加载并记住」。角色取第一条绑定（旧版 `findOne`）。
 *
 * 查不到返回 `null`（旧版抛 `NoSuchElementException`，会被兜底成 500）。
 *
 * @author 洛洛希雅Lolosia
 */
class UserInfo internal constructor(
    private val ctx: Context,
    private val users: SysUserRepository,
    private val bindings: ViewUserRoleRepository,
) {
    /** 当前登录用户 id；未登录按 401 处理。 */
    val id: String get() = ctx.requireUserId()

    private var entityLoaded: Boolean = false
    private var cachedEntity: SysUserEntity? = null

    private var roleLoaded: Boolean = false
    private var cachedRole: ViewUserRoleEntity? = null

    /** 当前用户实体；一次请求内只查一次。 */
    suspend fun entity(): SysUserEntity? {
        if (!entityLoaded) {
            cachedEntity = users.findOneById(ctx.db, id).getOrThrow()
            entityLoaded = true
        }
        return cachedEntity
    }

    /** 当前用户的角色绑定（多角色取第一条）；一次请求内只查一次。 */
    suspend fun role(): ViewUserRoleEntity? {
        if (!roleLoaded) {
            cachedRole = bindings.findOneByUserId(ctx.db, id).getOrThrow()
            roleLoaded = true
        }
        return cachedRole
    }

    /** 角色类型（`sys_role.type`）。 */
    suspend fun roleType(): String? = role()?.type

    /** 是否管理员（旧版判定是 `"admin" in roleType`，即包含）。 */
    suspend fun isAdmin(): Boolean = roleType()?.contains("admin") == true

    /** 是否教师。 */
    suspend fun isTeacher(): Boolean = roleType() == "teacher"

    /** 是否学生。 */
    suspend fun isStudent(): Boolean = roleType() == "student"
}
