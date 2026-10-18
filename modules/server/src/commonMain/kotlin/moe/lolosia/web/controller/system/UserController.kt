package moe.lolosia.web.controller.system

import io.ktor.http.*
import io.ktor.http.content.*
import moe.lolosia.web.dto.*
import moe.lolosia.web.service.permission.PermissionService
import moe.lolosia.web.service.system.UserRoleService
import moe.lolosia.web.service.system.UserService
import moe.lolosia.web.util.router.*
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
    @Permission("lolosia.user.read")
    suspend fun list(ctx: Context, users: UserService, @Body req: UserListReq): UserListResult =
        users.list(ctx, req.pageNo ?: 1, req.pageSize ?: 10, req.keys)

    /** 模糊搜索用户，最多 10 条（前端直接把它当数组用）。 */
    @POST("/user/searching")
    @Permission("lolosia.user.read")
    suspend fun searching(ctx: Context, users: UserService, @Body req: SearchReq): List<UserRow> =
        users.searching(ctx, req.keys)

    /** 按 id 批量取用户（`@Query` 不支持 `IN`，服务层循环单条）。 */
    @POST("/user/get")
    @Permission("lolosia.user.read")
    suspend fun get(ctx: Context, users: UserService, @Body req: IdListReq): List<UserRow> =
        users.get(ctx, req.idList.filterNotNull())

    @POST("/user/create")
    @Permission("lolosia.user.write")
    suspend fun create(ctx: Context, users: UserService, @Body req: UserCreateReq): IdRow =
        IdRow(users.create(ctx, req, ctx.userId).id)

    @POST("/user/edit")
    @Permission("lolosia.user.write")
    suspend fun edit(ctx: Context, users: UserService, @Body req: UserUpdateReq): EmptyResult {
        users.update(ctx, req, ctx.userId)
        return EmptyResult()
    }

    @POST("/user/delete")
    @Permission("lolosia.user.write")
    suspend fun delete(ctx: Context, users: UserService, @Body req: IdsReq): EmptyResult {
        if (req.ids.isNotEmpty()) users.delete(ctx, req.ids, ctx.userId)
        return EmptyResult()
    }

    /** 改密码；`id` 缺席时改登录人自己的（前端 `updatePassword` 允许不传 id）。 */
    @POST("/user/updatePassword")
    suspend fun updatePassword(ctx: Context, users: UserService, @Body req: UpdatePasswordReq): EmptyResult {
        users.changePassword(ctx, req.id ?: ctx.requireUserId(), req.origin, req.target)
        return EmptyResult()
    }

    /** 当前登录用户信息（前端路由守卫在 `Promise.all` 里取它）。 */
    @POST("/user/myInfo")
    suspend fun myInfo(ctx: Context, users: UserService): UserInfoRow =
        users.info(ctx, ctx.requireUserId())

    /**
     * 当前登录用户的角色（没有绑定时自动补一条「学生」，与旧版一致）。
     *
     * 额外回带 [UserRoleRow.rules]（生效的权限规则串）：前端守卫与 `v-permission` 直接用它，
     * 不必再单独调 `/api/permission/my`。
     */
    @POST("/user/myRole")
    suspend fun myRole(
        ctx: Context,
        bindings: UserRoleService,
        permissions: PermissionService,
    ): UserRoleRow = bindings.getRoleByUserId(ctx, ctx.requireUserId())
        .copy(rules = permissions.effectiveRules(ctx, ctx.requireUserId()))

    /**
     * 上传头像（前端 `setAvatar`：`PUT /user/avatar?id=<uuid>`，FormData 字段名 `file`）。
     *
     * 路径在鉴权白名单里（因为同名的 GET 要能被 `<img>` 直接访问、带不了请求头），
     * 所以**上传必须在这里自己校验登录态** —— 白名单只免掉了「没登录就 401」，不是免鉴权。
     * `id` 缺省表示改自己的头像。
     */
    @PUT("/user/avatar")
    suspend fun avatarUpload(
        ctx: Context,
        users: UserService,
        @FilePart("file") file: UploadedFile,
        @Query id: String?,
    ): EmptyResult {
        val operator = ctx.requireUserId()
        users.saveAvatar(ctx, id ?: operator, file, operator)
        return EmptyResult()
    }

    /** 读头像（前端 `getAvatar`：`GET /user/avatar?id=<uuid>`，`responseType: 'blob'`）。 */
    @GET("/user/avatar")
    suspend fun avatarGet(ctx: Context, users: UserService, @Query id: String?): OutgoingContent =
        avatarContent(users.avatarOf(ctx, id ?: ctx.requireUserId()))

    /** 取件式读头像（前端 store 的 `avatar` getter 拼的是 `/user/avatar/get/<id>`，给 `<img src>` 用）。 */
    @GET("/user/avatar/get/{id}")
    suspend fun avatarGetByPath(ctx: Context, users: UserService, @Path id: String): OutgoingContent =
        avatarContent(users.avatarOf(ctx, id))

    /** 按存的文件名判 MIME；`.jpeg` 归到 `image/jpeg`。 */
    private fun avatarContent(avatar: UserService.AvatarFile): OutgoingContent = ByteArrayContent(
        bytes = avatar.bytes,
        contentType = if (avatar.name.endsWith(".png", ignoreCase = true)) {
            ContentType.Image.PNG
        } else {
            ContentType.Image.JPEG
        },
    )
}
