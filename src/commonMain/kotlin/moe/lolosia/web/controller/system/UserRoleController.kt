package moe.lolosia.web.controller.system

import moe.lolosia.web.dto.UserIdReq
import moe.lolosia.web.dto.UserRoleRow
import moe.lolosia.web.service.system.UserRoleService
import moe.lolosia.web.util.router.Body
import moe.lolosia.web.util.router.Controller
import moe.lolosia.web.util.router.POST
import moe.lolosia.web.util.router.Permission
import moe.lolosia.web.util.session.Context

/**
 * 用户-角色绑定接口。
 *
 * 前端在「用户管理 → 编辑」里调 `/userRole/getByUserId` 回填角色单选组
 * （旧 JVM 版没有这个路由，属前端与服务端的漂移；本版按前端补上）。
 *
 * @author 洛洛希雅Lolosia
 */
@Controller("/api")
object UserRoleController {

    /** 取指定用户的角色绑定；没有绑定时返回 `roleId = 0` 的兜底行。 */
    @POST("/userRole/getByUserId")
    @Permission("lolosia.user.read")
    suspend fun getByUserId(ctx: Context, bindings: UserRoleService, @Body req: UserIdReq): UserRoleRow =
        bindings.getByUserId(ctx, req.userId)
}
