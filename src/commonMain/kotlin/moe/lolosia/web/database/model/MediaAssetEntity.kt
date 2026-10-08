package moe.lolosia.web.database.model

import io.github.smyrgeorge.sqlx4k.annotation.Id
import io.github.smyrgeorge.sqlx4k.annotation.Table
import kotlin.time.Instant

/**
 * `media_asset`：一个文件一行（位置 + 新鲜度 + 身份 + 提升成列的元数据 + 评级）。
 *
 * 四个身份键分工见 `doc/media-index-design.md` §2.2：
 * [path] 是「现在在哪」，[size] + [mtime] 决定「要不要重做」（零读取的新鲜度短路），
 * [quickId] 用来判断「改了 mtime 之后内容到底变没变」，[contentSha256] 才是「是不是同一份内容」
 * （全量哈希，异步补齐）。
 *
 * [rating] / [analysisSkip] 是**人工或规则**写入的合规闸门，模型永不参与（§7.2 / §10.2）；
 * `analyze*` 只有在用户显式点「全盘分析」之后才会变（§8.2），后台扫描不碰它们。
 *
 * [alivePath] 是表的生成列（软删时取 `NULL`），只为让「历史软删行」不撞唯一键，代码只读不写。
 *
 * @author 洛洛希雅Lolosia
 */
@Table("media_asset")
data class MediaAssetEntity(
    @Id
    val id: Long = 0L,
    /** 相对浏览根的路径（无前导 `/`）。 */
    val path: String,
    /** 父目录相对路径；根目录为空串。列目录摘要靠它一次取回。 */
    val dirPath: String,
    val name: String,
    /** `image` / `video` / `audio` / `other`。 */
    val kind: String = "other",
    val size: Long = 0L,
    /** 源文件修改时间（毫秒）。 */
    val mtime: Long = 0L,
    /** `size` + 首尾各 64KiB 的 sha256；算不出时为 `null`。 */
    val quickId: String? = null,
    /** 全量 SHA-256；异步补齐，只在同内容冲突判定时用。 */
    val contentSha256: String? = null,
    /** `unknown` / `safe` / `questionable` / `explicit`。 */
    val rating: String = RATING_UNKNOWN,
    /** `manual` / `rule`；未设过评级为 `null`。 */
    val ratingSource: String? = null,
    /** 禁止外发分析；`rating = explicit` 时强制为 `true`。 */
    val analysisSkip: Boolean = false,
    val width: Int? = null,
    val height: Int? = null,
    val durationMs: Long? = null,
    /** 折算成 UTC 的拍摄时刻，用于排序与范围筛选。 */
    val takenAt: Instant? = null,
    /** EXIF 原文的墙上时间，用于展示（不参与筛选）。 */
    val takenAtLocal: String? = null,
    val takenAtOffsetMin: Int? = null,
    val cameraMake: String? = null,
    val cameraModel: String? = null,
    val lens: String? = null,
    /** F 数，如 1.8。 */
    val aperture: Double? = null,
    /** 原文，如 `1/250`。 */
    val exposureTime: String? = null,
    val iso: Int? = null,
    /** 焦距（毫米）。 */
    val focalLength: Double? = null,
    val gpsLat: Double? = null,
    val gpsLon: Double? = null,
    val orientation: Int? = null,
    /**
     * 8×8 采样：每像素 4 位十六进制（RGB565 大端），共 256 字符；解不出为 `null`。
     *
     * 由缩略图生成时顺带产出（复用同一次解码），前端拿它做**加载前的占位**与
     * **敏感内容的遮罩底图**（露骨图默认只下发采样、不下发原图，见 §10.3）。
     */
    val sampleRgb565: String? = null,
    /** 长尾原文（EXIF / TIFF / 容器头），截断保存。 */
    val metadataJson: String? = null,
    val probeAt: Instant? = null,
    /** 探测器版本；升版即重探。 */
    val probeVersion: Int? = null,
    /** `none` / `queued` / `running` / `done` / `failed` / `skipped`。 */
    val analyzeState: String = ANALYZE_NONE,
    val analyzedAt: Instant? = null,
    val analyzeProvider: String? = null,
    val caption: String? = null,
    /** `manual` / `model`；手填的描述模型不得覆盖。 */
    val captionSource: String? = null,
    /** 上次扫描未见的时间；连续缺失才软删。 */
    val missingSince: Instant? = null,
    /** 内容重复时并入的资产 id。 */
    val mergedInto: Long? = null,
    val createdAt: Instant? = null,
    val createdBy: String? = null,
    val updatedAt: Instant? = null,
    val updatedBy: String? = null,
    val deleted: Boolean = false,
    /** 生成列，只读。 */
    val alivePath: String? = null,
)

/** 评级缺省值。 */
const val RATING_UNKNOWN: String = "unknown"

/** 评级：安全。 */
const val RATING_SAFE: String = "safe"

/** 评级：擦边。 */
const val RATING_QUESTIONABLE: String = "questionable"

/** 评级：露骨；触发前端遮罩、外发闸门与直链 `NSFW` 位（`doc/media-index-design.md` §7.2 / §10）。 */
const val RATING_EXPLICIT: String = "explicit"

/** 外发分析尚未开始。 */
const val ANALYZE_NONE: String = "none"
