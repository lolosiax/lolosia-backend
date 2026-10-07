package moe.lolosia.web.dto

import kotlinx.serialization.Serializable
import moe.lolosia.web.database.model.OssUserFileEntity
import moe.lolosia.web.util.time.toDisplayString

/**
 * OSS 文件相关 DTO（对应前端 `src/api/oss.ts`）。
 *
 * @author 洛洛希雅Lolosia
 */

/** 文件元数据（前端 `OssFile`：`BasicModel` + id / md5 / fileName / service）。 */
@Serializable
data class OssFile(
    val id: String,
    val md5: String,
    val fileName: String,
    val service: String,
    val createdAt: String? = null,
    val createdBy: String? = null,
    val updatedAt: String? = null,
    val updatedBy: String? = null,
    val deleted: Boolean = false,
)

/** 列出某 service 下的文件（前端 `listFiles(service)`）。 */
@Serializable
data class OssListReq(val service: String? = null)

/** 按 md5 取元数据（前端 `getFiles(service, md5)`）。 */
@Serializable
data class OssMetasReq(val service: String? = null, val md5: List<String> = emptyList())

/** 批量探测文件是否已存在（前端 `checkFile(md5)`）。 */
@Serializable
data class OssCheckReq(val md5: List<String> = emptyList())

// ===== 实体 → DTO =====

fun OssUserFileEntity.toOssFile(): OssFile = OssFile(
    id = id,
    md5 = md5,
    fileName = fileName,
    service = service,
    createdAt = createdAt?.toDisplayString(),
    createdBy = createdBy,
    updatedAt = updatedAt?.toDisplayString(),
    updatedBy = updatedBy,
    deleted = deleted,
)
