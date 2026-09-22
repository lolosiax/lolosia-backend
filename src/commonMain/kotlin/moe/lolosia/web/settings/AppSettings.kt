package moe.lolosia.web.settings

import kotlinx.serialization.Serializable
import moe.lolosia.web.util.router.Injectable

/**
 * 应用配置。
 *
 * 标注 [Injectable] 后，控制器形参声明 `AppSettings` 即可自动注入。
 * 多级覆盖见 [loadAppSettings]。
 *
 * @author 洛洛希雅Lolosia
 */
@Serializable
@Injectable
public data class AppSettings(
    val server: ServerConfig = ServerConfig(),
    val datasource: DatasourceConfig = DatasourceConfig(),
    /** 日志级别：TRACE / DEBUG / INFO / WARN / ERROR / OFF */
    val logLevel: String = "INFO",
) {
    @Serializable
    public data class ServerConfig(
        val host: String = "0.0.0.0",
        val port: Int = 7129,
    )

    @Serializable
    public data class DatasourceConfig(
        val url: String = "mysql://127.0.0.1:3306/ktor-template",
        val username: String = "root",
        val password: String = "123456",
        val maxConnections: Int = 10,
    )
}
