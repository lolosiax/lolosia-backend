package top.lolosia.web.controller.system

import top.lolosia.web.model.system.SysRoleEntity
import top.lolosia.web.service.system.RoleService
import top.lolosia.web.util.session.Context
import top.lolosia.web.util.bundle.Bundle
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/home/api/role")
class RoleController {

    @Autowired
    lateinit var roleService: RoleService

    @PostMapping("/list")
    fun list(context: Context): MutableList<SysRoleEntity> {
        return roleService.list()
    }


    data class QueryRoleByPageParam(
        val pageNo: Int,
        val pageSize: Int,
        val roleName: String?,
    )

    /**
     * 角色分页查询
     */
    @PostMapping("/queryRoleByPage")
    fun queryRoleByPage(context: Context, @RequestBody param: QueryRoleByPageParam): Bundle {
        val (a, b, c) = param
        return roleService.queryRoleByPage(context, a, b, c)
    }

    @PostMapping("/create")
    fun create(context: Context, @RequestBody params: Bundle): SysRoleEntity {
        return roleService.create(context, params)
    }

    @PostMapping("/update")
    fun update(context: Context, @RequestBody params: Bundle): Any {
        return roleService.update(context, params)
    }

    data class DestroyParam(var id: Int)

    @PostMapping("/destroy")
    fun destroy(context: Context, @RequestBody params: DestroyParam): Any {
        return roleService.destroy(context, params.id)
    }
}