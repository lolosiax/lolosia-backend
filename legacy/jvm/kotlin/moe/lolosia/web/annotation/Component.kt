package moe.lolosia.web.annotation

/**
 * 组件标记：与 [Service] 等价，被构建插件（GenerateDiModuleTask）扫描后自动生成
 * Koin 注册（`single<T> { T() }`）。仅用于语义区分，扫描逻辑一致。
 *
 * 要求：被标注的类必须**无参构造**，依赖用字段注入（`by inject()`）。
 *
 * @author 洛洛希雅Lolosia
 * @since 2026-08-30 16:16
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.BINARY)
annotation class Component
