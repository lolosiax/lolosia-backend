package moe.lolosia.web.controller.system

import moe.lolosia.web.util.router.Controller
import moe.lolosia.web.util.router.POST

/**
 * 菜单接口。
 *
 * 模板没有菜单模型（`sql/` 里没有菜单表，角色的 `menuId` / `menuTitle` 一直是空数组），
 * 所以这里与旧 JVM 版一样只提供 `/menu/config`。
 *
 * ⚠️ 与旧版的**有意差异**：旧版返回空表 `{}`，而前端 `use-permission.ts` 的
 * `filterTagRouters(routes, exp)` 会按 `exp[tag || 'default']` 过滤掉路由 —— 空表会让
 * 「用户中心」等内置路由整块消失。这里回 `{"default": true}` 放行内置路由
 * （前端 `roleRoutes()` 里唯一的 `meta.tag` 就是缺省值）。真要做菜单权限需要先建菜单表。
 *
 * @author 洛洛希雅Lolosia
 */
@Controller("/api")
object MenuController {

    @POST("/menu/config")
    suspend fun config(): Map<String, Boolean> = mapOf(DEFAULT_TAG to true)

    private const val DEFAULT_TAG: String = "default"
}
