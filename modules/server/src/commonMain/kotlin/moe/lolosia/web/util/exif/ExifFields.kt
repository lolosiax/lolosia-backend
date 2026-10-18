package moe.lolosia.web.util.exif

import kotlin.time.Instant

/**
 * 一次 EXIF 解析的结果：**提升成列的字段** + 长尾原文。
 *
 * 哪些字段提升成列、哪些留在 [entries] 里，判据见 `doc/media-index-design.md` §4.7：
 * 要在 UI 里筛 / 排的进强类型属性，其余（`Software` / `WhiteBalance` / `SceneCaptureType` …）
 * 只作展示。
 *
 * `takenAt` 与 `takenAtLocal` 的分工见 §6.3：前者是折算成 UTC 的时刻（用于排序与范围筛选，
 * 受 MySQL `TIMESTAMP` 可表示范围限制），后者是 EXIF 原文的墙上时间（永远保留，用于展示）。
 *
 * @property takenAt 已折算 UTC；超出 `TIMESTAMP` 可表示范围时为 `null`（此时只看 [takenAtLocal]）
 * @property takenAtOffsetMin EXIF 里的时区偏移（分钟）；缺失为 `null`
 * @property pixelWidth / @property pixelHeight EXIF 自报的像素尺寸，用于我们解不了码的格式（如 HEIC）
 * @author 洛洛希雅Lolosia
 */
data class ExifFields(
    val cameraMake: String? = null,
    val cameraModel: String? = null,
    val lens: String? = null,
    /** F 数，如 1.8。 */
    val aperture: Double? = null,
    /** 原文，如 `1/250`。 */
    val exposureTime: String? = null,
    val iso: Int? = null,
    /** 毫米。 */
    val focalLength: Double? = null,
    /** EXIF Orientation（1~8）。 */
    val orientation: Int? = null,
    /** `yyyy-MM-dd HH:mm:ss`（EXIF 原文的墙上时间）。 */
    val takenAtLocal: String? = null,
    val takenAt: Instant? = null,
    val takenAtOffsetMin: Int? = null,
    val pixelWidth: Int? = null,
    val pixelHeight: Int? = null,
    val gps: Gps? = null,
    /** 长尾原文（已截断、已限量）。 */
    val entries: List<ExifEntry> = emptyList(),
)
