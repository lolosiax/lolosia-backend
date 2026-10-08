package moe.lolosia.web.service.files

/**
 * 一个档位的缩略图。
 *
 * @property size 档位（最长边像素）；[FULL_SIZE_SENTINEL] 表示「原始分辨率」而不是某个档位
 * @property bytes 编码后的 WebP 字节
 * @property width 缩略图像素宽
 * @property height 缩略图像素高
 * @author 洛洛希雅Lolosia
 */
class Thumbnail(val size: Int, val bytes: ByteArray, val width: Int, val height: Int)

/** 结果里「原始分辨率」那一项的 `size` 值（它不是档位，不会被写进档位缓存）。 */
const val FULL_SIZE_SENTINEL: Int = 0

/**
 * 一张源图生成出的全部档位。
 *
 * @property sourceWidth 源图像素宽
 * @property sourceHeight 源图像素高
 * @property items 各档位结果（可能少于请求的档位：某档编码失败就不含它）
 * @property sample 8×8 采样：每像素 4 位十六进制（RGB565 大端），共 256 字符；解不出为 `null`
 * @author 洛洛希雅Lolosia
 */
class ThumbnailSet(
    val sourceWidth: Int,
    val sourceHeight: Int,
    val items: List<Thumbnail>,
    val sample: String? = null,
)

/**
 * 缩略图生成器：解码一次 → 按各档位等比缩放（原图只解一次，避免同一张照片反复解 55MP）→ 编码 WebP。
 *
 * 依赖内嵌的 C 图像库（libpng / libjpeg-turbo / libwebp / giflib）与 `RgbaImage`，这些类型只在
 * native 源集，因此实现只写在 nativeMain；commonMain 只拿得到本声明。
 *
 * @author 洛洛希雅Lolosia
 */
expect object ThumbnailGenerator {

    /**
     * 把 [bytes] 解码后按 [sizes] 各缩一版，编码为有损 WebP（[quality] 取 1~100）。
     *
     * 格式不支持或解码失败返回 `null`；单档编码失败则该档位不出现在结果里，不抛异常。
     */
    fun generate(bytes: ByteArray, sizes: List<Int>, quality: Int): ThumbnailSet?

    /**
     * 把 [bytes] 解码后按**原始分辨率**换编码为有损 WebP（[quality] 取 1~100），不缩放。
     *
     * 全屏查看器默认要的是这一版：档位缩略图最大只有 1024，屏幕一大就被放大糊掉；
     * 原始分辨率版本像素与原图一致、体积小得多。返回的 [ThumbnailSet.items] 恒为一项，
     * 其 `size` 记 0（表示「原分辨率」而不是某个档位），尺寸取自源图。
     *
     * 只在用户打开某张图时按需调用一次，结果落盘缓存，不进后台队列。
     */
    fun encodeFull(bytes: ByteArray, quality: Int): ThumbnailSet?

    /**
     * 只取 8×8 RGB565 采样（hex，256 字符），不编码缩略图；解不出返回 `null`。
     *
     * 专供**回填**：老的缓存里已经有缩略图、只缺采样时，直接解那一档小图（几十 KB）拿到采样，
     * 比为了 64 个像素再把原图整个读一遍、解一遍便宜几个数量级。
     */
    fun sample(bytes: ByteArray): String?
}
