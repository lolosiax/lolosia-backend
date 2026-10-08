package moe.lolosia.web.util.media

import moe.lolosia.web.service.files.extensionOf

/**
 * 媒体类型判定的散装常量与函数（公共顶层声明，见 AGENTS.md §1.5）。
 *
 * 只按**扩展名**判定，不读文件头 —— 扫描一个目录时逐文件读头在机械盘上不可接受。
 *
 * @author 洛洛希雅Lolosia
 */

/** 算 `quick_id` 时首尾各取多少字节。 */
const val QUICK_ID_CHUNK_BYTES: Long = 64L * 1024

/** 图片扩展名（含后端解不了码、但确实是图片的那些）。 */
val IMAGE_EXTENSIONS_ALL: Set<String> =
    setOf("png", "jpg", "jpeg", "jpe", "gif", "webp", "bmp", "tif", "tiff", "heic", "heif", "avif", "svg", "ico")

/** 视频扩展名。 */
val VIDEO_EXTENSIONS: Set<String> =
    setOf("mp4", "mov", "m4v", "mkv", "avi", "webm", "flv", "wmv", "ts", "mts", "3gp", "mpg", "mpeg", "vob")

/** 音频扩展名。 */
val AUDIO_EXTENSIONS: Set<String> =
    setOf("mp3", "flac", "wav", "m4a", "aac", "ogg", "opus", "wma", "ape", "aiff")

/** 需要索引的扩展名（扫描时据此跳过无关文件）。 */
val MEDIA_EXTENSIONS: Set<String> = IMAGE_EXTENSIONS_ALL + VIDEO_EXTENSIONS + AUDIO_EXTENSIONS

/** 是否是我们关心的媒体文件。 */
fun isMediaName(name: String): Boolean = extensionOf(name) in MEDIA_EXTENSIONS

/** 按扩展名判定大类。 */
fun kindOf(name: String): MediaKind {
    val ext = extensionOf(name)
    return when (ext) {
        in IMAGE_EXTENSIONS_ALL -> MediaKind.IMAGE
        in VIDEO_EXTENSIONS -> MediaKind.VIDEO
        in AUDIO_EXTENSIONS -> MediaKind.AUDIO
        else -> MediaKind.OTHER
    }
}
