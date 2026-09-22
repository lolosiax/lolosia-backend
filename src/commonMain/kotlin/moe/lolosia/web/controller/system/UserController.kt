package moe.lolosia.web.controller.system

import moe.lolosia.web.dto.EmptyResult
import moe.lolosia.web.dto.IdListReq
import moe.lolosia.web.dto.IdRow
import moe.lolosia.web.dto.IdsReq
import moe.lolosia.web.dto.SearchReq
import moe.lolosia.web.dto.UpdatePasswordReq
import moe.lolosia.web.dto.UserCreateReq
import moe.lolosia.web.dto.UserInfoRow
import moe.lolosia.web.dto.UserListReq
import moe.lolosia.web.dto.UserListResult
import moe.lolosia.web.dto.UserRoleRow
import moe.lolosia.web.dto.UserRow
import moe.lolosia.web.dto.UserUpdateReq
import moe.lolosia.web.service.system.UserRoleService
import moe.lolosia.web.service.system.UserService
import moe.lolosia.web.util.router.Body
import moe.lolosia.web.util.router.Controller
import moe.lolosia.web.util.router.POST
import moe.lolosia.web.util.session.Context

/**
 * 用户接口（路径与请求/响应形状**以前端副本 `F:\Develop\Node\lolosia-web` 为准**）。
 *
 * 与旧 JVM 版 `UserController` 的差异：
 * - 前端调的是 `/user/edit`（旧后端只有 `/user/update`），这里按前端实现；
 * - 没有 `/user/getByIds`（前端不调）；前端调的是 `/user/get` + `{idList}`；
 * - `/user/delete` 的 `ids` 前端传**逗号分隔字符串**（旧后端按数组收），这里按前端收；
 * - 旧后端把 `password` 回传给前端，这里不回传（见 [moe.lolosia.web.dto.UserRow]）。
 *
 * `operatorId` 取登录态（`ctx.userId`）写进审计列。
 *
 * @author 洛洛希雅Lolosia
 */
@Controller("/api")
object UserController {

    /** 分页列表（前端读 `data.data` / `data.total`）。 */
    @POST("/user/list")
    suspend fun list(users: UserService, @Body req: UserListReq): UserListResult =
        users.list(req.pageNo ?: 1, req.pageSize ?: 10, req.keys)

    /** 模糊搜索用户，最多 10 条（前端直接把它当数组用）。 */
    @POST("/user/searching")
    suspend fun searching(users: UserService, @Body req: SearchReq): List<UserRow> =
        users.searching(req.keys)

    /** 按 id 批量取用户（`@Query` 不支持 `IN`，服务层循环单条）。 */
    @POST("/user/get")
    suspend fun get(users: UserService, @Body req: IdListReq): List<UserRow> =
        users.get(req.idList.filterNotNull())

    @POST("/user/create")
    suspend fun create(ctx: Context, users: UserService, @Body req: UserCreateReq): IdRow =
        IdRow(users.create(req, ctx.userId).id)

    @POST("/user/edit")
    suspend fun edit(ctx: Context, users: UserService, @Body req: UserUpdateReq): EmptyResult {
        users.update(req, ctx.userId)
        return EmptyResult()
    }

    @POST("/user/delete")
    suspend fun delete(ctx: Context, users: UserService, @Body req: IdsReq): EmptyResult {
        val ids = req.ids.split(',').map(String::trim).filter(String::isNotEmpty)
        if (ids.isNotEmpty()) users.delete(ids, ctx.userId)
        return EmptyResult()
    }

    /** 改密码；`id` 缺席时改登录人自己的（前端 `updatePassword` 允许不传 id）。 */
    @POST("/user/updatePassword")
    suspend fun updatePassword(ctx: Context, users: UserService, @Body req: UpdatePasswordReq): EmptyResult {
        users.changePassword(req.id ?: ctx.requireUserId(), req.origin, req.target)
        return EmptyResult()
    }

    /** 当前登录用户信息（前端路由守卫在 `Promise.all` 里取它）。 */
    @POST("/user/myInfo")
    suspend fun myInfo(ctx: Context, users: UserService): UserInfoRow =
        users.info(ctx.requireUserId())

    /** 当前登录用户的角色（没有绑定时自动补一条「学生」，与旧版一致）。 */
    @POST("/user/myRole")
    suspend fun myRole(ctx: Context, bindings: UserRoleService): UserRoleRow =
        bindings.getRoleByUserId(ctx.requireUserId())
}
