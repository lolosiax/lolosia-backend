package moe.lolosia.web.database.repository

import io.github.smyrgeorge.sqlx4k.CrudRepository
import io.github.smyrgeorge.sqlx4k.QueryExecutor
import io.github.smyrgeorge.sqlx4k.annotation.Query
import io.github.smyrgeorge.sqlx4k.annotation.Repository
import moe.lolosia.web.database.model.MediaAssetEntity
import moe.lolosia.web.util.router.Injectable
import kotlin.time.Instant

/**
 * `media_asset` 的仓库。
 *
 * 全部是**单语句**（`insert …; select …` 那种多语句脚本在 MariaDB 预处理下会 1064，
 * 所以不用 `CrudRepository` 自带的 `insert` / `update`）；批量场景在服务层循环单条 + 一个事务。
 *
 * 写入按用途拆成几个窄 update，而不是一个「全字段 update」：探测只写探测列、评级只写评级列，
 * 互不覆盖（`null` = 不修改的语义在 SQL 层面天然成立）。
 *
 * @author 洛洛希雅Lolosia
 */
@Repository
@Injectable
interface MediaAssetRepository : CrudRepository<MediaAssetEntity> {

    @Query("select * from media_asset where path = :path and deleted = 0")
    suspend fun findOneByPath(context: QueryExecutor, path: String): Result<MediaAssetEntity?>

    @Query("select * from media_asset where id = :id and deleted = 0")
    suspend fun findOneById(context: QueryExecutor, id: Long): Result<MediaAssetEntity?>

    /**
     * 连**软删行**一起查：文件消失被软删、过一阵子又回来了，要靠它把那一行认回来
     * （标签与评级原样复活，见 `doc/media-index-design.md` §5.3）。
     */
    @Query("select * from media_asset where path = :path order by deleted asc, id desc limit 1")
    suspend fun findOneByPathIncludingDeleted(context: QueryExecutor, path: String): Result<MediaAssetEntity?>

    /** 内容身份查重：只在冲突判定时用，命中一条即返回。 */
    @Query("select * from media_asset where quick_id = :quickId and deleted = 0 limit 1")
    suspend fun findOneByQuickId(context: QueryExecutor, quickId: String): Result<MediaAssetEntity?>

    @Query("select * from media_asset where content_sha256 = :sha256 and deleted = 0 limit 1")
    suspend fun findOneByContentSha256(context: QueryExecutor, sha256: String): Result<MediaAssetEntity?>

    /** 列一个目录的摘要（评级/尺寸/时间）。这是「不写 `IN` 也能批量取回」的关键（§4.8-1）。 */
    @Query("select * from media_asset where dir_path = :dirPath and deleted = 0")
    suspend fun findAllByDirPath(context: QueryExecutor, dirPath: String): Result<List<MediaAssetEntity>>

    /** 取一批待外发分析的资产（显式作业的游标，§8.2）。 */
    @Query("select * from media_asset where analyze_state = :analyzeState and deleted = 0 order by id asc limit :limit")
    suspend fun findAllByAnalyzeState(
        context: QueryExecutor,
        analyzeState: String,
        limit: Int,
    ): Result<List<MediaAssetEntity>>

    @Query("select count(*) from media_asset where analyze_state = :analyzeState and deleted = 0")
    suspend fun countByAnalyzeState(context: QueryExecutor, analyzeState: String): Result<Long>

    /** 新发现的文件先落一行最小记录：元数据、评级、分析状态都由后续的窄 update 补。 */
    @Query(
        "insert into media_asset(path, dir_path, name, kind, size, mtime, quick_id, rating, analysis_skip, " +
            "created_at, created_by, updated_at, updated_by, deleted) " +
            "values (:path, :dirPath, :name, :kind, :size, :mtime, :quickId, :rating, :analysisSkip, " +
            ":createdAt, :createdBy, :updatedAt, :updatedBy, 0)"
    )
    suspend fun executeInsert(
        context: QueryExecutor,
        path: String,
        dirPath: String,
        name: String,
        kind: String,
        size: Long,
        mtime: Long,
        quickId: String?,
        rating: String,
        analysisSkip: Boolean,
        createdAt: Instant,
        createdBy: String?,
        updatedAt: Instant,
        updatedBy: String?,
    ): Result<Long>

    /** 位置与新鲜度（改名 / 搬目录 / 文件被改动）。 */
    @Query(
        "update media_asset set path = :path, dir_path = :dirPath, name = :name, size = :size, mtime = :mtime, " +
            "quick_id = :quickId, updated_at = :updatedAt, updated_by = :updatedBy where id = :id"
    )
    suspend fun executeUpdateLocationById(
        context: QueryExecutor,
        id: Long,
        path: String,
        dirPath: String,
        name: String,
        size: Long,
        mtime: Long,
        quickId: String?,
        updatedAt: Instant,
        updatedBy: String?,
    ): Result<Long>

    /** 探测结果（EXIF / 容器头 / 尺寸）。 */
    @Query(
        "update media_asset set kind = :kind, width = :width, height = :height, duration_ms = :durationMs, " +
            "taken_at = :takenAt, taken_at_local = :takenAtLocal, taken_at_offset_min = :takenAtOffsetMin, " +
            "camera_make = :cameraMake, camera_model = :cameraModel, lens = :lens, aperture = :aperture, " +
            "exposure_time = :exposureTime, iso = :iso, focal_length = :focalLength, gps_lat = :gpsLat, " +
            "gps_lon = :gpsLon, orientation = :orientation, metadata_json = :metadataJson, probe_at = :probeAt, " +
            "probe_version = :probeVersion, updated_at = :updatedAt, updated_by = :updatedBy where id = :id"
    )
    suspend fun executeUpdateProbeById(
        context: QueryExecutor,
        id: Long,
        kind: String,
        width: Int?,
        height: Int?,
        durationMs: Long?,
        takenAt: Instant?,
        takenAtLocal: String?,
        takenAtOffsetMin: Int?,
        cameraMake: String?,
        cameraModel: String?,
        lens: String?,
        aperture: Double?,
        exposureTime: String?,
        iso: Int?,
        focalLength: Double?,
        gpsLat: Double?,
        gpsLon: Double?,
        orientation: Int?,
        metadataJson: String?,
        probeAt: Instant,
        probeVersion: Int,
        updatedAt: Instant,
        updatedBy: String?,
    ): Result<Long>

    /**
     * 8×8 采样（缩略图生成时顺带产出）。按 **path** 定位：写它的是缩略图服务，手上只有路径。
     *
     * 参数名不能叫 `sample` —— 它在 SQL 语法里是保留字（`TABLESAMPLE`），KSP 的语句校验会拒。
     */
    @Query("update media_asset set sample_rgb565 = :sampleHex, updated_at = :updatedAt where path = :path and deleted = 0")
    suspend fun executeUpdateSampleByPath(
        context: QueryExecutor,
        path: String,
        sampleHex: String,
        updatedAt: Instant,
    ): Result<Long>

    /** 人工 / 规则评级 + 外发闸门（§7.2 / §10.2）。 */
    @Query(
        "update media_asset set rating = :rating, rating_source = :ratingSource, analysis_skip = :analysisSkip, " +
            "updated_at = :updatedAt, updated_by = :updatedBy where id = :id"
    )
    suspend fun executeUpdateRatingById(
        context: QueryExecutor,
        id: Long,
        rating: String,
        ratingSource: String?,
        analysisSkip: Boolean,
        updatedAt: Instant,
        updatedBy: String?,
    ): Result<Long>

    /** 外发分析状态（只有显式作业会调它，§8.2）。 */
    @Query(
        "update media_asset set analyze_state = :analyzeState, analyzed_at = :analyzedAt, " +
            "analyze_provider = :analyzeProvider, updated_at = :updatedAt where id = :id"
    )
    suspend fun executeUpdateAnalyzeById(
        context: QueryExecutor,
        id: Long,
        analyzeState: String,
        analyzedAt: Instant?,
        analyzeProvider: String?,
        updatedAt: Instant,
    ): Result<Long>

    @Query(
        "update media_asset set caption = :caption, caption_source = :captionSource, " +
            "updated_at = :updatedAt, updated_by = :updatedBy where id = :id"
    )
    suspend fun executeUpdateCaptionById(
        context: QueryExecutor,
        id: Long,
        caption: String?,
        captionSource: String?,
        updatedAt: Instant,
        updatedBy: String?,
    ): Result<Long>

    /** 全量哈希补齐（异步）。 */
    @Query("update media_asset set content_sha256 = :sha256, updated_at = :updatedAt where id = :id")
    suspend fun executeUpdateContentSha256ById(
        context: QueryExecutor,
        id: Long,
        sha256: String,
        updatedAt: Instant,
    ): Result<Long>

    /** 记「本次扫描没见到」；连续多次才软删（§5.3）。 */
    @Query("update media_asset set missing_since = :missingSince, updated_at = :updatedAt where id = :id")
    suspend fun executeUpdateMissingSinceById(
        context: QueryExecutor,
        id: Long,
        missingSince: Instant?,
        updatedAt: Instant,
    ): Result<Long>

    /** 内容重复时把非代表行挂到代表行上（§5.2）。 */
    @Query("update media_asset set merged_into = :mergedInto, deleted = 1, updated_at = :updatedAt where id = :id")
    suspend fun executeMergeIntoById(
        context: QueryExecutor,
        id: Long,
        mergedInto: Long,
        updatedAt: Instant,
    ): Result<Long>

    /** 文件又回来了：清空 `missing_since` 并复活（§5.3）。 */
    @Query("update media_asset set missing_since = null, deleted = 0, updated_at = :updatedAt where id = :id")
    suspend fun executeRestoreById(context: QueryExecutor, id: Long, updatedAt: Instant): Result<Long>

    @Query("update media_asset set deleted = 1, updated_at = :updatedAt, updated_by = :updatedBy where id = :id")
    suspend fun executeSoftDeleteById(
        context: QueryExecutor,
        id: Long,
        updatedAt: Instant,
        updatedBy: String?,
    ): Result<Long>

    /** 仪表盘「最近新增」：按登记时间倒序取若干条。 */
    @Query("select * from media_asset where deleted = 0 order by created_at desc limit :limit")
    suspend fun findAllByCreatedAtOrderDesc(context: QueryExecutor, limit: Int): Result<List<MediaAssetEntity>>

    /** 仪表盘「近期活跃」：按源文件修改时间倒序取若干条。 */
    @Query("select * from media_asset where deleted = 0 order by mtime desc limit :limit")
    suspend fun findAllByMtimeOrderDesc(context: QueryExecutor, limit: Int): Result<List<MediaAssetEntity>>
}
