package moe.lolosia.web.service.files

import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.roundToInt
import moe.lolosia.thirdparty.RgbaImage
import moe.lolosia.thirdparty.gif.Gif
import moe.lolosia.thirdparty.jpeg.Jpeg
import moe.lolosia.thirdparty.png.Png
import moe.lolosia.thirdparty.webp.Webp
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
 * 已知局限：不解析 EXIF 方向，手机竖拍照片可能方向不对（要修需在解码前读 EXIF Orientation 旋转）。
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
        for (size in targets) {
            val scaled = source.scaledToMax(size)
            val encoded = Webp.encode(scaled, quality.coerceIn(1, 100).toFloat()) ?: continue
            items.add(Thumbnail(size, encoded, scaled.width, scaled.height))
        }
        if (items.isEmpty()) return null
        return ThumbnailSet(source.width, source.height, items)
    }

    /** 按文件头识别格式后分派到对应门面解码。 */
    private fun decode(bytes: ByteArray): RgbaImage? = when (Image.typeOf(bytes)) {
        ImageFormat.PNG -> Png.decode(bytes)
        ImageFormat.JPEG -> Jpeg.decode(bytes)
        ImageFormat.GIF -> Gif.decode(bytes)
        ImageFormat.WEBP -> Webp.decode(bytes)
        ImageFormat.UNKNOWN -> null
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
}
