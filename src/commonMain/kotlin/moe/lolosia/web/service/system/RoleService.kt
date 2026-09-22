package moe.lolosia.web.service.system

import io.github.smyrgeorge.sqlx4k.QueryExecutor
import kotlin.time.Clock
import moe.lolosia.web.db.Database
import moe.lolosia.web.db.SysRoleRepository
import moe.lolosia.web.db.SysUserRoleRepository
import moe.lolosia.web.dto.RoleCreateReq
import moe.lolosia.web.dto.RolePageResult
import moe.lolosia.web.dto.RoleRow
import moe.lolosia.web.dto.RoleUpdateReq
import moe.lolosia.web.dto.toRow
import moe.lolosia.web.util.router.Injectable

/**
 * 角色服务（迁移自旧 JVM 版的 `RoleService`，返回值由 `Bundle` 改为强类型 DTO）。
 *
 * 与旧版的差异：
 * - 时间字段输出 `yyyy-MM-dd HH:mm:ss`（固定 +08:00），同旧版 `@JsonFormat`；
 * - `create` 里自增 id 靠**插入后回查**（`executeInsert` 只回受影响行数，MySQL 方言拿不到
 *   `last_insert_id()`，见 §2-13），回查按 `type + roleName` 取最新一条；
 * - `update` 用读-改-写实现「`null` = 不修改」；
 * - `destroy` 走软删（`deleted = 1`），旧版的 `role.delete()` 在 `@SoftDelete` 下也是软删。
 *
 * `operatorId` 语义见 [UserService]。
 *
 * @author 洛洛希雅Lolosia
 */
@Injectable
public class RoleService(
    private val db: Database,
    private val roles: SysRoleRepository,
    private val bindings: SysUserRoleRepository,
) {

    private val ctx: QueryExecutor get() = db.driver

    /** 全部未删除角色，按 id 升序（前端下拉用）。 */
    public suspend fun list(): List<RoleRow> =
        roles.findAllByDeletedFalse(ctx).getOrThrow().map { it.toRow() }

    /** 分页查询（角色名 / 类型模糊匹配；菜单字段已弃用，只给空数组保持前端兼容）。 */
    public suspend fun page(pageNo: Int = 1, pageSize: Int = 10, roleName: String? = null): RolePageResult {
        val pattern = likePattern(roleName)
        val total = roles.countByKeys(ctx, pattern).getOrThrow()
        val rows = roles.findAllByKeys(ctx, pattern, pageSize, (pageNo - 1) * pageSize).getOrThrow()
        return RolePageResult(rows.map { it.toRow() }, total)
    }

    /** 新建角色（`roleName` / `type` 必填，与旧版报错信息一致），返回整行（含自增 id）。 */
    public suspend fun create(req: RoleCreateReq, operatorId: String? = null): RoleRow {
        val roleName = req.roleName ?: throw IllegalArgumentException("必须填写roleName")
        val type = req.type ?: throw IllegalArgumentException("必须填写type")

        val now = Clock.System.now()
        roles.executeInsert(ctx, roleName, type, now, operatorId, now, operatorId).getOrThrow()

        return roles.findAllByType(ctx, type).getOrThrow()
            .filter { it.roleName == roleName }
            .maxByOrNull { it.id }
            ?.toRow()
            ?: error("角色已写入但读不回来：type=$type, roleName=$roleName")
    }

    /** 修改角色：`null` 的字段保持原值。 */
    public suspend fun update(req: RoleUpdateReq, operatorId: String? = null) {
        val current = roles.findOneById(ctx, req.id).getOrThrow() ?: throw NoSuchElementException("角色不存在")
        roles.executeUpdateById(
            context = ctx,
            id = current.id,
            roleName = req.roleName ?: current.roleName,
            type = req.type ?: current.type,
            updatedAt = Clock.System.now(),
            updatedBy = operatorId ?: current.updatedBy,
        ).getOrThrow()
    }

    /** 删除角色：不存在直接返回（旧版同此）；超级管理员与使用中的角色不许删。 */
    public suspend fun destroy(id: Long, operatorId: String? = null) {
        val role = roles.findOneById(ctx, id).getOrThrow() ?: return
        if (role.type == SUPER_ADMIN) throw IllegalStateException("不能删除超级管理员角色")
        if (bindings.countByRoleId(ctx, id).getOrThrow() > 0) throw IllegalStateException("该角色正在使用")
        roles.executeSoftDeleteById(ctx, id, Clock.System.now(), operatorId).getOrThrow()
    }

    private companion object {
        /** 不可删除的角色类型。 */
        const val SUPER_ADMIN: String = "super_admin"
    }
}
