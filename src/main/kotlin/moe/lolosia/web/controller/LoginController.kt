package moe.lolosia.web.controller

import cn.ktorfitx.server.annotation.Attribute
import cn.ktorfitx.server.annotation.Body
import cn.ktorfitx.server.annotation.Controller
import cn.ktorfitx.server.annotation.POST
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import moe.lolosia.web.dto.response.ApiResult
import moe.lolosia.web.dto.response.toJsonObject
import moe.lolosia.web.service.LoginService
import moe.lolosia.web.util.session.Context
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

/**
 * 登录注册控制器。
 * 响应统一走 {code, msg, data} 信封（前端契约，见 ApiResult）。
 *
 * @author 洛洛希雅Lolosia
 * @since 2024-04-25 10:01
 */
@Controller("/api")
object LoginController : KoinComponent {

    private val loginService: LoginService by inject()

    @Serializable
    data class LoginFn(val userName: String, val password: String?)

    @POST("/login")
    suspend fun login(@Attribute("ctx") ctx: Context, @Body param: LoginFn): ApiResult<JsonObject> =
        ApiResult.ok(loginService.login(ctx, param.userName, param.password).toJsonObject())

    @POST("/logout")
    suspend fun logout(@Attribute("ctx") ctx: Context): ApiResult<JsonObject> =
        ApiResult.ok(loginService.logout(ctx).toJsonObject())

    @POST("/captcha")
    suspend fun captcha(@Attribute("ctx") ctx: Context): ApiResult<JsonObject> =
        ApiResult.ok(loginService.captcha(ctx).toJsonObject())

    @Serializable
    data class RegisterFn(
        val userName: String,
        val password: String,
        val email: String? = null,
        val phone: String? = null,
    )

    @POST("/register")
    suspend fun register(@Body param: RegisterFn, @Attribute("ctx") ctx: Context): ApiResult<JsonObject> =
        ApiResult.ok(loginService.register(ctx, param.userName, param.password, param.email, param.phone).toJsonObject())
}
