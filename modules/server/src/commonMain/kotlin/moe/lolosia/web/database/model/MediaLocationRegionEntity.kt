package moe.lolosia.web.database.model

import io.github.smyrgeorge.sqlx4k.annotation.Id
import io.github.smyrgeorge.sqlx4k.annotation.Table
import kotlin.time.Instant

/**
 * `media_location_region`：**足迹地图**里人工命名的一块地理范围（圆形）。
 *
 * 一个圈 = 中心点 + 半径（米）+ 名字；「这个区域里有多少张图」是现算的（几何包含判定），
 * 不落库。与 `media_asset` 没有外键：圈是独立的标注层，删图不影响圈、删圈不影响图。
 *
 * @author 洛洛希雅Lolosia
 */
@Table("media_location_region")
data class MediaLocationRegionEntity(
    @Id
    val id: Long = 0L,
    /** 区域名，如「江汉路步行街」。 */
    val name: String,
    val centerLat: Double,
    val centerLon: Double,
    /** 半径（米）。 */
    val radiusM: Double = 0.0,
    /** 展示色，如 `#ff6600`；空则由前端取默认色。 */
    val color: String? = null,
    /** 一句话备注。 */
    val description: String? = null,
    val createdAt: Instant? = null,
    val createdBy: String? = null,
    val updatedAt: Instant? = null,
    val updatedBy: String? = null,
    val deleted: Boolean = false,
)
