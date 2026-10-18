package moe.lolosia.web.database.model

import io.github.smyrgeorge.sqlx4k.annotation.Id
import io.github.smyrgeorge.sqlx4k.annotation.Table
import kotlin.time.Instant

/**
 * `oss_user_file`：用户文件元数据。
 *
 * 文件本体按 `work/files/<created_by>/<md5>/<file_name>` 落盘（见 `service/oss/UserFileService`），
 * 这里只存元数据。`id` 由应用侧生成（`varchar(40)`），与 `sys_user` 一致。
 *
 * @author 洛洛希雅Lolosia
 */
@Table("oss_user_file")
data class OssUserFileEntity(
    @Id(insert = true)
    val id: String,
    val md5: String,
    val fileName: String,
    val service: String,
    val createdAt: Instant? = null,
    val createdBy: String? = null,
    val updatedAt: Instant? = null,
    val updatedBy: String? = null,
    val deleted: Boolean = false,
)
