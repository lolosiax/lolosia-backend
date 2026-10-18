package moe.lolosia.web.database.model

import io.github.smyrgeorge.sqlx4k.annotation.Id
import io.github.smyrgeorge.sqlx4k.annotation.Table
import kotlin.time.Instant

/**
 * `media_asset` 的**精简投影**：足迹地图只需要「在哪、什么时候、叫什么、什么评级」几个字段。
 *
 * 为什么另起一个实体而不是复用 [MediaAssetEntity]：足迹接口要**一次性读出全部带 GPS / 带拍摄
 * 时间的行**来聚类，而 [MediaAssetEntity] 带着 `metadata_json`（MEDIUMTEXT，每行几 KB）——
 * 几万行就是几十 MB 的无效往返。这里把列裁到最小，**只用于只读查询**（`select` 里显式列名，
 * 不写 `select *`）；其 `CrudRepository` 的写方法一律不用。
 *
 * [kind] / [deleted] 只是为了让 `@Query` 里的过滤列（`kind in (…)`、`deleted = 0`）能通过
 * sqlx4k 的「引用列必须存在于实体」校验，本身不参与业务。
 *
 * `@Table` 与主实体相同，`@Query` 的主表校验因此照常通过。
 *
 * @author 洛洛希雅Lolosia
 */
@Table("media_asset")
data class MediaGeoAssetEntity(
    @Id
    val id: Long = 0L,
    /** 相对浏览根的路径。 */
    val path: String,
    val name: String,
    /** `image` / `video` / …；只为满足查询校验，不参与业务。 */
    val kind: String = "other",
    val gpsLat: Double? = null,
    val gpsLon: Double? = null,
    /** 折算成 UTC 的拍摄时刻。 */
    val takenAt: Instant? = null,
    /** `unknown` / `safe` / `questionable` / `explicit`，前端据此决定缩略图要不要遮罩。 */
    val rating: String = RATING_UNKNOWN,
    /** 只为满足查询校验，不参与业务。 */
    val deleted: Boolean = false,
)
