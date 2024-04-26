package top.lolosia.web.controller.system

import top.lolosia.web.manager.SessionManager
import top.lolosia.web.model.system.SysUserEntity
import top.lolosia.web.service.system.UserService
import top.lolosia.web.util.ErrorResponseException
import top.lolosia.web.util.bundle.Bundle
import top.lolosia.web.util.bundle.bundleScope
import top.lolosia.web.util.bundle.invoke
import top.lolosia.web.util.bundle.scope
import top.lolosia.web.util.session.Context
import kotlinx.coroutines.reactor.awaitSingle
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.core.io.FileSystemResource
import org.springframework.http.HttpStatus
import org.springframework.http.codec.multipart.FilePart
import org.springframework.http.codec.multipart.FormFieldPart
import org.springframework.web.bind.annotation.*
import org.springframework.web.server.ServerWebExchange

@RestController
@RequestMapping("/home/api/user")
class UserController {

    @Autowired
    lateinit var userService: UserService

    @Autowired
    lateinit var sessionManager: SessionManager

    @PostMapping("/myInfo")
    fun myInfo(context: Context): Bundle {
        return userService.myUserInfo(context)
    }

    @PostMapping("/myRole")
    fun myRole(context: Context): Bundle {
        return userService.myUserRole(context)
    }

    data class ListParam(
        var pageNo: Int,
        var pageSize: Int,
        var keys: String?,
    )

    @PostMapping("/list")
    fun list(context: Context, @RequestBody param: ListParam): Bundle {
        val (a, b, c) = param
        val (rows, count) = userService.list(context, a, b, c)
        return bundleScope {
            "data" set rows
            "total" set count
        }
    }

    data class UserSearchingParam(var keys: String?)

    @PostMapping("/searching")
    fun userSearching(context: Context, params: UserSearchingParam): List<SysUserEntity> {
        return userService.userSearching(context, params.keys)
    }

    data class GetParam(var idList: List<String>)

    @PostMapping("/get")
    fun get(context: Context, @RequestBody param: GetParam): List<Bundle> {
        return userService.get(context, param.idList)
    }

    data class CreateParam(
        var phone: String,
        var userName: String?,
        var realName: String?,
        var password: String?,
        var isUse: Boolean,
        var roleId: Int
    )

    @PostMapping("/create")
    fun create(context: Context, @RequestBody param: CreateParam): Any {
        return userService.create(context, bundleScope {
            use(param::phone)
            use(param::userName) { param.phone }
            use(param::realName)
            use(param::isUse)
            use(param::roleId)
            use(param::password)
            "createdBy" set context.userId
        })
    }

    @PostMapping("/userInfo")
    fun getUserInfo(context: Context): Bundle {
        return userService.getUserInfo(context, context.userId).scope {
            // 我也不知道这句话什么意思。
            "permissions" set listOf(current["userName"])
        }
    }

    @PostMapping("/edit")
    fun edit(context: Context, @RequestBody data: Bundle): Any {
        if ("id" !in data) throw IllegalArgumentException("必须提供UserID")
        data["updatedBy"] = context.userId
        return userService.update(context, data)
    }

    data class DeleteParam(var ids: List<String>)

    @PostMapping("/delete")
    fun delete(context: Context, @RequestBody data: DeleteParam): Any {
        return userService.delete(context, data.ids)
    }

    data class UpdatePasswordParam(
        var id: String?,
        var origin: String?,
        var target: String,
        var session: String?,
    )

    /** 修改密码 */
    @PostMapping("/updatePassword")
    fun updatePassword(context: Context, @RequestBody params: UpdatePasswordParam): Any {
        if (params.session != null) {
            val session = params.session!!
            if (!sessionManager.contains(session)) {
                throw ErrorResponseException(HttpStatus.UNAUTHORIZED, "身份认证失败，请重新登陆")
            }
            params.id = sessionManager[session]("id")
        } else if (sessionManager.mySession(context) == null) {
            throw ErrorResponseException(HttpStatus.UNAUTHORIZED, "身份认证失败，请重新登陆")
        }

        return userService.updatePassword(context, params.id!!, params.origin ?: "", params.target)
    }

    @GetMapping("/avatar")
    fun getAvatar(context: Context, @RequestParam("id") id: String): FileSystemResource {
        return userService.avatar(context, id)
    }

    @PutMapping("/avatar")
    suspend fun setAvatar(context: Context, exchange: ServerWebExchange): Any {
        val form = exchange.multipartData.awaitSingle()
        val file = form["file"] as FilePart
        val id = (form["id"] as FormFieldPart?)?.value() ?: throw IllegalArgumentException("用户ID不存在")
        return userService.avatar(context, id, file)
    }
}