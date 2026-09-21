package moe.lolosia.web.controller.system

import cn.ktorfitx.server.annotation.Attribute
import cn.ktorfitx.server.annotation.Body
import cn.ktorfitx.server.annotation.Controller
import cn.ktorfitx.server.annotation.POST
import com.fasterxml.jackson.databind.json.JsonMapper
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import moe.lolosia.web.dto.response.ApiResult
import moe.lolosia.web.dto.response.toJsonObject
import moe.lolosia.web.service.system.RoleService
import moe.lolosia.web.util.bundle.bundleScope
import moe.lolosia.web.util.bundle.toBundle
import moe.lolosia.web.util.session.Context
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

/**
 * 角色控制器。
 *
 * @author 洛洛希雅Lolosia
 * @since 2024-04-25 10:01
 */
@Controller("/api")
object RoleController : KoinComponent {

    private val roleService: RoleService by inject()
    private val mapper = JsonMapper()

    @POST("/role/list")
    suspend fun list(@Attribute("ctx") ctx: Context): JsonArray =
        buildJsonArray {
            roleService.list(ctx).forEach { add(mapper.toBundle(it).toJsonObject()) }
        }

    @Serializable
    data class PageFn(val pageNo: Int? = null, val pageSize: Int? = null, val roleName: String? = null)

    @POST("/role/queryRoleByPage")
    suspend fun queryRoleByPage(@Attribute("ctx") ctx: Context, @Body param: PageFn): ApiResult<JsonObject> =
        ApiResult.ok(
            roleService.queryRoleByPage(ctx, param.pageNo ?: 1, param.pageSize ?: 10, param.roleName).toJsonObject()
        )

    @Serializable
    data class RoleFn(val id: Int? = null, val roleName: String? = null, val type: String? = null)

    @POST("/role/create")
    suspend fun create(@Attribute("ctx") ctx: Context, @Body param: RoleFn): ApiResult<JsonObject> =
        ApiResult.ok(
            roleService.create(ctx, bundleScope {
                param.roleName?.let { "roleName" set it }
                param.type?.let { "type" set it }
            }).let { mapper.toBundle(it).toJsonObject() }
        )

    @POST("/role/update")
    suspend fun update(@Attribute("ctx") ctx: Context, @Body param: RoleFn): ApiResult<JsonObject> =
        ApiResult.ok(
            roleService.update(ctx, bundleScope {
                param.id?.let { "id" set it }
                param.roleName?.let { "roleName" set it }
                param.type?.let { "type" set it }
            }).toJsonObject()
        )

    @Serializable
    data class DestroyFn(val id: Int)

    @POST("/role/destroy")
    suspend fun destroy(@Attribute("ctx") ctx: Context, @Body param: DestroyFn): ApiResult<JsonObject> =
        ApiResult.ok(roleService.destroy(ctx, param.id).toJsonObject())
}
