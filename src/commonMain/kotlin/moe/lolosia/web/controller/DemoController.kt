package moe.lolosia.web.controller

import kotlinx.serialization.Serializable
import moe.lolosia.web.config.AppConfig
import moe.lolosia.web.db.Database
import moe.lolosia.web.util.router.Body
import moe.lolosia.web.util.router.Controller
import moe.lolosia.web.util.router.FilePart
import moe.lolosia.web.util.router.Form
import moe.lolosia.web.util.router.GET
import moe.lolosia.web.util.router.POST
import moe.lolosia.web.util.router.Path
import moe.lolosia.web.util.router.Query
import moe.lolosia.web.util.router.UploadedFile
import moe.lolosia.web.util.session.Context

/**
 * 自研路由插件的验证用控制器：覆盖 @Controller / @GET / @POST /
 * 按类型自动注入 / @Path / @Query / @Body / @Form / @FilePart。
 *
 * 注意：写法与旧 ktorfitx 控制器一致，仅 import 包名不同。
 *
 * @author 洛洛希雅Lolosia
 */
@Serializable
data class EchoReq(val name: String, val times: Int = 1)

@Serializable
data class EchoResp(val items: List<String>)

@Serializable
data class RouteInfo(val path: String, val id: String, val verbose: Boolean?)

@Serializable
data class UploadInfo(val field: String?, val fileName: String?, val size: Int, val note: String?)

@Controller("/api")
object DemoController {

    /** 最简单：无参数、返回 String */
    @GET("/ping")
    fun ping(): String = "pong"

    /**
     * 按类型自动注入：`ctx: Context` 没有任何注解，
     * KSP 检测到 Context 标注了 @Injectable 后生成 `call.inject<Context>()`。
     */
    @GET("/whoami")
    fun whoami(ctx: Context): String = "uri=${ctx.call.request.local.uri}"

    /** @Path / @Query 的名称可省略，默认取形参名（`id` → `{id}`，`verbose` → `?verbose=`） */
    @GET("/user/get/{id}")
    fun getUser(ctx: Context, @Path id: String, @Query verbose: Boolean?): RouteInfo =
        RouteInfo(
            path = ctx.call.request.local.uri,
            id = id,
            verbose = verbose,
        )

    /** JSON 请求体 */
    @POST("/echo")
    fun echo(@Body req: EchoReq): EchoResp =
        EchoResp(List(req.times.coerceIn(0, 10)) { "${req.name}#$it" })

    /** 表单字段 + 文件（multipart） */
    @POST("/upload")
    fun upload(@Form("note") note: String?, @FilePart("file") file: UploadedFile): UploadInfo =
        UploadInfo(
            field = file.fieldName,
            fileName = file.fileName,
            size = file.bytes.size,
            note = note,
        )

    /** 路径参数用显式名称（与形参名不同） */
    @GET("/explicit/{uid}")
    fun explicit(@Path("uid") userId: String): String = "userId=$userId"

    /**
     * 生效配置（口令不外泄）：验证「配置文件/环境变量生效」+「Koin → 路由按类型注入」整条链路。
     * 服务实例从 0.6 起按同样方式注入。
     */
    @GET("/config")
    fun config(config: AppConfig): String = buildString {
        append("port=").append(config.server.port)
        append(", host=").append(config.server.host)
        append(", logLevel=").append(config.logLevel)
        append(", db=").append(config.datasource.url)
        append(", dbUser=").append(config.datasource.username)
    }

    /** 数据访问探针（P0-0.6 第一步）：验证 sqlx4k 的 FFI 驱动在 native 上确实能连库执行查询。 */
    @GET("/db/ping")
    suspend fun dbPing(db: Database): String = "db=${db.ping()}"
}
