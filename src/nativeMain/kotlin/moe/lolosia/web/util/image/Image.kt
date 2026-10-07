package moe.lolosia.web.util.image

import kotlin.math.*
import moe.lolosia.thirdparty.ImageInfo
import moe.lolosia.thirdparty.RgbaImage
import moe.lolosia.thirdparty.gif.Gif
import moe.lolosia.thirdparty.jpeg.Jpeg
import moe.lolosia.thirdparty.png.Png
import moe.lolosia.thirdparty.webp.Webp

actual class Image {

    /** 解码后的 RGBA 像素；两个 native 目标共用这一份实现。 */
    private val pixels: RgbaImage

    actual val format: ImageFormat

    /** 源文件头信息，只用于 [colorType]。 */
    private val info: ImageInfo

    actual constructor(bytes: ByteArray) {
        val detected = magicOf(bytes)
        if (detected == ImageFormat.UNKNOWN) throw ImageCodecException("无法识别的图像格式（${bytes.size} 字节）")
        pixels = decode(detected, bytes) ?: throw ImageCodecException("图像解码失败：$detected")
        format = detected
        info = probe(detected, bytes) ?: ImageInfo(pixels.width, pixels.height)
    }

    actual constructor(width: Int, height: Int) {
        require(width > 0 && height > 0) { "画布尺寸必须为正：${width}x$height" }
        pixels = RgbaImage(width, height, ByteArray(width * height * 4))
        format = ImageFormat.UNKNOWN
        info = ImageInfo(width, height, hasAlpha = true)
    }

    actual val width: Int get() = pixels.width

    actual val height: Int get() = pixels.height

    actual val size: ImageSize get() = ImageSize(pixels.width, pixels.height)

    actual val colorType: ImageColorType get() = info.toColorType()

    actual fun toByteArray(format: ImageFormat): ByteArray {
        val encoded = when (format) {
            ImageFormat.PNG -> Png.encode(pixels)
            ImageFormat.JPEG -> Jpeg.encode(pixels)
            ImageFormat.WEBP -> Webp.encode(pixels)
            ImageFormat.GIF, ImageFormat.UNKNOWN -> null
        }
        return encoded ?: throw ImageCodecException("无法编码为 $format")
    }

    actual fun fill(color: Int) {
        val buffer = pixels.pixels
        var i = 0
        while (i < buffer.size) {
            buffer[i] = (color ushr 16).toByte()
            buffer[i + 1] = (color ushr 8).toByte()
            buffer[i + 2] = color.toByte()
            buffer[i + 3] = (color ushr 24).toByte()
            i += 4
        }
    }

    actual fun drawPoint(x: Int, y: Int, color: Int) {
        writePixel(pixels, x, y, color)
    }

    actual fun drawLine(x0: Int, y0: Int, x1: Int, y1: Int, color: Int) {
        var x = x0
        var y = y0
        val dx = abs(x1 - x0)
        val dy = -abs(y1 - y0)
        val sx = if (x0 < x1) 1 else -1
        val sy = if (y0 < y1) 1 else -1
        var err = dx + dy
        while (true) {
            writePixel(pixels, x, y, color)
            if (x == x1 && y == y1) break
            val e2 = 2 * err
            if (e2 >= dy) {
                err += dy
                x += sx
            }
            if (e2 <= dx) {
                err += dx
                y += sy
            }
        }
    }

    actual fun drawRect(x: Int, y: Int, width: Int, height: Int, color: Int, filled: Boolean) {
        if (width <= 0 || height <= 0) return
        if (filled) {
            for (row in y until y + height) {
                for (col in x until x + width) writePixel(pixels, col, row, color)
            }
        } else {
            drawLine(x, y, x + width - 1, y, color)
            drawLine(x, y, x, y + height - 1, color)
            drawLine(x + width - 1, y, x + width - 1, y + height - 1, color)
            drawLine(x, y + height - 1, x + width - 1, y + height - 1, color)
        }
    }

    actual fun draw(source: Image, x: Int, y: Int) {
        composite(pixels, source.pixels, x, y)
    }

    actual companion object {
        actual fun typeOf(bytes: ByteArray): ImageFormat = magicOf(bytes)

        actual fun sizeOf(bytes: ByteArray): ImageSize? =
            probe(magicOf(bytes), bytes)?.let { ImageSize(it.width, it.height) }

        actual fun colorTypeOf(bytes: ByteArray): ImageColorType? =
            probe(magicOf(bytes), bytes)?.toColorType()
    }
}

/** 只按文件头识别格式；识别不出回 [ImageFormat.UNKNOWN]。 */
private fun magicOf(bytes: ByteArray): ImageFormat = when {
    bytes.matches(0, 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A) -> ImageFormat.PNG
    bytes.matches(0, 0xFF, 0xD8, 0xFF) -> ImageFormat.JPEG
    bytes.matches(0, 0x47, 0x49, 0x46, 0x38, 0x37) || bytes.matches(0, 0x47, 0x49, 0x46, 0x38, 0x39) -> ImageFormat.GIF
    bytes.matches(0, 0x52, 0x49, 0x46, 0x46) && bytes.matches(8, 0x57, 0x45, 0x42, 0x50) -> ImageFormat.WEBP
    else -> ImageFormat.UNKNOWN
}

/** 判断 [offset] 起是否与给定魔数一致；[magic] 按 0~255 给。 */
private fun ByteArray.matches(offset: Int, vararg magic: Int): Boolean {
    if (size < offset + magic.size) return false
    for (i in magic.indices) {
        if (this[offset + i].toInt() and 0xFF != magic[i]) return false
    }
    return true
}

/** 解码分派。 */
private fun decode(format: ImageFormat, bytes: ByteArray): RgbaImage? = when (format) {
    ImageFormat.PNG -> Png.decode(bytes)
    ImageFormat.JPEG -> Jpeg.decode(bytes)
    ImageFormat.GIF -> Gif.decode(bytes)
    ImageFormat.WEBP -> Webp.decode(bytes)
    ImageFormat.UNKNOWN -> null
}

/** 只读文件头分派。 */
private fun probe(format: ImageFormat, bytes: ByteArray): ImageInfo? = when (format) {
    ImageFormat.PNG -> Png.probe(bytes)
    ImageFormat.JPEG -> Jpeg.probe(bytes)
    ImageFormat.GIF -> Gif.probe(bytes)
    ImageFormat.WEBP -> Webp.probe(bytes)
    ImageFormat.UNKNOWN -> null
}

/** 把探测出来的色彩信息折成对外的 [ImageColorType]。 */
private fun ImageInfo.toColorType(): ImageColorType = when {
    isPalette -> ImageColorType.PALETTE
    isGray && hasAlpha -> ImageColorType.GRAY_ALPHA
    isGray -> ImageColorType.GRAY
    hasAlpha -> ImageColorType.RGBA
    else -> ImageColorType.RGB
}

/** 原值覆盖写一个像素（含 alpha），越界忽略。 */
private fun writePixel(image: RgbaImage, x: Int, y: Int, color: Int) {
    if (x < 0 || y < 0 || x >= image.width || y >= image.height) return
    val i = (y * image.width + x) * 4
    val buffer = image.pixels
    buffer[i] = (color ushr 16).toByte()
    buffer[i + 1] = (color ushr 8).toByte()
    buffer[i + 2] = color.toByte()
    buffer[i + 3] = (color ushr 24).toByte()
}

/** 把 [src] 以 source-over 合成到 [dst] 的 ([ox], [oy]) 处，超出画布的部分裁掉。 */
private fun composite(dst: RgbaImage, src: RgbaImage, ox: Int, oy: Int) {
    val db = dst.pixels
    val sb = src.pixels
    for (y in 0 until src.height) {
        val ty = oy + y
        if (ty < 0 || ty >= dst.height) continue
        for (x in 0 until src.width) {
            val tx = ox + x
            if (tx < 0 || tx >= dst.width) continue
            val si = (y * src.width + x) * 4
            val sa = sb[si + 3].toInt() and 0xFF
            if (sa == 0) continue
            val di = (ty * dst.width + tx) * 4
            if (sa == 255) {
                db[di] = sb[si]
                db[di + 1] = sb[si + 1]
                db[di + 2] = sb[si + 2]
                db[di + 3] = 0xFF.toByte()
                continue
            }
            val da = db[di + 3].toInt() and 0xFF
            val outA = sa + da * (255 - sa) / 255
            db[di] = channel(sb[si], sa, db[di], da, outA)
            db[di + 1] = channel(sb[si + 1], sa, db[di + 1], da, outA)
            db[di + 2] = channel(sb[si + 2], sa, db[di + 2], da, outA)
            db[di + 3] = outA.toByte()
        }
    }
}

/** source-over 的单个通道：`(sc*sa + dc*da*(255-sa)/255) / outA`，全程 0~255 定点。 */
private fun channel(s: Byte, sa: Int, d: Byte, da: Int, outA: Int): Byte {
    if (outA <= 0) return 0
    val sc = s.toInt() and 0xFF
    val dc = d.toInt() and 0xFF
    return ((sc * sa + dc * da * (255 - sa) / 255) / outA).coerceIn(0, 255).toByte()
}
