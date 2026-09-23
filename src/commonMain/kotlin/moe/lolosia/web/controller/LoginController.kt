package moe.lolosia.web.controller

import moe.lolosia.web.dto.CaptchaResult
import moe.lolosia.web.dto.LoginReq
import moe.lolosia.web.dto.LoginResult
import moe.lolosia.web.dto.MessageResult
import moe.lolosia.web.dto.RegisterReq
import moe.lolosia.web.dto.UserListRow
import moe.lolosia.web.dto.VerifyReq
import moe.lolosia.web.service.LoginService
import moe.lolosia.web.util.router.Body
import moe.lolosia.web.util.router.Controller
import moe.lolosia.web.util.router.POST
import moe.lolosia.web.util.session.Context

/**
 * 登录 / 登出 / 图形验证码 / 注册（对应前端 `src/api/user.ts` 的 `login` / `logout` / `captcha` / `verify` / `register`）。
 *
 * 这五个都在鉴权白名单里（`AuthInterceptor`），`/api/logout` 不在 —— 登出本来就要带会话。
 *
 * 前端注册是两步：第一步 `captcha` + `verify` 过图形验证码，第二步填密码后 `register`
 * （邮箱验证码 `code` 前端会传，但本轮服务端忽略）。
 *
 * @author 洛洛希雅Lolosia
 */
@Controller("/api")
object LoginController {

    @POST("/login")
    suspend fun login(ctx: Context, login: LoginService, @Body param: LoginReq): LoginResult =
        login.login(ctx, param.userName, param.password)

    @POST("/logout")
    suspend fun logout(ctx: Context, login: LoginService): MessageResult =
        login.logout(ctx)

    @POST("/captcha")
    suspend fun captcha(ctx: Context, login: LoginService): CaptchaResult = login.captcha(ctx)

    @POST("/verify")
    suspend fun verify(ctx: Context, login: LoginService, @Body param: VerifyReq): MessageResult =
        login.verify(ctx, param.id, param.code)

    @POST("/register")
    suspend fun register(ctx: Context, login: LoginService, @Body param: RegisterReq): UserListRow =
        login.register(ctx, param.userName, param.password, param.email, param.phone)
}
