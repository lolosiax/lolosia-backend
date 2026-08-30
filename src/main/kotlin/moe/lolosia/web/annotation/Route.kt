package moe.lolosia.web.annotation

/**
 * 手动路由标记：被构建插件（GenerateRouteModuleTask）扫描后自动生成
 * `Routing.() -> Unit` 批量注册函数（默认名 `manualRoutes`），在 `routing { }` 内调用一次即可。
 *
 * 要求：标注在任意 `object` 的成员函数上，函数类型为 `Function<Routing, Unit>`
 * （形如 `fun xxx(routing: Routing)` 或 `fun Routing.xxx()`），函数体内用
 * `routing.get(...) / routing.post(...) / routing.staticResources(...)` 注册路由。
 * 生成代码会以 `ObjectName.xxx(this)` 的方式调用该函数。
 *
 *
 * @author 洛洛希雅Lolosia
 * @since 2026-08-30 18:00
 */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.BINARY)
annotation class Route
