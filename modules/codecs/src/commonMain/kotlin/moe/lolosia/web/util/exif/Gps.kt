package moe.lolosia.web.util.exif

/**
 * GPS 坐标（已折算成十进制度）。
 *
 * 只在用户拥有 `lolosia.media.location` 时才下发（见 `doc/media-index-design.md` §6.4）——
 * 家庭照片的 GPS 等于住址，默认不该给所有人看。
 *
 * @author 洛洛希雅Lolosia
 */
data class Gps(
    val latitude: Double,
    val longitude: Double,
)
