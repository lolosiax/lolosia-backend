package moe.lolosia.web.util.image

/**
 * 图像容器格式。
 *
 * [UNKNOWN] 既表示识别不出的字节流，也表示不支持编码的目标格式。
 *
 * @author 洛洛希雅Lolosia
 */
enum class ImageFormat {
    PNG,
    JPEG,
    GIF,
    WEBP,
    UNKNOWN,
}
