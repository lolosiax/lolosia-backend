package moe.lolosia.thirdparty.webp

import kotlinx.cinterop.*
import moe.lolosia.thirdparty.ImageInfo
import moe.lolosia.thirdparty.RgbaImage
import platform.posix.memcpy

/**
 * libwebp 门面：RGBA 像素与 WebP 字节流互转。
 *
 * 失败一律返回 `null`（libwebp 出错时报 0 / 空指针），不抛异常。
 *
 * @author 洛洛希雅Lolosia
 */
object Webp {

    /** 有损编码的默认质量（0~100）。 */
    const val DEFAULT_QUALITY: Float = 80f

    /** 只读文件头取尺寸与 alpha 标记；坏图返回 `null`。 */
    fun probe(bytes: ByteArray): ImageInfo? {
        if (bytes.isEmpty()) return null
        return memScoped {
            val features = alloc<WebPBitstreamFeatures>()
            val status = bytes.usePinned { pinned ->
                WebPGetFeatures(pinned.addressOf(0).reinterpret(), bytes.size.convert(), features.ptr)
            }
            if (status != VP8_STATUS_OK) return@memScoped null
            val width = features.width
            val height = features.height
            if (width <= 0 || height <= 0) return@memScoped null
            ImageInfo(width, height, hasAlpha = features.has_alpha != 0)
        }
    }

    /** 有损编码；[quality] 取 0~100，越大越清晰、体积越大。 */
    fun encode(image: RgbaImage, quality: Float = DEFAULT_QUALITY): ByteArray? = memScoped {
        val output = alloc<CPointerVar<UByteVar>>()
        val size = image.pixels.usePinned { pinned ->
            WebPEncodeRGBA(pinned.addressOf(0).reinterpret(), image.width, image.height, image.width * 4, quality, output.ptr)
        }
        output.takeOrFree(size)
    }

    /** 无损编码（体积通常明显大于有损，适合图标、截图）。 */
    fun encodeLossless(image: RgbaImage): ByteArray? = memScoped {
        val output = alloc<CPointerVar<UByteVar>>()
        val size = image.pixels.usePinned { pinned ->
            WebPEncodeLosslessRGBA(pinned.addressOf(0).reinterpret(), image.width, image.height, image.width * 4, output.ptr)
        }
        output.takeOrFree(size)
    }

    /** 解码 WebP（动图取第一帧）为 RGBA。 */
    fun decode(bytes: ByteArray): RgbaImage? {
        if (bytes.isEmpty()) return null
        return memScoped {
            val width = alloc<IntVar>()
            val height = alloc<IntVar>()
            val rgba = bytes.usePinned { pinned ->
                WebPDecodeRGBA(pinned.addressOf(0).reinterpret(), bytes.size.convert(), width.ptr, height.ptr)
            } ?: return@memScoped null
            try {
                val w = width.value
                val h = height.value
                if (w <= 0 || h <= 0) return@memScoped null
                RgbaImage(w, h, rgba.copyBytes((w * h * 4).convert()))
            } finally {
                WebPFree(rgba)
            }
        }
    }
}

/** 把 libwebp 用 WebPMalloc 分配的缓冲拷成 ByteArray 后归还；[size] 为 0 表示编码失败。 */
private fun CPointerVar<UByteVar>.takeOrFree(size: ULong): ByteArray? {
    val ptr = value ?: return null
    try {
        return if (size == 0uL) null else ptr.copyBytes(size)
    } finally {
        WebPFree(ptr)
    }
}

/** 从 C 缓冲拷出 [count] 字节。 */
private fun CPointer<UByteVar>.copyBytes(count: ULong): ByteArray {
    val out = ByteArray(count.toInt())
    out.usePinned { memcpy(it.addressOf(0), this, count) }
    return out
}
