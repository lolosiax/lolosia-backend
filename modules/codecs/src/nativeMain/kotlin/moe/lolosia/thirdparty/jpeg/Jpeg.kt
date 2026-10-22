package moe.lolosia.thirdparty.jpeg

import kotlinx.cinterop.*
import moe.lolosia.thirdparty.ImageInfo
import moe.lolosia.thirdparty.RgbaImage
import platform.posix.memcpy

/**
 * libjpeg-turbo 门面：JPEG 字节流与 RGBA 像素互转。
 *
 * 只用 TurboJPEG 的 `tj3*` 接口 —— 经典 libjpeg 接口靠 `setjmp/longjmp` 处理坏图，
 * 其默认 `error_exit` 会直接 `exit()` 进程，从 Kotlin 调用无法拦截；`tj3*` 全程返回错误码。
 *
 * @author 洛洛希雅Lolosia
 */
object Jpeg {

    /** 有损编码的默认质量（1~100）。 */
    const val DEFAULT_QUALITY: Int = 80

    /** 只读文件头取尺寸与色彩类型；坏图返回 `null`。 */
    fun probe(bytes: ByteArray): ImageInfo? {
        if (bytes.isEmpty()) return null
        val handle = tj3Init(TJINIT.TJINIT_DECOMPRESS.value.toInt()) ?: return null
        try {
            val headerOk = bytes.usePinned { pinned ->
                tj3DecompressHeader(handle, pinned.addressOf(0).reinterpret(), bytes.size.convert()) == 0
            }
            if (!headerOk) return null
            val width = tj3Get(handle, TJPARAM.TJPARAM_JPEGWIDTH.value.toInt())
            val height = tj3Get(handle, TJPARAM.TJPARAM_JPEGHEIGHT.value.toInt())
            if (width <= 0 || height <= 0) return null
            val gray = tj3Get(handle, TJPARAM.TJPARAM_COLORSPACE.value.toInt()) == TJCS.TJCS_GRAY.value.toInt()
            return ImageInfo(width, height, isGray = gray)
        } finally {
            tj3Destroy(handle)
        }
    }

    /** 解码 JPEG 为 RGBA；坏图返回 `null`。 */
    fun decode(bytes: ByteArray): RgbaImage? {
        if (bytes.isEmpty()) return null
        val handle = tj3Init(TJINIT.TJINIT_DECOMPRESS.value.toInt()) ?: return null
        try {
            val headerOk = bytes.usePinned { pinned ->
                tj3DecompressHeader(handle, pinned.addressOf(0).reinterpret(), bytes.size.convert()) == 0
            }
            if (!headerOk) return null
            val width = tj3Get(handle, TJPARAM.TJPARAM_JPEGWIDTH.value.toInt())
            val height = tj3Get(handle, TJPARAM.TJPARAM_JPEGHEIGHT.value.toInt())
            if (width <= 0 || height <= 0) return null

            val pixels = ByteArray(width * height * 4)
            val decoded = bytes.usePinned { src ->
                pixels.usePinned { dst ->
                    tj3Decompress8(
                        handle, src.addressOf(0).reinterpret(), bytes.size.convert(),
                        dst.addressOf(0).reinterpret(), width * 4, TJPF_RGBA
                    ) == 0
                }
            }
            return if (decoded) RgbaImage(width, height, pixels) else null
        } finally {
            tj3Destroy(handle)
        }
    }

    /** 把 RGBA 编码成 JPEG；[quality] 取 1~100，失败返回 `null`。 */
    fun encode(image: RgbaImage, quality: Int = DEFAULT_QUALITY): ByteArray? {
        val handle = tj3Init(TJINIT.TJINIT_COMPRESS.value.toInt()) ?: return null
        try {
            tj3Set(handle, TJPARAM.TJPARAM_QUALITY.value.toInt(), quality)
            tj3Set(handle, TJPARAM.TJPARAM_SUBSAMP.value.toInt(), TJSAMP_420)
            return memScoped {
                val output = alloc<CPointerVar<UByteVar>>()
                val size = alloc<ULongVar>()
                val encoded = image.pixels.usePinned { src ->
                    tj3Compress8(
                        handle, src.addressOf(0).reinterpret(), image.width, image.width * 4, image.height,
                        TJPF_RGBA, output.ptr, size.ptr
                    ) == 0
                }
                val buffer = output.value
                if (!encoded || buffer == null) {
                    buffer?.let { tj3Free(it) }
                    return@memScoped null
                }
                try {
                    val out = ByteArray(size.value.toInt())
                    out.usePinned { memcpy(it.addressOf(0), buffer, size.value) }
                    out
                } finally {
                    tj3Free(buffer)
                }
            }
        } finally {
            tj3Destroy(handle)
        }
    }
}
