package moe.lolosia.thirdparty

/**
 * 解码后的 8bit RGBA 像素图，行优先，每像素依次 R、G、B、A。
 *
 * thirdparty 下各图像库门面（libpng / libjpeg-turbo / giflib / libwebp）统一用它收发像素，
 * 避免把各自的指针类型泄漏到调用方。
 *
 * @property width 像素宽
 * @property height 像素高
 * @property pixels 像素缓冲，长度恒为 `width * height * 4`
 * @author 洛洛希雅Lolosia
 */
class RgbaImage(val width: Int, val height: Int, val pixels: ByteArray) {

    init {
        require(width > 0 && height > 0) { "图片尺寸非法：${width}x$height" }
        require(pixels.size == width * height * 4) { "像素数据长度与尺寸不符：${pixels.size} != ${width * height * 4}" }
    }
}
