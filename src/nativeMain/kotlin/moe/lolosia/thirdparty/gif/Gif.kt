package moe.lolosia.thirdparty.gif

import kotlinx.cinterop.*
import moe.lolosia.thirdparty.ImageInfo
import moe.lolosia.thirdparty.RgbaImage
import platform.posix.memcpy

/**
 * giflib 门面：GIF 字节流解码为 RGBA（只取第一帧）。
 *
 * giflib 全部接口以错误码返回，无 `longjmp`。`DGifSlurp` 会自行处理隔行扫描，
 * `RasterBits` 出来就是常规行序，调用方不必再重排。
 *
 * 不做 GIF 编码：EGif\* 写图需要自建调色板与量化，本项目唯一的用处是转 WebP，用不到。
 *
 * @author 洛洛希雅Lolosia
 */
object Gif {

    /** Graphic Control Extension 的扩展功能码。 */
    private const val GRAPHICS_EXT_FUNC_CODE: Int = 0xF9

    /** 只读文件头取逻辑屏幕尺寸；坏图返回 `null`。 */
    fun probe(bytes: ByteArray): ImageInfo? {
        // 6 字节签名 + 7 字节逻辑屏幕描述符；不足则不是合法 GIF 头
        if (bytes.size < 13 || bytes[0] != 'G'.code.toByte() || bytes[1] != 'I'.code.toByte() || bytes[2] != 'F'.code.toByte()) return null
        val width = bytes.leShort(6)
        val height = bytes.leShort(8)
        if (width <= 0 || height <= 0) return null
        // GIF 恒为调色板索引色；第 10 字节 bit7 = 全局调色板标志
        return ImageInfo(width, height, isPalette = bytes[10].toInt() and 0x80 != 0)
    }

    /** 解码 GIF 首帧为 RGBA；坏图返回 `null`。 */
    fun decode(bytes: ByteArray): RgbaImage? {
        if (bytes.isEmpty()) return null
        return bytes.usePinned { pinned ->
            val ref = StableRef.create(GifSource(pinned))
            var file: CPointer<GifFileType>? = null
            try {
                memScoped {
                    val error = alloc<IntVar>()
                    file = DGifOpen(ref.asCPointer(), gifReader, error.ptr)
                }
                val gif = file ?: return@usePinned null
                if (DGifSlurp(gif) != GIF_OK) return@usePinned null
                render(gif.pointed)
            } finally {
                file?.let { DGifCloseFile(it, null) }
                ref.dispose()
            }
        }
    }

    /** 把首帧的调色板索引还原成 RGBA 像素；画布为逻辑屏幕尺寸，超出画布的部分裁掉。 */
    private fun render(gif: GifFileType): RgbaImage? {
        val width = gif.SWidth
        val height = gif.SHeight
        if (width <= 0 || height <= 0 || gif.ImageCount <= 0) return null
        val frame = gif.SavedImages?.pointed ?: return null
        val raster = frame.RasterBits ?: return null
        val colorMap = (frame.ImageDesc.ColorMap ?: gif.SColorMap)?.pointed ?: return null
        val colors = colorMap.Colors?.reinterpret<UByteVar>() ?: return null
        val colorCount = colorMap.ColorCount
        val transparent = transparentIndexOf(frame)

        val desc = frame.ImageDesc
        val pixels = ByteArray(width * height * 4)
        for (y in 0 until desc.Height) {
            for (x in 0 until desc.Width) {
                val index = raster[y * desc.Width + x].toInt()
                if (index == transparent || index >= colorCount) continue
                val target = ((desc.Top + y) * width + desc.Left + x) * 4
                if (target < 0 || target + 3 >= pixels.size) continue
                val rgb = index * 3
                pixels[target] = colors[rgb].toByte()
                pixels[target + 1] = colors[rgb + 1].toByte()
                pixels[target + 2] = colors[rgb + 2].toByte()
                pixels[target + 3] = 0xFF.toByte()
            }
        }
        return RgbaImage(width, height, pixels)
    }

    /** 从帧的扩展块里找 GCE 的透明色索引；没有透明色返回 -1。 */
    private fun transparentIndexOf(frame: SavedImage): Int {
        val blocks = frame.ExtensionBlocks ?: return -1
        for (i in 0 until frame.ExtensionBlockCount) {
            val block = blocks[i]
            if (block.Function != GRAPHICS_EXT_FUNC_CODE) continue
            val data = block.Bytes ?: continue
            if (block.ByteCount < 4 || (data[0].toInt() and 0x01) == 0) continue
            return data[3].toInt()
        }
        return -1
    }
}

/** 供 giflib 读回调使用的字节流游标；`pinned` 必须在整个解码期保持有效。 */
private class GifSource(val pinned: Pinned<ByteArray>) {
    var offset: Int = 0

    val size: Int get() = pinned.get().size
}

/** 小端读 16 位无符号整数。 */
private fun ByteArray.leShort(offset: Int): Int =
    (this[offset].toInt() and 0xFF) or ((this[offset + 1].toInt() and 0xFF) shl 8)

/** 从 giflib 的 `GifFileType.UserData` 取回调用方放的字节流游标，逐块喂给它。 */
private val gifReader: InputFunc = staticCFunction { file, buffer, length ->
    val userData = file?.pointed?.UserData
    if (userData == null || buffer == null || length <= 0) {
        0
    } else {
        val source = userData.asStableRef<GifSource>().get()
        val count = minOf(length, source.size - source.offset)
        if (count <= 0) {
            0
        } else {
            memcpy(buffer, source.pinned.addressOf(source.offset), count.convert())
            source.offset += count
            count
        }
    }
}
