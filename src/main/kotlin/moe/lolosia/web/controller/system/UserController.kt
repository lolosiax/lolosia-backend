package moe.lolosia.web.controller.system

import cn.ktorfitx.server.annotation.Attribute
import cn.ktorfitx.server.annotation.Body
import cn.ktorfitx.server.annotation.Controller
import cn.ktorfitx.server.annotation.POST
import cn.ktorfitx.server.annotation.Path
import com.fasterxml.jackson.databind.json.JsonMapper
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respond
import io.ktor.server.routing.Routing
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import moe.lolosia.web.annotation.Route
import moe.lolosia.web.dto.response.ApiResult
import moe.lolosia.web.dto.response.toJsonObject
import moe.lolosia.web.service.system.UserService
import moe.lolosia.web.util.ErrorResponseException
import moe.lolosia.web.util.bundle.bundleScope
import moe.lolosia.web.util.bundle.toBundle
import moe.lolosia.web.util.collectFileItems
import moe.lolosia.web.util.respondFileCached
import moe.lolosia.web.util.session.Context
import moe.lolosia.web.util.session.ContextKey
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

/**
 * 用户管理控制器。
 *
 * @author 洛洛希雅Lolosia
 * @since 2024-04-25 10:01
 */
@Controller("/api")
object UserController : KoinComponent {

    private val userService: UserService by inject()
    private val mapper = JsonMapper()

    @Serializable
    data class ListFn(val pageNo: Int? = null, val pageSize: Int? = null, val keys: String? = null)

    @POST("/user/list")
    suspend fun list(@Attribute("ctx") ctx: Context, @Body param: ListFn): JsonObject {
        val (list, total) = userService.list(ctx, param.pageNo ?: 1, param.pageSize ?: 10, param.keys)
        return bundleScope {
            "list" set list
            "total" set total
        }.toJsonObject()
    }

    @Serializable
    data class SearchFn(val keys: String? = null)

    @POST("/user/searching")
    suspend fun search(@Attribute("ctx") ctx: Context, @Body param: SearchFn): JsonObject =
        bundleScope {
            "list" set userService.userSearching(ctx, param.keys).map { mapper.toBundle(it) }
        }.toJsonObject()

    @POST("/user/get/{id}")
    suspend fun get(@Attribute("ctx") ctx: Context, @Path("id") id: String): JsonObject =
        userService.getUserInfo(ctx, id).toJsonObject()

    @Serializable
    data class IdListFn(val ids: List<String?>)

    @POST("/user/getByIds")
    suspend fun getByIds(@Attribute("ctx") ctx: Context, @Body param: IdListFn): JsonObject =
        bundleScope {
            "list" set userService.get(ctx, param.ids)
        }.toJsonObject()

    @Serializable
    data class UserFn(
        val id: String? = null,
        val userName: String? = null,
        val password: String? = null,
        val phone: String? = null,
        val email: String? = null,
        val realName: String? = null,
        val isUse: Boolean? = null,
        val roleId: Int? = null,
    )

    @POST("/user/create")
    suspend fun create(@Attribute("ctx") ctx: Context, @Body param: UserFn): JsonObject {
        val data = bundleScope {
            param.id?.let { "id" set it }
            param.userName?.let { "userName" set it }
            param.password?.let { "password" set it }
            param.phone?.let { "phone" set it }
            param.email?.let { "email" set it }
            param.realName?.let { "realName" set it }
            param.isUse?.let { "isUse" set it }
            param.roleId?.let { "roleId" set it }
        }
        return bundleScope {
            "id" set userService.create(ctx, data).userId.toString()
        }.toJsonObject()
    }

    @POST("/user/update")
    suspend fun update(@Attribute("ctx") ctx: Context, @Body param: UserFn): JsonObject {
        val data = bundleScope {
            param.id?.let { "id" set it }
            param.userName?.let { "userName" set it }
            param.password?.let { "password" set it }
            param.phone?.let { "phone" set it }
            param.email?.let { "email" set it }
            param.realName?.let { "realName" set it }
            param.isUse?.let { "isUse" set it }
            param.roleId?.let { "roleId" set it }
        }
        return userService.update(ctx, data).toJsonObject()
    }

    @Serializable
    data class IdsFn(val ids: List<String>)

    @POST("/user/delete")
    suspend fun delete(@Attribute("ctx") ctx: Context, @Body param: IdsFn): JsonObject =
        userService.delete(ctx, param.ids).toJsonObject()

    @Serializable
    data class UpdatePasswordFn(val id: String, val origin: String, val target: String)

    @POST("/user/updatePassword")
    suspend fun updatePassword(@Attribute("ctx") ctx: Context, @Body param: UpdatePasswordFn): JsonObject =
        userService.updatePassword(ctx, param.id, param.origin, param.target).toJsonObject()

    @POST("/user/myInfo")
    suspend fun myInfo(@Attribute("ctx") ctx: Context): ApiResult<JsonObject> =
        ApiResult.ok(userService.myUserInfo(ctx).toJsonObject())

    @POST("/user/myRole")
    suspend fun myRole(@Attribute("ctx") ctx: Context): ApiResult<JsonObject> =
        ApiResult.ok(userService.myUserRole(ctx).toJsonObject())

    // ===== 手动路由（@Route 扫描注册） =====

    /** 头像上传（手动路由：流式读取 multipart，见 collectFileItems） */
    @Route
    fun avatarUpload(routing: Routing) {
        routing.post("/api/user/avatar") {
            val ctx = call.attributes[ContextKey]
            val id = call.request.queryParameters["id"]
            val file = collectFileItems().firstOrNull()
                ?: throw ErrorResponseException(HttpStatusCode.BadRequest, "缺少文件")
            val result = userService.saveAvatar(ctx, id ?: ctx.userId, file)
            // 手动路由经信封中间件统一包裹，Bundle 需先转 JsonObject
            call.respond(HttpStatusCode.OK, result.toJsonObject())
        }
    }

    /** 头像访问（Last-Modified/ETag → 304 协商，1 分钟缓存） */
    @Route
    fun avatar(routing: Routing) {
        routing.get("/api/user/avatar/get/{id}") {
            val ctx = call.attributes[ContextKey]
            val path = userService.avatarPathOf(ctx, call.parameters["id"]!!)
                ?: throw ErrorResponseException(HttpStatusCode.NotFound, "头像不存在")
            call.respondFileCached(path.toFile(), cacheControl = "max-age=60")
        }
    }
}
