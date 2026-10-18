package moe.lolosia.web.service.permission

import moe.lolosia.web.database.repository.SysRolePermissionRepository
import moe.lolosia.web.database.repository.SysUserPermissionRepository
import moe.lolosia.web.dto.ApiException
import moe.lolosia.web.dto.PermissionMyResult
import moe.lolosia.web.util.router.Injectable
import moe.lolosia.web.util.router.Service
import moe.lolosia.web.util.session.Context
import kotlin.time.Clock

/**
 * 权限规则的**管理侧**读写（判定的解析在 [PermissionResolver]）。
 *
 * [ctx] 首参透传：语句执行句柄从 `ctx.db` 取，`save*` 的两步（软删 + 插入）跑在 `ctx.transaction { }`
 * 里，本层不持有连接池。
 *
 * 保存一律是**全量替换**（先软删该主体的全部规则再插入新列表），不做逐条 diff ——
 * 避免「删掉一个 `+` 却留下同名 `-`」这类残留，且语义与前端提交的完整列表一致。
 * 保存后该组/该用户就有了 DB 规则，默认权限注册表（`permissions.yaml`）不再对它兜底。
 *
 * @author 洛洛希雅Lolosia
 */
@Service
@Injectable
class PermissionService(
    private val resolver: PermissionResolver,
    private val rolePerms: SysRolePermissionRepository,
    private val userPerms: SysUserPermissionRepository,
) {

    /** 某组**自身**的规则原文（管理界面回填）；库里为空时给注册表默认值。 */
    suspend fun rulesOfRole(ctx: Context, roleId: Long): List<String> = resolver.rulesOfGroup(ctx.db, roleId)

    /** 某用户的直授规则原文。 */
    suspend fun rulesOfUser(ctx: Context, userId: String): List<String> =
        userPerms.findAllByUserId(ctx.db, userId).getOrThrow().map { it.permission }

    /**
     * 某用户**生效**的规则串（组链 + 直授合并、去重，规范化成 `+a.b.*` 形式）。
     *
     * 这里只做合并 —— 「负号优先」的判定发生在请求期（[Permissions.granted]）。
     * 拥有者组直接回 `+*`，与判定侧的兜底保持一致（见 [OWNER]）。
     */
    suspend fun effectiveRules(ctx: Context, userId: String): List<String> =
        if (resolver.isOwner(ctx.db, userId)) listOf(OWNER_RULE) else merged(ctx, userId)

    /** 当前用户视角的权限（`/api/permission/my`）。 */
    suspend fun my(ctx: Context): PermissionMyResult {
        val userId = ctx.requireUserId()
        val owner = resolver.isOwner(ctx.db, userId)
        return PermissionMyResult(
            rules = if (owner) listOf(OWNER_RULE) else merged(ctx, userId),
            owner = owner,
        )
    }

    /** 全量替换某组的规则；[operatorId] 由控制器显式传入（本层不从 `ctx.userId` 隐式取）。 */
    suspend fun saveRoleRules(ctx: Context, roleId: Long, rules: List<String>, operatorId: String?) {
        val normalized = normalize(rules)
        val now = Clock.System.now()
        ctx.transaction { tx ->
            rolePerms.executeSoftDeleteByRoleId(tx, roleId, now, operatorId).getOrThrow()
            normalized.forEach {
                rolePerms.executeInsert(tx, roleId, it, now, operatorId, now, operatorId).getOrThrow()
            }
        }
    }

    /** 全量替换某用户的直授规则。 */
    suspend fun saveUserRules(ctx: Context, userId: String, rules: List<String>, operatorId: String?) {
        val normalized = normalize(rules)
        val now = Clock.System.now()
        ctx.transaction { tx ->
            userPerms.executeSoftDeleteByUserId(tx, userId, now, operatorId).getOrThrow()
            normalized.forEach {
                userPerms.executeInsert(tx, userId, it, now, operatorId, now, operatorId).getOrThrow()
            }
        }
    }

    /** 组链 + 直授合并成规范化规则串（顺序稳定：直授在前、组在后）。 */
    private suspend fun merged(ctx: Context, userId: String): List<String> =
        resolver.rulesOfUser(ctx.db, userId).map { it.text }.distinct()

    /** 逐条校验并规范化（统一大小写与符号、去重）；非法串直接 400，不落库。 */
    private fun normalize(rules: List<String>): List<String> = rules
        .map { raw -> Permissions.canonical(raw) ?: throw ApiException(400, "权限串格式非法：$raw") }
        .distinct()
}
