package moe.lolosia.web.controller

import moe.lolosia.web.dto.LoginReq
import moe.lolosia.web.dto.LoginResult
import moe.lolosia.web.service.LoginService
import moe.lolosia.web.util.router.Body
import moe.lolosia.web.util.router.Controller
import moe.lolosia.web.util.router.POST
import moe.lolosia.web.util.session.Context

/**
 * 登录 / 登出（对应前端 `src/api/user.ts` 的 `login` / `logout`）。
 *
 * `/api/login` 在鉴权白名单里（`AuthInterceptor`），`/api/logout` 不在 —— 登出本来就要带会话。
 *
 * @author 洛洛希雅Lolosia
 */
@Controller("/api")
object LoginController {

    @POST("/login")
    suspend fun login(login: LoginService, @Body param: LoginReq): LoginResult =
        login.login(param.userName, param.password)

    @POST("/logout")
    suspend fun logout(ctx: Context, login: LoginService): String =
        login.logout(ctx.sessionId)
}
