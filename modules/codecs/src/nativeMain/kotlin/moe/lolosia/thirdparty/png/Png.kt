package moe.lolosia.thirdparty.png

import kotlinx.cinterop.*
import moe.lolosia.thirdparty.ImageInfo
import moe.lolosia.thirdparty.RgbaImage
import platform.posix.memset

/**
 * libpng 门面：PNG 字节流与 RGBA 像素互转。
 *
 * 只用 `png_image` 简化接口 —— 它把 libpng 内部的 `setjmp/longjmp` 关在库内，出错只回错误码，
 * 从 Kotlin 调用不会因坏图而跳飞（经典 `png_read_*` 接口要求调用方自备 `jmp_buf`，不适合跨语言）。
 *
 * @author 洛洛希雅Lolosia
 */
object Png {

    /** `png_image` 结构体版本号，libpng 据此校验结构布局。 */
    private val IMAGE_VERSION: UInt = PNG_IMAGE_VERSION.convert()

    /** 每像素 4 字节 RGBA。 */
    private val FORMAT_RGBA: UInt = PNG_FORMAT_RGBA

    /** 只读文件头取尺寸与色彩类型；坏图返回 `null`。 */
    fun probe(bytes: ByteArray): ImageInfo? {
        if (bytes.isEmpty()) return null
        return bytes.usePinned { source ->
            memScoped {
                val image = blank(source.addressOf(0), bytes.size)
                if (image == null) return@memScoped null
                try {
                    // begin_read 会把**源文件**的色彩类型写进 image.format（解码前才被我们改成 RGBA）
                    val format = image.format
                    ImageInfo(
                        width = image.width.toInt(),
                        height = image.height.toInt(),
                        hasAlpha = format and PNG_FORMAT_FLAG_ALPHA != 0u,
                        isGray = format and PNG_FORMAT_FLAG_COLOR == 0u,
                        isPalette = format and PNG_FORMAT_FLAG_COLORMAP != 0u,
                    )
                } finally {
                    png_image_free(image.ptr)
                }
            }
        }
    }

    /** 解码 PNG 为 RGBA；坏图或格式不支持返回 `null`。 */
    fun decode(bytes: ByteArray): RgbaImage? {
        if (bytes.isEmpty()) return null
        // 整段 pin：libpng 只存下 `begin_read` 给的裸指针，真正的解码在 `finish_read` 里懒读，
        // 中途解 pin 会读到已释放的内存（像素全错但不报错）。
        return bytes.usePinned { source ->
            memScoped {
                val image = blank(source.addressOf(0), bytes.size) ?: return@memScoped null
                try {
                    image.format = FORMAT_RGBA
                    val width = image.width.toInt()
                    val height = image.height.toInt()
                    if (width <= 0 || height <= 0) return@memScoped null
                    val pixels = ByteArray(width * height * 4)
                    val read = pixels.usePinned { pinned ->
                        png_image_finish_read(image.ptr, null, pinned.addressOf(0), 0, null) != 0
                    }
                    if (read) RgbaImage(width, height, pixels) else null
                } finally {
                    png_image_free(image.ptr)
                }
            }
        }
    }

    /** 把 RGBA 编码成 PNG；失败返回 `null`。 */
    fun encode(image: RgbaImage): ByteArray? = memScoped {
        val png = alloc<png_image>()
        memset(png.ptr, 0, sizeOf<png_image>().convert())
        png.version = IMAGE_VERSION
        png.width = image.width.convert()
        png.height = image.height.convert()
        png.format = FORMAT_RGBA

        // 先问所需字节数：memory 传 null 时它照样走完整编码流程，只把长度写回 limit
        val limit = alloc<ULongVar>()
        limit.value = 0uL
        image.pixels.usePinned { pinned ->
            png_image_write_to_memory(png.ptr, null, limit.ptr, 0, pinned.addressOf(0), 0, null)
        }
        val needed = limit.value.toInt()
        if (needed <= 0) return@memScoped null

        val out = ByteArray(needed)
        limit.value = needed.convert()
        val written = image.pixels.usePinned { src ->
            out.usePinned { dst ->
                png_image_write_to_memory(png.ptr, dst.addressOf(0), limit.ptr, 0, src.addressOf(0), 0, null) != 0
            }
        }
        if (written) out else null
    }

    /** 在 [MemScope] 里摆一个 `png_image` 并读完文件头；读不出来返回 `null`。 */
    private fun MemScope.blank(source: CPointer<ByteVar>, size: Int): png_image? {
        val image = alloc<png_image>()
        memset(image.ptr, 0, sizeOf<png_image>().convert())
        image.version = IMAGE_VERSION
        if (png_image_begin_read_from_memory(image.ptr, source, size.convert()) == 0) return null
        return image
    }
}
