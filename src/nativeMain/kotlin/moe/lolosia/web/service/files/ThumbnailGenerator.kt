package moe.lolosia.web.service.files

import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.roundToInt
import moe.lolosia.thirdparty.RgbaImage
import moe.lolosia.thirdparty.gif.Gif
import moe.lolosia.thirdparty.jpeg.Jpeg
import moe.lolosia.thirdparty.png.Png
import moe.lolosia.thirdparty.webp.Webp
import moe.lolosia.web.util.exif.ExifReader
import moe.lolosia.web.util.image.Image
import moe.lolosia.web.util.image.ImageFormat

/**
 * 缩略图生成（native 实现）：用 thirdparty 门面拿 `RgbaImage`，自己缩放后交给 libwebp 编码。
 *
 * 之所以不走 `Image`：它的 `pixels` 是私有的、`toByteArray` 也不带质量参数，做不了「先缩后编」。
 * 这里只借用 `Image.typeOf` 做文件头识别，像素处理全部用门面 API。
 *
 * 缩放用**面积平均（box filter）**手写实现：库里没有任何缩放 API，而面积平均在缩小时质量稳定、
 * 只需一趟遍历，适合缩略图场景。
 *
 * EXIF Orientation 在解码后立即摆正（见 [reoriented]）：解码器给的是传感器原始朝向，而重新编码
 * 出的 WebP 不带 EXIF，不转正的话手机竖拍的照片会躺倒 90°。
 *
 * @author 洛洛希雅Lolosia
 */
actual object ThumbnailGenerator {

    actual fun generate(bytes: ByteArray, sizes: List<Int>, quality: Int): ThumbnailSet? {
        if (bytes.isEmpty()) return null
        val targets = sizes.filter { it > 0 }.distinct().sorted()
        if (targets.isEmpty()) return null
        val source = decode(bytes) ?: return null

        val items = ArrayList<Thumbnail>(targets.size)
        var sample: String? = null
        for (size in targets) {
            val scaled = source.scaledToMax(size)
            // 采样取自最小档缩略图：这一路的面积平均本来就要跑，再回到原始分辨率上重跑一趟
            // （55MP 就是 5500 万次内层循环）只为得到 64 个像素纯属浪费。
            if (sample == null) sample = scaled.sampleRgb565()
            val encoded = Webp.encode(scaled, quality.coerceIn(1, 100).toFloat()) ?: continue
            items.add(Thumbnail(size, encoded, scaled.width, scaled.height))
        }
        if (items.isEmpty()) return null
        return ThumbnailSet(source.width, source.height, items, sample)
    }

    actual fun encodeFull(bytes: ByteArray, quality: Int): ThumbnailSet? {
        if (bytes.isEmpty()) return null
        val source = decode(bytes) ?: return null
        val encoded = Webp.encode(source, quality.coerceIn(1, 100).toFloat()) ?: return null
        val item = Thumbnail(FULL_SIZE_SENTINEL, encoded, source.width, source.height)
        return ThumbnailSet(source.width, source.height, listOf(item))
    }

    actual fun sample(bytes: ByteArray): String? = decode(bytes)?.sampleRgb565()

    /** 按文件头识别格式后分派到对应门面解码，并把 EXIF 方向摆正。 */
    private fun decode(bytes: ByteArray): RgbaImage? {
        val image = when (Image.typeOf(bytes)) {
            ImageFormat.PNG -> Png.decode(bytes)
            ImageFormat.JPEG -> Jpeg.decode(bytes)
            ImageFormat.GIF -> Gif.decode(bytes)
            ImageFormat.WEBP -> Webp.decode(bytes)
            ImageFormat.UNKNOWN -> null
        } ?: return null
        return image.reoriented(ExifReader.read(bytes)?.orientation)
    }

    /**
     * 按 EXIF Orientation 摆正像素。
     *
     * 解码器给的是**传感器原始朝向**：手机横放竖拍时像素是 6000×4000 的横向图，方向只写在 EXIF 里，
     * 查看器（浏览器看原图）会自己转正。缩略图要重新编码成 WebP，而这一版 WebP 不带 EXIF，
     * 所以必须在这里把像素转过来，否则前端拿到的就是躺倒 90° 的图。
     *
     * 5~8 是「带旋转的镜像 / 旋转」，宽高与原图相反；值非法（`null` / 越界）或本身正立（1）时原样返回。
     */
    private fun RgbaImage.reoriented(orientation: Int?): RgbaImage {
        if (orientation == null || orientation <= 1 || orientation > 8) return this
        val swapped = orientation >= 5
        val outWidth = if (swapped) height else width
        val outHeight = if (swapped) width else height
        val src = pixels
        val out = ByteArray(outWidth * outHeight * 4)
        for (y in 0 until outHeight) {
            for (x in 0 until outWidth) {
                // 目标像素 (x, y) 落在源图上的哪一点：各方向互为逆映射
                val sx: Int
                val sy: Int
                when (orientation) {
                    2 -> { sx = width - 1 - x; sy = y }
                    3 -> { sx = width - 1 - x; sy = height - 1 - y }
                    4 -> { sx = x; sy = height - 1 - y }
                    5 -> { sx = y; sy = x }
                    6 -> { sx = y; sy = height - 1 - x }
                    7 -> { sx = width - 1 - y; sy = height - 1 - x }
                    else -> { sx = width - 1 - y; sy = x }
                }
                val s = (sy * width + sx) * 4
                val o = (y * outWidth + x) * 4
                out[o] = src[s]
                out[o + 1] = src[s + 1]
                out[o + 2] = src[s + 2]
                out[o + 3] = src[s + 3]
            }
        }
        return RgbaImage(outWidth, outHeight, out)
    }

    /** 等比缩到最长边不超过 [maxEdge]；本来就够小则原样返回（不放大）。 */
    private fun RgbaImage.scaledToMax(maxEdge: Int): RgbaImage {
        val longest = max(width, height)
        if (longest <= maxEdge) return this
        val ratio = maxEdge.toDouble() / longest
        val targetWidth = max(1, (width * ratio).roundToInt())
        val targetHeight = max(1, (height * ratio).roundToInt())
        return boxResample(targetWidth, targetHeight)
    }

    /**
     * 面积平均重采样。
     *
     * 每个目标像素覆盖源图上的一个矩形（浮点边界向上取整，保证不丢行），把矩形内像素按
     * **alpha 预乘**后求均值：不预乘的话，透明像素的 RGB（通常是 0）会把边缘拉黑。
     */
    private fun RgbaImage.boxResample(targetWidth: Int, targetHeight: Int): RgbaImage {
        val out = ByteArray(targetWidth * targetHeight * 4)
        val src = pixels
        val stepX = width.toDouble() / targetWidth
        val stepY = height.toDouble() / targetHeight
        for (ty in 0 until targetHeight) {
            val y0 = (ty * stepY).toInt()
            val y1 = max(y0 + 1, minOf(height, ceil((ty + 1) * stepY).toInt()))
            for (tx in 0 until targetWidth) {
                val x0 = (tx * stepX).toInt()
                val x1 = max(x0 + 1, minOf(width, ceil((tx + 1) * stepX).toInt()))
                var sumR = 0L
                var sumG = 0L
                var sumB = 0L
                var sumA = 0L
                var count = 0
                for (y in y0 until y1) {
                    var i = (y * width + x0) * 4
                    for (x in x0 until x1) {
                        val alpha = src[i + 3].toInt() and 0xFF
                        sumR += (src[i].toInt() and 0xFF) * alpha
                        sumG += (src[i + 1].toInt() and 0xFF) * alpha
                        sumB += (src[i + 2].toInt() and 0xFF) * alpha
                        sumA += alpha
                        count++
                        i += 4
                    }
                }
                val o = (ty * targetWidth + tx) * 4
                if (sumA > 0) {
                    out[o] = (sumR / sumA).toInt().coerceIn(0, 255).toByte()
                    out[o + 1] = (sumG / sumA).toInt().coerceIn(0, 255).toByte()
                    out[o + 2] = (sumB / sumA).toInt().coerceIn(0, 255).toByte()
                }
                out[o + 3] = if (count == 0) 0 else (sumA / count).toInt().coerceIn(0, 255).toByte()
            }
        }
        return RgbaImage(targetWidth, targetHeight, out)
    }

    /**
     * 8×8 RGB565 采样，十六进制小写、每像素 4 位（大端），共 256 字符。
     *
     * 复用同一套面积平均缩到 8×8，再按 R 取高 5 位 / G 取高 6 位 / B 取高 5 位量化，
     * 8×8×2 = 128 字节。前端拿它当加载前的占位、以及敏感内容的遮罩底图。
     */
    private fun RgbaImage.sampleRgb565(): String {
        val small = boxResample(SAMPLE_SIZE, SAMPLE_SIZE)
        val hex = CharArray(SAMPLE_SIZE * SAMPLE_SIZE * 4)
        var at = 0
        for (i in 0 until SAMPLE_SIZE * SAMPLE_SIZE) {
            val r = small.pixels[i * 4].toInt() and 0xFF
            val g = small.pixels[i * 4 + 1].toInt() and 0xFF
            val b = small.pixels[i * 4 + 2].toInt() and 0xFF
            val value = ((r shr 3) shl 11) or ((g shr 2) shl 5) or (b shr 3)
            hex[at++] = HEX[(value shr 12) and 0xF]
            hex[at++] = HEX[(value shr 8) and 0xF]
            hex[at++] = HEX[(value shr 4) and 0xF]
            hex[at++] = HEX[value and 0xF]
        }
        return hex.concatToString()
    }

    /** 采样边长（像素）。 */
    private const val SAMPLE_SIZE: Int = 8

    private const val HEX: String = "0123456789abcdef"
}
