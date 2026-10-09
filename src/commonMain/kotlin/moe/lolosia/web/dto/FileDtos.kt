package moe.lolosia.web.dto

import kotlinx.serialization.Serializable
import moe.lolosia.web.service.files.DEFAULT_MIME

/**
 * 在线文件管理器的接口 DTO。
 *
 * 字段刻意给足：一次列表就带上大小、修改时间、MIME、是否图片、有没有缩略图，
 * 让前端不必为每一项再发一轮请求（机械盘上每一次额外的 stat 都是真的慢）。
 *
 * `path` 一律是**相对浏览根**的路径（`a/b/c.jpg`，无前导 `/`），根目录是空串。
 *
 * @author 洛洛希雅Lolosia
 */

/** 目录项 / 文件项。 */
@Serializable
data class FileEntry(
    val name: String,
    val path: String,
    val directory: Boolean,
    /** 文件字节数；目录为 0。 */
    val size: Long = 0,
    /** 修改时间（毫秒）。 */
    val mtime: Long = 0,
    /** 小写扩展名（不含点）。 */
    val ext: String = "",
    val mime: String = DEFAULT_MIME,
    /** 是否图片（按扩展名判定）。 */
    val image: Boolean = false,
    /** 是否已生成缩略图（最小档存在即为真）。 */
    val thumb: Boolean = false,
    /**
     * 目录内是否还有子项（文件或目录，忽略 `.` 开头的隐藏项）；文件恒为 `false`。
     *
     * 这是「**是否为空**」的标志位，前端据此把空文件夹与有内容的文件夹区分开。
     * 代价是每个**子目录**多一次目录枚举（见 [moe.lolosia.web.service.files.FileBrowser.mapChildren]）。
     */
    val hasChildren: Boolean = false,
    /**
     * 目录内是否还有**子目录**（同样忽略隐藏项）；文件恒为 `false`。
     *
     * 这是「**能否展开**」的标志位，和 [hasChildren] 是两回事：
     * 一个目录可以塞满文件却没有任何子目录（`hasChildren = true` 而 `hasSubdirs = false`），
     * 目录树据此决定画不画展开箭头、以及展开后该提示「空」还是「没有子文件夹」。
     *
     * 只有 `files/dirs`（目录树）填它——判定它要枚举子目录再逐个 stat 子项，
     * 网格/列表视图用不到就不付这份代价。
     */
    val hasSubdirs: Boolean = false,
    /**
     * 评级（`unknown` / `safe` / `questionable` / `explicit`），由媒体索引提供。
     *
     * 前端据此给 `explicit` / `questionable` 的项加遮罩（见 `doc/media-index-design.md` §10.3）。
     * **数据库不可用时恒为 `unknown`** —— 列目录本身不依赖库，这是有意的降级。
     */
    val rating: String = "unknown",
    /** 是否已被媒体索引登记；未登记的项属性页只给得出通用信息。 */
    val indexed: Boolean = false,
    /**
     * 8×8 采样：每像素 4 位十六进制（RGB565 大端），共 256 字符；没有则为空串。
     *
     * 前端把它画成 8×8 的图铺满格子：既当**缩略图加载出来之前的占位**，
     * 也当**敏感内容的遮罩底图**（露骨图默认不下发原图）。
     */
    val sample: String = "",
    /** 拍摄时刻（毫秒，UTC）；没探测到为 0。 */
    val takenAt: Long = 0,
    /**
     * 直链签名的过期时间戳（毫秒）与签名值；只由 `files/list` / `files/detail` 填充
     * （`files/dirs` 只喂目录树，用不上）。
     *
     * 签名覆盖「相对路径 + 过期时间」，所以同一个 `exp`/`sig` 可以取该文件的任意档位缩略图
     * 与原始文件，见 [moe.lolosia.web.service.files.FileSigner]。
     */
    val exp: Long = 0,
    val sig: String = "",
)

/** 目录列表结果。 */
@Serializable
data class FileListResult(
    /** 当前目录的相对路径。 */
    val path: String,
    /** 父目录相对路径；已在根目录为 `null`。 */
    val parent: String? = null,
    val entries: List<FileEntry> = emptyList(),
)

/** 单个文件的详细信息（预览面板用）。 */
@Serializable
data class FileDetail(
    val entry: FileEntry,
    /** 原图像素宽；非图片或未探测到为 `null`。 */
    val width: Int? = null,
    /** 原图像素高；非图片或未探测到为 `null`。 */
    val height: Int? = null,
    /** 已生成的缩略图档位（最长边像素）。 */
    val thumbs: List<Int> = emptyList(),
)

/** 请求扫描某个目录（高优先级，用于用户主动打开目录）。`path` 为空表示浏览根。 */
@Serializable
data class FileScanReq(val path: String? = null)

/** 重命名请求：把 [path] 指向的项在同级改名为 [name]（只改名，不移动；目录与文件都可以）。 */
@Serializable
data class FileRenameReq(val path: String? = null, val name: String = "")

/** 新建目录请求：在 [path] 目录下新建名为 [name] 的子目录（`path` 为空表示浏览根）。 */
@Serializable
data class FileMkdirReq(val path: String? = null, val name: String = "")

/** 删除请求：删除 [path] 指向的文件或目录（目录连子内容一起递归删）。 */
@Serializable
data class FileDeleteReq(val path: String? = null)

/**
 * 一次写操作（重命名 / 新建目录 / 删除 / 上传）之后的结果：涉及的相对路径与新名字。
 *
 * 前端拿它做两件事：如果**当前路径就在被改名的那个目录下**，用 [path] 换掉旧前缀；
 * 否则只重列当前目录（删除与上传则只用来提示）。
 */
@Serializable
data class FileOpResult(val path: String, val name: String)

/**
 * 一个**已签名**的相对路径：`exp` / `sig` 与 [FileEntry] 里的同名字段同义。
 *
 * [FileThumbEvent] 用它而不是裸路径，是因为 `<img>` 换源必须走签名直链，
 * 而列表接口下发的那份签名可能已经过期（页面开着超过 `signTtlSeconds`）。
 */
@Serializable
data class SignedFile(
    val path: String,
    val exp: Long = 0,
    val sig: String = "",
)

/**
 * SSE 事件 `files:thumb` 的负载：这些文件的缩略图刚生成完，前端可以重新拉取。
 *
 * 逐文件推送而非每轮扫描推一次：前端只需把命中的那一项标成「有缩略图」并让 `<img>` 换源，
 * 不必整目录重列（重列在机械盘上很贵）。
 *
 * 带的是**新签发的**签名，所以即使页面已停留很久、列表里的签名早已过期，换源也依然能取到图。
 */
@Serializable
data class FileThumbEvent(val files: List<SignedFile> = emptyList())
