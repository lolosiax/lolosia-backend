package moe.lolosia.thirdparty

/**
 * 图像头信息：只读文件头、不解码像素。
 *
 * 各 C 库门面的 `probe` 统一回这个，避免把 4 个库各自的头结构泄漏给调用方。
 *
 * @property width 像素宽
 * @property height 像素高
 * @property hasAlpha 位流里是否含 alpha 通道
 * @property isGray 是否单通道灰度
 * @property isPalette 是否调色板索引色
 * @author 洛洛希雅Lolosia
 */
class ImageInfo(
    val width: Int,
    val height: Int,
    val hasAlpha: Boolean = false,
    val isGray: Boolean = false,
    val isPalette: Boolean = false,
)
