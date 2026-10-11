package moe.lolosia.web.settings

import kotlinx.serialization.Serializable
import moe.lolosia.web.util.llm.LlmSettings
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
    val dashboard: DashboardConfig = DashboardConfig(),
    val llm: LlmSettings = LlmSettings(),
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
        /**
         * 是否允许客户端访问**隐藏项**（`.` 开头的名字，以及文件系统自建的 `lost+found`）。
         *
         * 默认关闭，与在线文件管理器口径一致：`.cache`（缩略图缓存）之类不该混进机械盘的正常目录树。
         * 但有例外场景需要打开 —— 开发机把线上机的 `/dav` 映射成网络驱动器当本地盘用时，
         * 缩略图缓存就落在 `<root>/.cache` 下，不开这个开关客户端连建目录都会被拒（403）。
         *
         * 这里只是**配置文件里的初值**；面板上改过的值存在 `sys_cache`，以库里的为准
         * （见 `AppSettingService`）。
         */
        val allowHidden: Boolean = false,
    )

    /**
     * 在线文件管理器：以 [root] 为浏览根（留空则回退 [WebdavConfig.root]），列出目录、生成
     * 缩略图（浏览走 `lolosia.files.read`），并允许新建目录 / 改名 / 删除 / 上传
     * （写操作走 `lolosia.files.write`）。
     * 缩略图落在 `<root>/.cache/lolosia/thumb/v<版本>`，该目录对 WebDAV 隐藏
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
        val media: MediaConfig = MediaConfig(),
    ) {
        /** 实际生效的浏览根。 */
        fun resolveRoot(webdavRoot: String): String = root.ifBlank { webdavRoot }

        /**
         * 媒体索引：把浏览根下的图片 / 视频 / 音频登记成「一个文件一行」，抽出可筛选的元数据
         * （拍摄时间 / 光圈 / 机型 / GPS…），并支持人工打标签与评级。
         *
         * ⚠️ **后台扫描只做本地的事**（stat / 哈希 / EXIF / 缩略图 / 规则打标），
         * 标签与描述要等用户显式点「全盘分析」才会外发（见 `doc/media-index-design.md` §8.2）。
         */
        @Serializable
        data class MediaConfig(
            /** 是否启用媒体索引。关掉时所有入口都是空操作。 */
            val enabled: Boolean = true,
            /** 后台**本地**索引扫描间隔（秒），下限 300。 */
            val indexIntervalSeconds: Int = 1800,
            /** 探测元数据时读文件头的字节上限。 */
            val probeMaxBytes: Int = 262144,
            /** 探测器版本；升版即全量重探（语义同 `THUMBNAIL_CACHE_VERSION`）。 */
            val probeVersion: Int = 1,
            /** EXIF 没写时区时的兜底偏移（分钟，+480 = UTC+8）。 */
            val defaultTimezoneOffset: Int = 480,
            /** 连续多少轮扫描都没见到才软删（按扫描间隔折算成时间）。 */
            val missingScanLimit: Int = 3,
            val rules: RulesConfig = RulesConfig(),
            val vision: VisionConfig = VisionConfig(),
        ) {
            /** 规则打标（目录 / 文件名 → 标签或评级）。 */
            @Serializable
            data class RulesConfig(
                /**
                 * 是否启用。
                 *
                 * 它是**本地派生**：只读路径与文件名、不读内容、不出网，所以允许在后台扫描时自动跑
                 * （与「外发分析必须显式触发」不冲突）。想连它都关掉、完全手动打标，就设成 `false`。
                 */
                val enabled: Boolean = true,
            )

            /**
             * 外发分析（视觉自动打标，见 `doc/vision-autotag-design.md`）。
             *
             * 与 [RulesConfig] 相反，它**会把素材交给外部服务**，故默认全关（`enabled = false`）；
             * 必须由管理员显式开启并对某个范围点「分析」。密钥走 `AppSettings.llm.apiKey`
             * （环境变量 `KTOR_LLM_API_KEY`），不在此重复。
             */
            @Serializable
            data class VisionConfig(
                /** 是否启用外发分析。关掉时所有入口为空操作。 */
                val enabled: Boolean = false,
                /** 打标后端：`deepseek` | `noop`。 */
                val provider: String = "deepseek",
                /** 模型名（覆盖 `llm.model`）。 */
                val model: String = "deepseek-flash",
                /** 每请求图片数（阶段 A 的批量）；批量是省 token 的主要杠杆。 */
                val batchSize: Int = 6,
                /** 图片细节级别：`low`（默认，缩到 512²）| `original`。 */
                val detail: String = "low",
                /** 并发批次数；机械盘 + 限速，默认压到 1。 */
                val concurrency: Int = 1,
                /** 要跑哪些维度。 */
                val analyze: AnalyzeConfig = AnalyzeConfig(),
                /** 本地预筛与去重（零 token，不出网）。 */
                val prefilter: PreFilterConfig = PreFilterConfig(),
                /** 预算拦截。 */
                val budget: BudgetConfig = BudgetConfig(),
                /** 分词器来源（用于文本 token 预估与成本台账）。 */
                val tokenizer: TokenizerConfig = TokenizerConfig(),
            ) {
                /** 要跑哪些标注维度。 */
                @Serializable
                data class AnalyzeConfig(
                    /** 构图 / 人数（`scene:*`）。 */
                    val composition: Boolean = true,
                    /** 是否有效内容（`meta:有效` / `meta:无效`）。 */
                    val validity: Boolean = true,
                    /** 题材（`series:VRChat` / `series:娃照` / `series:杂项`）。 */
                    val subject: Boolean = true,
                    /** 一句话描述（落 `media_asset.caption`）。 */
                    val caption: Boolean = true,
                    /** 角色视觉属性（`attribute:粉发` …）。 */
                    val attributes: Boolean = true,
                    /** 画面里的显著物件 / 道具 / 场景元素（`object:蛋糕` …）。 */
                    val objects: Boolean = true,
                    /** 场合 / 活动（`event:生日会` …）：主要依据路径与文件夹名推断，也可望文生义。 */
                    val events: Boolean = true,
                    /** 画面里正在做的事 / 动作（`action:比耶` …）。 */
                    val actions: Boolean = true,
                    /** 角色识别（需参考库，V5，暂未实现；开了也不生效）。 */
                    val character: Boolean = false,
                )

                /** 本地预筛参数（模糊 / 纯色 / 近重复）。 */
                @Serializable
                data class PreFilterConfig(
                    /** 拉普拉斯方差下限（越大越锐）；低于它判废片。暂未启用。 */
                    val blurThreshold: Int = 60,
                    /** dHash 汉明距离阈值；以内判近重复（组内只发代表图）。 */
                    val dedupHamming: Int = 6,
                    /** 内联档的最长边像素（本地降采样目标）。 */
                    val maxUploadEdge: Int = 512,
                )

                /** 预算拦截（反算依据见设计稿 §3.5）。 */
                @Serializable
                data class BudgetConfig(
                    /** 只在空闲（半价）时段开跑；手动触发可覆盖。 */
                    val preferOffPeakOnly: Boolean = true,
                    /** 单次作业输入 token 上限。 */
                    val maxPromptTokensPerRun: Long = 1_000_000,
                    /** 日累计输入 token 上限。 */
                    val dailyPromptTokenLimit: Long = 3_000_000,
                )

                /** 分词器来源（运行期拉取并缓存，拉不到降级为字符启发式）。 */
                @Serializable
                data class TokenizerConfig(
                    /**
                     * 主源：ModelScope 原始 `tokenizer.json`（**直接是 JSON**，无需解压）。
                     *
                     * 官方的 `https://cdn.deepseek.com/api-docs/deepseek_v4_tokenizer.zip` 是压缩包，
                     * 原生端不便解压，故默认走镜像的原始文件。
                     */
                    val url: String =
                        "https://www.modelscope.cn/api/v1/models/deepseek-ai/DeepSeek-V4.1-Flash/repo" +
                            "?Revision=master&FilePath=tokenizer.json",
                    /** 备用源（不可用时回退字符启发式）。 */
                    val mirrorUrl: String =
                        "https://modelscope.cn/models/deepseek-ai/DeepSeek-V4.1-Flash/resolve/master/tokenizer.json",
                    /** 下载后缓存目录（相对浏览根）。 */
                    val cacheDir: String = ".cache/lolosia/llm",
                )
            }
        }

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
            /** 自动裁边：识别并裁掉图片四边的近纯白装饰带（手机水印白条 / VRChat 白框）。 */
            val autoCrop: AutoCropConfig = AutoCropConfig(),
        ) {
            /**
             * 缩略图自动裁边的参数（判据见 [moe.lolosia.web.service.files.AutoCrop]）。
             *
             * 只裁「近纯白边带」，亮底照片与阴天亮区会被占比 / 连续段判据挡下；四边各自独立判定。
             */
            @Serializable
            data class AutoCropConfig(
                /** 是否启用。 */
                val enabled: Boolean = true,
                /** 每通道近白下限（0~255）：真白边是 255，阴天亮区多在 244 上下。 */
                val white: Int = 246,
                /** 行/列内近白像素占比下限。 */
                val minRatio: Double = 0.55,
                /** 行/列内非白像素最长连续段占比上限（文字是细笔画、成片内容会被挡下）。 */
                val maxRunRatio: Double = 0.15,
                /** 单边最大裁剪比例（防止亮底照片被整片误裁）。 */
                val maxTrimRatio: Double = 0.35,
            )
        }
    }

    /**
     * 仪表盘：系统指标采样与访问统计。
     *
     * [enabled] 关掉时，采样循环、访问埋点全部空转（接口返回空数据）。
     * 指标采集两端都支持：Linux 解析 `/proc`，Windows 走 Win32 / NT API（见 `util/system/SystemMetricsReader.*`）。
     */
    @Serializable
    data class DashboardConfig(
        /** 是否启用后台采样与访问统计。 */
        val enabled: Boolean = false,
        /** 系统指标采样间隔（秒），下限 1。 */
        val sampleIntervalSeconds: Int = 2,
        /** 指标落库的保留时长（小时），旧行按它清理。 */
        val retainHours: Int = 48,
        /**
         * 统计磁盘容量用的挂载点（如 `/mnt/hdd1`）；留空则回退根分区 `/`。
         * Windows 固定统计系统盘，忽略此值。
         */
        val diskPath: String = "/",
    )
}
