package moe.lolosia.web.service.files

/**
 * 缩略图自动裁边参数：把图片四边的**近纯白装饰带**裁掉（手机水印白条 / VRChat 白框）。
 *
 * 判据是「一行（列）里近白像素占比够高、且非白像素不成片」：
 * - [white] 每个通道的下限（真白边是 255，阴天亮区多在 244 上下，取 246 一刀切开）；
 * - [minRatio] 行（列）内近白像素占比下限（容忍白边里的水印文字：文字行仍有 70%+ 白）；
 * - [maxRunRatio] 行（列）内非白像素**最长连续段**占比上限（文字是细笔画、成片内容会被挡下）；
 * - [maxTrimRatio] 单边最大裁剪比例（防止亮底照片被整片误裁）。
 *
 * 四边各自独立判定，都只从最外沿向内推进；判不出来就不裁。
 *
 * @author 洛洛希雅Lolosia
 */
class AutoCrop(
    val white: Int = 246,
    val minRatio: Double = 0.55,
    val maxRunRatio: Double = 0.15,
    val maxTrimRatio: Double = 0.35,
)
