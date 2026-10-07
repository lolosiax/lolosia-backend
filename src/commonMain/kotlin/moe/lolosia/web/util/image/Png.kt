package moe.lolosia.web.util.image

import kotlin.math.abs

/**
 * 极简 PNG 编码器（手写、零依赖、零 cinterop）。
 *
 * 为什么自研：Kotlin/Native 上没有 `javax.imageio`，而图形验证码只需要「填色 + 画线 + 点阵文字」，
 * 用不到通用图像库。这里只实现 PNG 的**必需子集**：8bit RGB、无隔行、zlib 用 **STORED** 块
 * （即不压缩，只做 zlib 容器 + 校验和）。
 *
 * 代价：不压缩，100x40 的图约 12KB（base64 后约 16KB）。与 `classpath.zip` 只支持 STORED 是同一个
 * 取舍——换来实现体量最小；要压到 1~2KB 得补 `platform.zlib` 或自写 DEFLATE，非当前需要。
 *
 * 参考：PNG 规范 RFC 2083、zlib RFC 1950（deflate 未压缩块 RFC 1951 §3.2.4）。
 *
 * @author 洛洛希雅Lolosia
 */
class RgbCanvas(val width: Int, val height: Int) {

    /** 逐像素的颜色，`0xRRGGBB`。 */
    private val pixels: IntArray = IntArray(width * height)

    /** 整幅填色（通常用来刷背景）。 */
    fun fill(color: Int) {
        pixels.fill(color and RGB_MASK)
    }

    /** 画一个像素；越界坐标直接忽略（干扰线会画出界）。 */
    fun set(x: Int, y: Int, color: Int) {
        if (x < 0 || y < 0 || x >= width || y >= height) return
        pixels[y * width + x] = color and RGB_MASK
    }

    fun fillRect(x: Int, y: Int, w: Int, h: Int, color: Int) {
        for (dy in 0 until h) for (dx in 0 until w) set(x + dx, y + dy, color)
    }

    /** Bresenham 直线；与 AWT 的 `drawLine` 一样**含两个端点**。 */
    fun drawLine(x0: Int, y0: Int, x1: Int, y1: Int, color: Int) {
        var x = x0
        var y = y0
        val dx = abs(x1 - x0)
        val dy = -abs(y1 - y0)
        val sx = if (x0 < x1) 1 else -1
        val sy = if (y0 < y1) 1 else -1
        var err = dx + dy
        while (true) {
            set(x, y, color)
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

    /** 编码成 PNG 字节（签名 + IHDR + IDAT + IEND）。 */
    fun toPng(): ByteArray {
        val raw = encodeScanlines()
        val idat = zlibStored(raw)
        val ihdr = ByteArray(13)
        writeInt(ihdr, 0, width)
        writeInt(ihdr, 4, height)
        ihdr[8] = 8 // bit depth
        ihdr[9] = 2 // color type：2 = truecolor RGB
        // 10 compression / 11 filter / 12 interlace 均为 0

        val out = ByteArray(SIGNATURE.size + (CHUNK_HEAD + 13 + 4) + (CHUNK_HEAD + idat.size + 4) + CHUNK_HEAD + 4)
        var o = 0
        SIGNATURE.copyInto(out, o)
        o += SIGNATURE.size
        o = writeChunk(out, o, "IHDR", ihdr, 0, ihdr.size)
        o = writeChunk(out, o, "IDAT", idat, 0, idat.size)
        writeChunk(out, o, "IEND", EMPTY, 0, 0)
        return out
    }

    /** 每条扫描线前置一个 filter 字节（0 = None），再接 3 字节/像素的 RGB。 */
    private fun encodeScanlines(): ByteArray {
        val stride = width * 3
        val raw = ByteArray(height * (1 + stride))
        var p = 0
        for (y in 0 until height) {
            raw[p++] = 0
            val base = y * width
            for (x in 0 until width) {
                val color = pixels[base + x]
                raw[p++] = (color ushr 16).toByte()
                raw[p++] = (color ushr 8).toByte()
                raw[p++] = color.toByte()
            }
        }
        return raw
    }

    companion object {
        /** 打包 `0xRRGGBB`。 */
        fun rgb(r: Int, g: Int, b: Int): Int = ((r and 0xFF) shl 16) or ((g and 0xFF) shl 8) or (b and 0xFF)

        private const val RGB_MASK: Int = 0xFFFFFF
        private const val CHUNK_HEAD: Int = 8
        private const val MAX_STORED_BLOCK: Int = 65535
        private const val ZLIB_HEADER: Int = 0x7801

        /** `89 50 4E 47 0D 0A 1A 0A`。 */
        private val SIGNATURE: ByteArray = byteArrayOf(
            0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
        )
        private val EMPTY: ByteArray = ByteArray(0)

        private const val CRC_POLY: Int = 0xEDB88320.toInt()
        private const val ADLER_MOD: Int = 65521

        // 注意声明顺序：对象属性按声明顺序初始化，CRC_TABLE 依赖 CRC_POLY
        private val CRC_TABLE: IntArray = IntArray(256) { n ->
            var c = n
            repeat(8) { c = if (c and 1 != 0) CRC_POLY xor (c ushr 1) else c ushr 1 }
            c
        }

        /** 写 length + type + data + crc32(type+data)，返回新的偏移。 */
        private fun writeChunk(out: ByteArray, offset: Int, type: String, data: ByteArray, from: Int, size: Int): Int {
            var o = offset
            writeInt(out, o, size)
            o += 4
            for (i in 0 until 4) out[o++] = type[i].code.toByte()
            val crcFrom = o - 4
            if (size > 0) {
                data.copyInto(out, o, from, from + size)
                o += size
            }
            writeInt(out, o, crc32(out, crcFrom, o))
            return o + 4
        }

        private fun writeInt(out: ByteArray, offset: Int, value: Int) {
            out[offset] = (value ushr 24).toByte()
            out[offset + 1] = (value ushr 16).toByte()
            out[offset + 2] = (value ushr 8).toByte()
            out[offset + 3] = value.toByte()
        }

        private fun crc32(data: ByteArray, from: Int, until: Int): Int {
            var c = -1
            for (i in from until until) c = CRC_TABLE[(c xor data[i].toInt()) and 0xFF] xor (c ushr 8)
            return c.inv()
        }

        /** zlib 容器：2 字节头 + N 个 STORED 块 + Adler-32。 */
        private fun zlibStored(raw: ByteArray): ByteArray {
            val blocks = (raw.size + MAX_STORED_BLOCK - 1) / MAX_STORED_BLOCK
            val out = ByteArray(2 + raw.size + blocks * 5 + 4)
            var o = 0
            // 0x7801 = CMF/FLG：deflate、32K 窗口、无预设字典，(0x7801 % 31 == 0) 合法
            out[o++] = (ZLIB_HEADER ushr 8).toByte()
            out[o++] = ZLIB_HEADER.toByte()

            var from = 0
            while (from < raw.size) {
                val len = minOf(MAX_STORED_BLOCK, raw.size - from)
                val last = from + len == raw.size
                out[o++] = if (last) 1 else 0 // BFINAL + BTYPE=00(STORED)
                out[o++] = len.toByte()
                out[o++] = (len ushr 8).toByte()
                val nlen = len.inv()
                out[o++] = nlen.toByte()
                out[o++] = (nlen ushr 8).toByte()
                raw.copyInto(out, o, from, from + len)
                o += len
                from += len
            }

            val adler = adler32(raw)
            writeInt(out, o, adler)
            return out
        }

        private fun adler32(data: ByteArray): Int {
            var a = 1
            var b = 0
            for (byte in data) {
                a = (a + (byte.toInt() and 0xFF)) % ADLER_MOD
                b = (b + a) % ADLER_MOD
            }
            return (b shl 16) or a
        }
    }
}
