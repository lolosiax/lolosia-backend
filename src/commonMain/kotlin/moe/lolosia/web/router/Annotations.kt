package moe.lolosia.web.router

/**
 * 自研注解式路由：注解定义。
 *
 * 设计目标（对应迁移方案决策 9）：
 * 1. 与 ktorfitx server 端**同名同义**（`@Controller` / `@GET` / `@POST` / `@Body` / `@Path` / `@Query`），
 *    跨项目复制控制器代码时只需改 import；
 * 2. **去掉冗余的名称前缀**：可注入的上下文按**类型**自动注入（Spring 风格），不需要 `@Attribute("ctx")`；
 *    `@Path` / `@Query` / `@Header` / `@Form` 的名称可省略，默认取 Kotlin 形参名。
 *
 * @author 洛洛希雅Lolosia
 */

/** 标注控制器：只能用在 `object` 上，[prefix] 为路由前缀。 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.BINARY)
public annotation class Controller(val prefix: String = "")

/** 标注"可按类型注入"的类型：控制器形参声明为该类型（或其子类型）时无需任何注解，自动注入。 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.BINARY)
public annotation class Injectable

@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.BINARY)
public annotation class GET(val path: String = "")

@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.BINARY)
public annotation class POST(val path: String = "")

@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.BINARY)
public annotation class PUT(val path: String = "")

@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.BINARY)
public annotation class DELETE(val path: String = "")

@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.BINARY)
public annotation class PATCH(val path: String = "")

/** JSON 请求体：形参类型需为 `@Serializable`。 */
@Target(AnnotationTarget.VALUE_PARAMETER)
@Retention(AnnotationRetention.BINARY)
public annotation class Body

/** URL 路径参数（形如 `/user/get/{id}`）；[name] 省略时取形参名。 */
@Target(AnnotationTarget.VALUE_PARAMETER)
@Retention(AnnotationRetention.BINARY)
public annotation class Path(val name: String = "")

/** Query 参数；[name] 省略时取形参名。 */
@Target(AnnotationTarget.VALUE_PARAMETER)
@Retention(AnnotationRetention.BINARY)
public annotation class Query(val name: String = "")

/** 请求头；[name] 省略时取形参名。 */
@Target(AnnotationTarget.VALUE_PARAMETER)
@Retention(AnnotationRetention.BINARY)
public annotation class Header(val name: String = "")

/** 表单字段（urlencoded 或 multipart 的普通字段）；[name] 省略时取形参名。 */
@Target(AnnotationTarget.VALUE_PARAMETER)
@Retention(AnnotationRetention.BINARY)
public annotation class Form(val name: String = "")

/** multipart 文件项（形参类型为 `UploadedFile`）；[name] 省略时取形参名，为空表示"第一个文件"。 */
@Target(AnnotationTarget.VALUE_PARAMETER)
@Retention(AnnotationRetention.BINARY)
public annotation class FilePart(val name: String = "")
