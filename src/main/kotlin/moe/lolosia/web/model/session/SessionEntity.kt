package moe.lolosia.web.model.session

import com.fasterxml.jackson.annotation.JsonFormat
import io.ebean.annotation.DbComment
import io.ebean.annotation.DbName
import io.ebean.annotation.SoftDelete
import io.ebean.annotation.WhenCreated
import io.ebean.annotation.WhenModified
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import moe.lolosia.web.util.ebean.AbstractModel
import java.sql.Timestamp
import java.util.*

/**
 * 会话实体
 *
 * @author 洛洛希雅Lolosia
 * @since 2024-04-25 10:01
 * */
@DbName("db")
@Entity
@Table(name = "sys_sessions")
class SessionEntity : AbstractModel("db") {
    @Id
    lateinit var id: UUID

    @DbComment("会话数据(JSON)")
    var data = "{}"

    @WhenCreated
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
    var createdAt: Timestamp? = null

    @WhenModified
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
    var updatedAt: Timestamp? = null

    @SoftDelete
    var deleted = false
}
