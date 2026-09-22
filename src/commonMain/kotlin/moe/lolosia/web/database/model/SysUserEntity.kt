package moe.lolosia.web.database.model

import io.github.smyrgeorge.sqlx4k.annotation.Id
import io.github.smyrgeorge.sqlx4k.annotation.Table
import kotlin.time.Instant

/**
 * `sys_user`：用户表。
 *
 * 库端默认值不可靠：`created_at` 带 `ON UPDATE CURRENT_TIMESTAMP`、
 * `updated_at` 是 `DEFAULT '0000-00-00 00:00:00'`，因此写入时必须两个都给值。
 *
 * @author 洛洛希雅Lolosia
 */
@Table("sys_user")
public data class SysUserEntity(
    @Id(insert = true)
    val id: String,
    val userName: String,
    val password: String? = null,
    val realName: String? = null,
    val phone: String? = null,
    val email: String? = null,
    val avatar: String? = null,
    val isUse: Boolean? = null,
    val createdAt: Instant? = null,
    val createdBy: String? = null,
    val updatedAt: Instant? = null,
    val updatedBy: String? = null,
    val deleted: Boolean = false,
)
