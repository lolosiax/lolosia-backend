package top.lolosia.web.controller

import top.lolosia.web.service.LoginService
import top.lolosia.web.util.bundle.Bundle
import top.lolosia.web.util.session.Context
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import top.lolosia.web.model.system.ViewUserRoleEntity

@RestController
@RequestMapping("/api")
class LoginController {

    @Autowired
    lateinit var loginService: LoginService

    class LoginFn(var userName: String, var password: String?)

    @PostMapping("/login")
    suspend fun login(context: Context, @RequestBody param: LoginFn): Bundle {
        return loginService.login(context, param.userName, param.password)
    }

    @PostMapping("/logout")
    suspend fun logout(context: Context): Any {
        return loginService.logout(context)
    }

    @PostMapping("/captcha")
    suspend fun captcha(context: Context): Bundle {
        return loginService.captcha(context)
    }

    data class VerifyFn(var id: Int, var code: String, var userName: String, var email: String)

    @PostMapping("/verify")
    suspend fun verify(context: Context, @RequestBody param: VerifyFn) : ResponseEntity<ByteArray> {
        return loginService.verify(context, param.id, param.code, param.userName, param.email)
    }

    data class RegisterFn(var userName: String, var password: String, var email: String, var code: String)
    @PostMapping("/register")
    suspend fun register(context: Context, @RequestBody param: RegisterFn) : ViewUserRoleEntity {
        return loginService.register(context, param.userName, param.password, param.email, param.code)
    }
}