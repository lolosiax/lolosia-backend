package moe.lolosia.web.service.media

import moe.lolosia.web.settings.AppSettings
import moe.lolosia.web.util.exif.ExifReader
import moe.lolosia.web.util.image.Image
import moe.lolosia.web.util.media.MediaKind
import moe.lolosia.web.util.router.Injectable
import moe.lolosia.web.util.router.Service

/**
 * 元数据探测：吃文件头的几百 KB 字节，吐 [MediaProbeResult]。
 *
 * 只做**本地**的事 —— 读的是自己在机械盘上的文件，出不了网（§8.2）。
 *
 * 覆盖面见 `doc/media-index-design.md` §6.1：v1 做 `image/…` 的 EXIF（自研解析器，零依赖）；
 * 视频 / 音频只登记身份与大小，容器内的时长、宽高等留给外发分析的 worker（§8.1）。
 *
 * @author 洛洛希雅Lolosia
 */
@Injectable
@Service
class MediaProbe(settings: AppSettings) {

    private val config = settings.files.media

    /** 当前探测器版本；升版即全量重探（语义同 `THUMBNAIL_CACHE_VERSION`）。 */
    val version: Int get() = config.probeVersion

    /**
     * 探测一个媒体文件的头部字节。
     *
     * 像素尺寸优先取 EXIF 的 `PixelXDimension` / `PixelYDimension`（比解容器头省事且更准），
     * 缺了才回退到 [Image] 的纯头解析（只读几十字节，不解码像素）。
     */
    fun probe(kind: MediaKind, head: ByteArray): MediaProbeResult {
        if (kind != MediaKind.IMAGE) return MediaProbeResult()
        val fields = ExifReader.read(head, config.defaultTimezoneOffset)
        val fallback = if (fields?.pixelWidth == null || fields.pixelHeight == null) Image.sizeOf(head) else null
        return MediaProbeResult(
            width = fields?.pixelWidth ?: fallback?.width,
            height = fields?.pixelHeight ?: fallback?.height,
            fields = fields,
        )
    }
}
