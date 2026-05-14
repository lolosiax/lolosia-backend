package moe.lolosia.web.model.session

import com.fasterxml.jackson.annotation.JsonFormat
import moe.lolosia.web.util.ebean.AbstractModel
import moe.lolosia.web.model.ModelCompanion
import io.ebean.DB
import io.ebean.annotation.DbName
import io.ebean.annotation.SoftDelete
import io.ebean.annotation.WhenCreated
import io.ebean.annotation.WhenModified
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.sql.Timestamp
import java.util.*

@DbName("db")
@Entity
@Table(name = "sys_sessions")
class SessionEntity : AbstractModel("db") {

    companion object : ModelCompanion {
        @JvmStatic
        @Deprecated("use spring bean instead.", replaceWith = ReplaceWith("ctx.database", "ctx"))
        override val database get() = DB.byName("db")
    }

    @Id
    lateinit var id: UUID

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