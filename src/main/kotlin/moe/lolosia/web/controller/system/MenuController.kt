package moe.lolosia.web.controller.system

import cn.ktorfitx.server.annotation.Controller
import cn.ktorfitx.server.annotation.POST
import kotlinx.serialization.json.JsonObject
import moe.lolosia.web.dto.response.ApiResult

/**
 * 菜单控制器。
 *
 * 模板暂未实现菜单/权限模型，`/menu/config` 返回空权限表：
 * 前端 [moe.lolosia.web] 的 filterTagRouters 据此过滤 tag 路由（全部不显示）。
 *
 * @author 洛洛希雅Lolosia
 * @since 2026-08-30 20:20
 */
@Controller("/api")
object MenuController {

    @POST("/menu/config")
    fun config(): ApiResult<JsonObject> = ApiResult.ok(JsonObject(emptyMap()))
}
