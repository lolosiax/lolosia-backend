package moe.lolosia.web.controller.system

import cn.ktorfitx.server.annotation.Attribute
import cn.ktorfitx.server.annotation.Controller
import cn.ktorfitx.server.annotation.GET
import com.fasterxml.jackson.databind.json.JsonMapper
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonArray
import moe.lolosia.web.dto.response.toJsonObject
import moe.lolosia.web.service.system.RoleService
import moe.lolosia.web.util.bundle.toBundle
import moe.lolosia.web.util.session.Context
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

/**
 * 角色控制器（基础版）。
 *
 * @author 洛洛希雅Lolosia
 * @since 2024-04-25 10:01
 */
@Controller("/api")
object RoleController : KoinComponent {

    private val roleService: RoleService by inject()
    private val mapper = JsonMapper()

    @GET("/role/list")
    suspend fun list(@Attribute("ctx") ctx: Context): JsonArray =
        buildJsonArray {
            roleService.list(ctx).forEach { add(mapper.toBundle(it).toJsonObject()) }
        }
}
