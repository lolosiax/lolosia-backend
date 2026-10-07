package moe.lolosia.web.util.router

/**
 * 注解式路由的注解定义。
 *
 * 可注入的上下文按类型自动注入，不需要额外的名称前缀；
 * `@Path` / `@Query` / `@Header` / `@Form` / `@FilePart` 的名称可省略，默认取形参名。
 *
 * @author 洛洛希雅Lolosia
 */

/** 标注控制器：只能用在 `object` 上，[prefix] 为路由前缀。 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.BINARY)
annotation class Controller(val prefix: String = "")

/**
 * 标注"可按类型注入"的类型：控制器形参声明为该类型（或其子类型）时无需任何注解，自动注入。
 *
 * @param requestScoped 该类型由请求上下文逐次提供（如 `Context`），**不在 DI 容器里**；
 *   `InjectionProcessor` 据此跳过它的 `registerInjectable`，避免注册出一个容器里没有的工厂。
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.BINARY)
annotation class Injectable(val requestScoped: Boolean = false)

/**
 * 标注 Koin 单例服务：`InjectionProcessor` 据此生成 `single { 类型(get(), …) }` 绑定
 * （构造器注入，每个构造参数按类型从容器取），无需再手写 `serviceModule()`。
 *
 * 与 [Injectable] 各管一件事：本注解管**容器绑定**，[Injectable] 管**路由形参按类型注入**；
 * 既是服务又要被控制器形参注入时，两个注解都要标。构造参数带外部实例（如 `AppSettings` 用配置对象、
 * `FileStore` 用平台实现）的类型无法由类型推导，仍留手写（见 `appModule` / `platformModule`）。
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.BINARY)
annotation class Service

@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.BINARY)
annotation class GET(val path: String = "")

@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.BINARY)
annotation class POST(val path: String = "")

@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.BINARY)
annotation class PUT(val path: String = "")

@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.BINARY)
annotation class DELETE(val path: String = "")

@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.BINARY)
annotation class PATCH(val path: String = "")

/** JSON 请求体：形参类型需为 `@Serializable`。 */
@Target(AnnotationTarget.VALUE_PARAMETER)
@Retention(AnnotationRetention.BINARY)
annotation class Body

/** URL 路径参数（形如 `/user/get/{id}`）；[name] 省略时取形参名。 */
@Target(AnnotationTarget.VALUE_PARAMETER)
@Retention(AnnotationRetention.BINARY)
annotation class Path(val name: String = "")

/** Query 参数；[name] 省略时取形参名。 */
@Target(AnnotationTarget.VALUE_PARAMETER)
@Retention(AnnotationRetention.BINARY)
annotation class Query(val name: String = "")

/** 请求头；[name] 省略时取形参名。 */
@Target(AnnotationTarget.VALUE_PARAMETER)
@Retention(AnnotationRetention.BINARY)
annotation class Header(val name: String = "")

/** 表单字段（urlencoded 或 multipart 的普通字段）；[name] 省略时取形参名。 */
@Target(AnnotationTarget.VALUE_PARAMETER)
@Retention(AnnotationRetention.BINARY)
annotation class Form(val name: String = "")

/** multipart 文件项（形参类型为 `UploadedFile`）；[name] 省略时取形参名，为空表示"第一个文件"。 */
@Target(AnnotationTarget.VALUE_PARAMETER)
@Retention(AnnotationRetention.BINARY)
annotation class FilePart(val name: String = "")
