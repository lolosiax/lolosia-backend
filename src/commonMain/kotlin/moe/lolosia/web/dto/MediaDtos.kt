package moe.lolosia.web.dto

import kotlinx.serialization.Serializable

/**
 * 媒体索引与属性页的接口 DTO（见 `doc/media-index-design.md` §11）。
 *
 * `path` 一律是**相对浏览根**的路径，与 [FileEntry] 同一口径（根目录是空串）。
 *
 * @author 洛洛希雅Lolosia
 */

/** 一对经纬度；列表接口永不带它，详情接口按 `lolosia.media.location` 决定是否给出。 */
@Serializable
data class MediaGps(val latitude: Double, val longitude: Double)

/** 属性页里逐条展示的 EXIF 长尾项（`metadata_json` 展开后的键值对）。 */
@Serializable
data class MediaExifItem(val key: String, val value: String)

/** 标签项：词表里的一个标签 + 它被引用的次数（分面与候选列表都用它）。 */
@Serializable
data class MediaTagItem(
    val id: Long,
    val namespace: String,
    val name: String,
    val parentId: Long? = null,
    /** 被多少个未删除的资产引用。 */
    val count: Long = 0,
    /** 生效来源：`manual` / `rule` / `model`（`manual > rule > model`，§7.3）。 */
    val source: String = "",
    /** 人工否决：这张图明确不是该标签；属性页据此画成带删除线的红标签。 */
    val negative: Boolean = false,
)

/** 单个文件的完整媒体信息（属性编辑页与检索结果共用）。 */
@Serializable
data class MediaDetail(
    val path: String,
    val name: String,
    /** `image` / `video` / `audio` / `other`。 */
    val kind: String,
    val size: Long = 0,
    val mtime: Long = 0,
    val width: Int? = null,
    val height: Int? = null,
    val durationMs: Long? = null,
    /** `unknown` / `safe` / `questionable` / `explicit`。 */
    val rating: String = "unknown",
    /** `manual` / `rule`；未设过为 `null`。 */
    val ratingSource: String? = null,
    /** 禁止外发分析。 */
    val analysisSkip: Boolean = false,
    /** 一句话描述；`manual` 来源时模型不得覆盖。 */
    val caption: String? = null,
    val captionSource: String? = null,
    /** 拍摄时刻（毫秒，UTC）；没探测到为 `null`。 */
    val takenAt: Long? = null,
    /** EXIF 原文的墙上时间。 */
    val takenAtLocal: String? = null,
    val takenAtOffsetMin: Int? = null,
    val cameraMake: String? = null,
    val cameraModel: String? = null,
    val lens: String? = null,
    val aperture: Double? = null,
    val exposureTime: String? = null,
    val iso: Int? = null,
    val focalLength: Double? = null,
    val orientation: Int? = null,
    /** 8×8 采样（每像素 4 位十六进制，RGB565 大端，共 256 字符）；没有为空串。 */
    val sample: String = "",
    /** 经纬度；没有权限时恒为 `null`。 */
    val gps: MediaGps? = null,
    /** EXIF 长尾项。 */
    val metadata: List<MediaExifItem> = emptyList(),
    val tags: List<MediaTagItem> = emptyList(),
    /** 是否已被媒体索引登记（未登记的图属性页只给得出通用信息）。 */
    val indexed: Boolean = false,
    /** 直链签名的过期时间戳（毫秒）与签名值。 */
    val exp: Long = 0,
    val sig: String = "",
)

/** 属性页一次性提交。 */
@Serializable
data class MediaPropertyReq(
    val path: String? = null,
    /** 四档之一；`null` = 不改。 */
    val rating: String? = null,
    /** 「不上传到外部分析」；`null` = 不改。 */
    val analysisSkip: Boolean? = null,
    /** 一句话描述；`null` = 不改（写库时来源记 `manual`）。 */
    val caption: String? = null,
)

/** 批量定级：把同一套评级应用到本目录（含子目录）。 */
@Serializable
data class MediaPropertyApplyReq(
    val path: String? = null,
    val recursive: Boolean = false,
    val rating: String? = null,
    val analysisSkip: Boolean? = null,
)

/** 请求扫描某个目录（**仅本地索引**，不出网）。`path` 为空表示浏览根。 */
@Serializable
data class MediaIndexScanReq(val path: String? = null)

/**
 * 请求里的一个标签引用。`namespace` + `name` 组合定位词表里的一行，**不存在就按需建**
 * （根级自由标签是一等公民，§7.5）。
 */
@Serializable
data class MediaTagRef(val namespace: String, val name: String)

/**
 * 人工打标 / 解除 / 否决的共同请求体（`doc/media-index-design.md` §11.2 的 `media/tags/…`）。
 *
 * 三个接口形状一致，差别只在语义：`attach` 打上（来源 `manual`）、`detach` 解除关联、
 * `deny` 记否决（`negative = 1`，规则与模型都不会再加回来）。
 */
@Serializable
data class MediaTagsReq(
    val path: String? = null,
    val tags: List<MediaTagRef> = emptyList(),
)

/**
 * SSE 事件 `media:index` 的负载：这个文件的本地索引刚完成。
 *
 * 前端据此就地更新遮罩与元数据，不必整目录重列（重列在机械盘上很贵）。
 */
@Serializable
data class MediaIndexEvent(
    val path: String,
    val rating: String = "unknown",
    val indexed: Boolean = true,
    /** 拍摄时刻（毫秒，UTC）；没有为 0。 */
    val takenAt: Long = 0,
    /** 8×8 采样（每像素 4 位十六进制，RGB565 大端，共 256 字符）；没有为空串。 */
    val sample: String = "",
)
