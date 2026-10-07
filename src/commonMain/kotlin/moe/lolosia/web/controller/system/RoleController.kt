package moe.lolosia.web.controller.system

import moe.lolosia.web.dto.*
import moe.lolosia.web.service.system.RoleService
import moe.lolosia.web.util.router.Body
import moe.lolosia.web.util.router.Controller
import moe.lolosia.web.util.router.POST
import moe.lolosia.web.util.session.Context

/**
 * 角色接口（路径与响应形状以前端副本为准）。
 *
 * 前端分页读 `data.rows` / `data.total`（与用户列表的 `data` 键不同名，属前端历史遗留）。
 *
 * @author 洛洛希雅Lolosia
 */
@Controller("/api")
object RoleController {

    /** 全部角色（前端下拉 / 单选组用；`roleName` 与 `type` 都不含 admin 的会被前端筛掉）。 */
    @POST("/role/list")
    suspend fun list(ctx: Context, roles: RoleService): List<RoleRow> = roles.list(ctx)

    @POST("/role/queryRoleByPage")
    suspend fun page(ctx: Context, roles: RoleService, @Body req: RolePageReq): RolePageResult =
        roles.page(ctx, req.pageNo ?: 1, req.pageSize ?: 10, req.roleName)

    @POST("/role/create")
    suspend fun create(ctx: Context, roles: RoleService, @Body req: RoleCreateReq): RoleRow =
        roles.create(ctx, req, ctx.userId)

    @POST("/role/update")
    suspend fun update(ctx: Context, roles: RoleService, @Body req: RoleUpdateReq): EmptyResult {
        roles.update(ctx, req, ctx.userId)
        return EmptyResult()
    }

    /** 删除角色（`super_admin` / 使用中的角色会被拒），前端只取 `msg`。 */
    @POST("/role/destroy")
    suspend fun destroy(ctx: Context, roles: RoleService, @Body req: RoleDestroyReq): EmptyResult {
        roles.destroy(ctx, req.id, ctx.userId)
        return EmptyResult()
    }
}
