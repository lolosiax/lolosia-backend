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
    val files: FilesConfig = FilesConfig(),
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

    /**
     * 在线文件管理器：以 [root] 为浏览根（留空则回退 [WebdavConfig.root]），只读地列出目录与
     * 生成缩略图。缩略图落在 `<root>/.cache/lolosia/thumb/v1`，该目录对 WebDAV 隐藏
     * （见 `DavStore`），避免污染机械盘的正常目录树。
     *
     * 缩略图生成会在后台按 [ThumbnailConfig.scanIntervalSeconds] 周期扫描，也会在用户打开目录时
     * 立即插队处理。**开发机与线上机必须只在一台开启**，否则两边同时写同一份缓存会互相覆盖。
     */
    @Serializable
    data class FilesConfig(
        /** 浏览根目录（绝对路径）。留空则回退 `webdav.root`。 */
        val root: String = "",
        /**
         * 缩略图 / 原图直链的 HMAC 签名密钥。
         *
         * 这两个接口是给 `<img src>` 用的，浏览器原生标签带不了 `Authorization`，
         * 所以改成 URL 签名：由列表接口下发 `exp` + `sig`，取值时校验 HMAC 与有效期。
         * 留空则本次启动随机生成（重启后旧直链失效，前端重新列目录即可）。
         */
        val signKey: String = "",
        /** 直链签名的有效期（秒），下限 60。 */
        val signTtlSeconds: Int = 900,
        val thumbnail: ThumbnailConfig = ThumbnailConfig(),
    ) {
        /** 实际生效的浏览根。 */
        fun resolveRoot(webdavRoot: String): String = root.ifBlank { webdavRoot }

        @Serializable
        data class ThumbnailConfig(
            /** 是否启用后台缩略图生成。 */
            val enabled: Boolean = false,
            /** 缩略图档位：最长边像素，按从小到大排列。 */
            val sizes: List<Int> = listOf(256, 512, 1024),
            /** WebP 编码质量（1~100）。 */
            val quality: Int = 80,
            /** 后台全量扫描的间隔（秒），下限 60。 */
            val scanIntervalSeconds: Int = 600,
            /** 超过该字节数的源文件跳过（避免在小内存设备上解码爆内存）。 */
            val maxSourceBytes: Long = 64L * 1024 * 1024,
        )
    }
}
