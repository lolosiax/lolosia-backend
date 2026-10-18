package moe.lolosia.web.controller.system

import moe.lolosia.web.dto.*
import moe.lolosia.web.generated.generatedPermissions
import moe.lolosia.web.service.permission.PermissionService
import moe.lolosia.web.util.router.*
import moe.lolosia.web.util.session.Context

/**
 * 权限管理接口：组的规则、用户的直授规则、当前用户生效权限、权限节点目录。
 *
 * 路径把「组规则」与「用户直授」都收在这里（分别落在 `/role/permission` 与 `/user/permission` 前缀下），
 * 是为了让权限这一件事只有一个入口；`role` / `user` 的业务接口仍在各自的控制器。
 *
 * `save` 是**全量替换**：提交的列表即该主体最终的规则集合。
 *
 * @author 洛洛希雅Lolosia
 */
@Controller("/api")
object PermissionController {

    /** 读某组挂的规则。 */
    @POST("/role/permission/list")
    @Permission("lolosia.role.permission")
    suspend fun roleList(
        ctx: Context,
        permissions: PermissionService,
        @Body req: RolePermissionReq,
    ): PermissionRulesResult = PermissionRulesResult(permissions.rulesOfRole(ctx, req.roleId))

    /** 全量替换某组挂的规则。 */
    @POST("/role/permission/save")
    @Permission("lolosia.role.permission")
    suspend fun roleSave(
        ctx: Context,
        permissions: PermissionService,
        @Body req: RolePermissionReq,
    ): EmptyResult {
        permissions.saveRoleRules(ctx, req.roleId, req.rules, ctx.userId)
        return EmptyResult()
    }

    /** 读某用户的直授规则。 */
    @POST("/user/permission/list")
    @Permission("lolosia.user.permission")
    suspend fun userList(
        ctx: Context,
        permissions: PermissionService,
        @Body req: UserPermissionReq,
    ): PermissionRulesResult = PermissionRulesResult(permissions.rulesOfUser(ctx, req.userId))

    /** 全量替换某用户的直授规则。 */
    @POST("/user/permission/save")
    @Permission("lolosia.user.permission")
    suspend fun userSave(
        ctx: Context,
        permissions: PermissionService,
        @Body req: UserPermissionReq,
    ): EmptyResult {
        permissions.saveUserRules(ctx, req.userId, req.rules, ctx.userId)
        return EmptyResult()
    }

    /** 当前用户生效的权限（前端守卫 / 元素级指令的数据源）。 */
    @POST("/permission/my")
    suspend fun my(ctx: Context, permissions: PermissionService): PermissionMyResult = permissions.my(ctx)

    /** 全部权限节点（编译期从 `@Permission` 扫描生成）。 */
    @POST("/permission/catalog")
    @Permission("lolosia.permission.read")
    suspend fun catalog(): List<PermissionCatalogEntry> = generatedPermissions()
}
