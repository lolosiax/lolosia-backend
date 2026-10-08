package moe.lolosia.web.service.media

import moe.lolosia.web.util.exif.ExifFields

/**
 * 一次探测的结果：提升成列的几项 + 全量 EXIF（长尾项落 `metadata_json`）。
 *
 * @author 洛洛希雅Lolosia
 */
data class MediaProbeResult(
    val width: Int? = null,
    val height: Int? = null,
    val fields: ExifFields? = null,
)
