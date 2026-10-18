package moe.lolosia.web.database.model

import io.github.smyrgeorge.sqlx4k.annotation.Id
import io.github.smyrgeorge.sqlx4k.annotation.Table
import kotlin.time.Instant

/**
 * `media_embedding`：向量**索引**（本体在 `<root>/.cache/…/vec/<model>.f32`）。
 *
 * 一行 = 一个资产 × 一个模型，[rowIndex] 是向量本体在第几个 dim 的起始偏移。
 * 检索时整块读进内存做点积即可 —— 十万级数据暴力算够用，不值得为此引一套向量数据库
 * （见 `doc/media-index-design.md` §2.3）。
 *
 * [aliveKey] 是生成列（`assetId:model`，软删时取 `NULL`），令「换模型重跑」不会覆盖旧向量。
 *
 * @author 洛洛希雅Lolosia
 */
@Table("media_embedding")
data class MediaEmbeddingEntity(
    @Id
    val id: Long = 0L,
    val assetId: Long,
    /** 如 `clip-vit-b32`。 */
    val model: String,
    val dim: Int,
    /** 向量本体在 `<model>.f32` 里的行号。 */
    val rowIndex: Long,
    /** 归一化前的模长，仅用于诊断。 */
    val norm: Double? = null,
    val createdAt: Instant? = null,
    val updatedAt: Instant? = null,
    val deleted: Boolean = false,
    /** 生成列，只读。 */
    val aliveKey: String? = null,
)
