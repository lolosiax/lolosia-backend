package top.lolosia.web.controller.system

import top.lolosia.web.service.system.UserRoleService
import top.lolosia.web.util.bundle.Bundle
import top.lolosia.web.util.bundle.bundleScope
import top.lolosia.web.util.session.Context
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/home/api/userRole")
class UserRoleController {

    @Autowired
    lateinit var service: UserRoleService

    data class GetByUserIdParam(val userId: String)

    @PostMapping("/getByUserId")
    fun getByUserId(context: Context, @RequestBody param: GetByUserIdParam): Bundle {
        return service.getByUserId(context, param.userId)
    }

    @PostMapping("/getUserRole")
    fun getUserRole(context: Context, @RequestBody param: GetByUserIdParam): Bundle {
        return service.getRoleByUserId(context, mutableMapOf("userId" to param.userId))
    }
}