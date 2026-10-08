package moe.lolosia.web.service.permission

import io.github.smyrgeorge.sqlx4k.QueryExecutor
import moe.lolosia.web.database.model.SysRoleEntity
import moe.lolosia.web.database.repository.SysRolePermissionRepository
import moe.lolosia.web.database.repository.SysRoleRepository
import moe.lolosia.web.database.repository.SysUserPermissionRepository
import moe.lolosia.web.database.repository.ViewUserRoleRepository
import moe.lolosia.web.util.router.Injectable
import moe.lolosia.web.util.router.Service

/**
 * 生效权限的**解析**（判定侧），与 [PermissionService] 的管理侧读写分开。
 *
 * 规则来源有两处，优先级明确：
 * 1. `sys_role_permission` / `sys_user_permission` 里存的（管理界面改过的，**以它为准**）；
 * 2. 该组在库里一条规则都没有时，回退用 [PermissionRegistry]（内嵌 `permissions.yaml`）的默认值。
 *
 * **组继承**沿 `sys_role.parent_id` 递归展开：用户绑定的组 + 其全部祖先组的规则都参与判定。
 * 用「已访问组 id」集合收敛，父子成环也不会死循环。
 *
 * `exec` 由调用方从 `ctx.db`（或事务）传入，本层不持有连接池。
 *
 * @author 洛洛希雅Lolosia
 */
@Service
@Injectable
class PermissionResolver(
    private val roles: SysRoleRepository,
    private val bindings: ViewUserRoleRepository,
    private val rolePerms: SysRolePermissionRepository,
    private val userPerms: SysUserPermissionRepository,
    private val registry: PermissionRegistry,
) {

    /** 该用户所属的组链：绑定的组 + 沿父指针递归的祖先组（按首次出现的顺序，已去重）。 */
    suspend fun groupsOf(exec: QueryExecutor, userId: String): List<SysRoleEntity> {
        val all = roles.findAllByDeletedFalse(exec).getOrThrow().associateBy { it.id }
        val chain = mutableListOf<SysRoleEntity>()
        val seen = mutableSetOf<Long>()
        for (binding in bindings.findAllByUserId(exec, userId).getOrThrow()) {
            var current = binding.roleId?.let(all::get)
            while (current != null && seen.add(current.id)) {
                chain += current
                current = current.parentId?.let(all::get)
            }
        }
        return chain
    }

    /** 是否属于拥有者组：判定时视为拥有 `*`，且不可被 `-` 规则剥夺（见 [OWNER]）。 */
    suspend fun isOwner(exec: QueryExecutor, userId: String): Boolean =
        groupsOf(exec, userId).any { it.type?.lowercase() == OWNER }

    /** 该用户生效的规则：组链上的规则（DB 优先，空则注册表默认） + 用户直授。 */
    suspend fun rulesOfUser(exec: QueryExecutor, userId: String): List<PermissionRule> {
        val byRole = rolePerms.findAllByDeletedFalse(exec).getOrThrow().groupBy { it.roleId }
        val group = groupsOf(exec, userId).flatMap { role ->
            storedRules(byRole[role.id].orEmpty().map { it.permission }, role.type)
        }
        val direct = userPerms.findAllByUserId(exec, userId).getOrThrow().map { it.permission }
        return Permissions.merge(group, direct)
    }

    /**
     * 某组**自身**的规则原文（管理界面回填）：DB 为空时给注册表默认值。
     *
     * 不含祖先组的规则 —— 继承关系由 `parent_id` 表达，界面按组分别展示。`null` 的 `roleId`
     * （没有任何绑定的兜底行）视为无规则。
     */
    suspend fun rulesOfGroup(exec: QueryExecutor, roleId: Long?): List<String> {
        if (roleId == null) return emptyList()
        val stored = rolePerms.findAllByRoleId(exec, roleId).getOrThrow().map { it.permission }
        if (stored.isNotEmpty()) return stored
        return registry.permissionsOf(roles.findOneById(exec, roleId).getOrThrow()?.type)
    }

    /** DB 里有规则就用 DB 的，没有才回退注册表默认值。 */
    private fun storedRules(stored: List<String>, type: String?): List<String> =
        stored.ifEmpty { registry.permissionsOf(type) }
}
