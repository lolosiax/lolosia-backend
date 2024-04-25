package top.lolosia.web.controller

import top.lolosia.web.service.LoginService
import top.lolosia.web.util.bundle.Bundle
import top.lolosia.web.util.session.Context
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

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
}