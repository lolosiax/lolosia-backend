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
data class AppSettings(
    val server: ServerConfig = ServerConfig(),
    val datasource: DatasourceConfig = DatasourceConfig(),
    val ddns: DdnsConfig = DdnsConfig(),
    val webdav: WebdavConfig = WebdavConfig(),
    /** 日志级别：TRACE / DEBUG / INFO / WARN / ERROR / OFF */
    val logLevel: String = "INFO",
) {
    @Serializable
    data class ServerConfig(
        val host: String = "0.0.0.0",
        val port: Int = 7129,
    )

    @Serializable
    data class DatasourceConfig(
        val url: String = "mysql://127.0.0.1:3306/ktor-template",
        val username: String = "root",
        val password: String = "123456",
        val maxConnections: Int = 10,
    )

    /**
     * Cloudflare DDNS：把 [interfaceName] 上的全局 IPv6 同步到 [recordName] 的 AAAA 记录。
     *
     * 板子（eth0 接光猫）拿到 IPv6 依赖光猫周期性 RA，地址前缀会随上游变化，
     * 故按 [intervalSeconds] 周期检查、变化即更新。
     */
    @Serializable
    data class DdnsConfig(
        /** 是否启用自动更新。 */
        val enabled: Boolean = false,
        /** Cloudflare 区域 ID（zone_id）。 */
        val zoneId: String = "",
        /** Cloudflare API Token，需 `Zone → DNS → Edit` 权限。 */
        val apiToken: String = "",
        /** 要维护的完整记录名，如 `ddns-1m.lolosia.moe`。 */
        val recordName: String = "",
        /** 记录 TTL（秒），1 表示自动。 */
        val ttl: Int = 120,
        /** 是否走 Cloudflare 代理（橙云）。要直连设备必须为 false。 */
        val proxied: Boolean = false,
        /** 取哪个网卡的全局 IPv6。 */
        val interfaceName: String = "eth0",
        /** 检查间隔（秒），下限 30。 */
        val intervalSeconds: Int = 300,
    )

    /**
     * WebDAV：把 [root] 目录（如挂载的机械盘 `/mnt/hdd1`）以 WebDAV 协议挂在 [path] 下，
     * 供 Windows「映射网络驱动器」之类的客户端直接读写，无需先下载到本地临时目录。
     *
     * 用 HTTP Basic 鉴权（Windows 默认只允许在 HTTPS 上发 Basic，故对外必须经 Caddy 的 TLS 端口）。
     * 挂载路径不走 `/api`，因此不受会话鉴权与响应信封影响（见 `configureApplication`）。
     */
    @Serializable
    data class WebdavConfig(
        /** 是否启用。 */
        val enabled: Boolean = false,
        /** 对外挂载的路径前缀，需与客户端使用的 URL 一致。 */
        val path: String = "/dav",
        /** 本地根目录（绝对路径）。所有请求都被限制在该目录内。 */
        val root: String = "/mnt/hdd1",
        /** Basic 鉴权用户名。 */
        val username: String = "lolosia",
        /** Basic 鉴权密码；为空时拒绝一切请求。 */
        val password: String = "",
        /** 401 挑战里显示的 realm。 */
        val realm: String = "lolosia-webdav",
    )
}
