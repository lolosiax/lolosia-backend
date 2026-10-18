package moe.lolosia.web.util.media

/**
 * 资产大类。
 *
 * 与文件管理器的 `image` 判定（`isImageName`，只认可解码的 5 种）不是一回事：
 * 这里按「这是什么媒体」分，`heic` / `bmp` / `tiff` 也算图片，只是后端解不了码。
 *
 * @author 洛洛希雅Lolosia
 */
enum class MediaKind(val id: String) {
    IMAGE("image"),
    VIDEO("video"),
    AUDIO("audio"),
    OTHER("other"),
    ;

    companion object {
        /** 从数据库的字符串还原；未知值一律归 [OTHER]。 */
        fun of(id: String?): MediaKind = entries.firstOrNull { it.id == id } ?: OTHER
    }
}
