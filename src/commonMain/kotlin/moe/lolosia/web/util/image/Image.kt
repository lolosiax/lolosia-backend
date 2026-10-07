package moe.lolosia.web.util.image

/**
 * 与平台无关的图像对象：构造时按文件头自动识别格式并解码，内存里恒为 RGBA。
 *
 * 读写能力由内嵌的 C 静态库提供（libpng / libjpeg-turbo / libwebp / giflib），
 * 因此实现只在 native 源集；commonMain 只能拿到本 `expect` 声明。
 *
 * 颜色一律用 `Int` 的 `0xAARRGGBB` 表示；[fill] / [drawPoint] / [drawLine] / [drawRect]
 * 按原值**直接覆盖**（含 alpha），[draw] 做 source-over 合成。
 *
 * 空白画布（[Image] 的尺寸构造）没有源文件，`format` 为 [ImageFormat.UNKNOWN]、`colorType` 为
 * [ImageColorType.RGBA]。
 *
 * @author 洛洛希雅Lolosia
 */
expect class Image {

    /** 从编码字节流解码；识别不出格式或解码失败抛 [ImageCodecException]。 */
    constructor(bytes: ByteArray)

    /** 新建一张全透明的空白画布，用于从零绘制；尺寸非正抛 [IllegalArgumentException]。 */
    constructor(width: Int, height: Int)

    val width: Int

    val height: Int

    val size: ImageSize

    /** 源文件格式（构造时的识别结果）。 */
    val format: ImageFormat

    /** 源文件色彩类型。 */
    val colorType: ImageColorType

    /** 按 [format] 编码；不支持的目标格式（如 GIF）抛 [ImageCodecException]。 */
    fun toByteArray(format: ImageFormat = ImageFormat.PNG): ByteArray

    /** 用 [color] 涂满整张画布。 */
    fun fill(color: Int)

    /** 在 ([x], [y]) 写一个像素；越界忽略。 */
    fun drawPoint(x: Int, y: Int, color: Int)

    /** 画一条直线（Bresenham）；端点越界部分裁掉。 */
    fun drawLine(x0: Int, y0: Int, x1: Int, y1: Int, color: Int)

    /** 画矩形；[filled] 为 `true` 时填充，否则描边。 */
    fun drawRect(x: Int, y: Int, width: Int, height: Int, color: Int, filled: Boolean = false)

    /** 把 [source] 以 source-over 合成到 ([x], [y]) 处。 */
    fun draw(source: Image, x: Int, y: Int)

    companion object {

        /** 只按文件头识别格式，不解码像素。 */
        fun typeOf(bytes: ByteArray): ImageFormat

        /** 只读文件头取尺寸，不解码像素；识别不出返回 `null`。 */
        fun sizeOf(bytes: ByteArray): ImageSize?

        /** 只读文件头取色彩类型，不解码像素；识别不出返回 `null`。 */
        fun colorTypeOf(bytes: ByteArray): ImageColorType?
    }
}
