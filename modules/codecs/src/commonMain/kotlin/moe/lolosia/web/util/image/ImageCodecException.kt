package moe.lolosia.web.util.image

/**
 * 图像识别 / 解码 / 编码失败。
 *
 * 继承 [IllegalArgumentException]，调用方按「传进来的东西不对」处理即可。
 *
 * @author 洛洛希雅Lolosia
 */
class ImageCodecException(message: String) : IllegalArgumentException(message)
