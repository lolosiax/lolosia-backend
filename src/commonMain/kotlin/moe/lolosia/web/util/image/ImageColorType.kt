package moe.lolosia.web.util.image

/**
 * 图像色彩类型，按**源文件头**判定（不是解码后在内存里的形态 —— 内存里恒为 RGBA）。
 *
 * @author 洛洛希雅Lolosia
 */
enum class ImageColorType {
    RGB,
    RGBA,
    GRAY,
    GRAY_ALPHA,
    PALETTE,
    UNKNOWN,
}
