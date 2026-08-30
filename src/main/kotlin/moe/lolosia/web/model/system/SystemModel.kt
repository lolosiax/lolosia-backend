package moe.lolosia.web.model.system

import com.fasterxml.jackson.annotation.JsonFormat
import io.ebean.annotation.DbName
import io.ebean.annotation.SoftDelete
import io.ebean.annotation.WhenCreated
import io.ebean.annotation.WhenModified
import jakarta.persistence.MappedSuperclass
import moe.lolosia.web.util.ebean.AbstractModel
import java.sql.Timestamp
import java.util.*

/**
 * 实体公共基类。
 * [DbName] 将实体绑定到命名数据库 "db"，配合 AbstractModel 完成路由。
 *
 * @author 洛洛希雅Lolosia
 * @since 2024-04-25 10:01
 */
@DbName("db")
@MappedSuperclass
abstract class SystemModel : AbstractModel("db") {

    var createdBy: UUID? = null

    var updatedBy: UUID? = null

    @WhenCreated
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
    var createdAt: Timestamp? = null

    @WhenModified
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
    var updatedAt: Timestamp? = null

    @SoftDelete
    var deleted = false
}
