package top.lolosia.web.model.session

import com.fasterxml.jackson.annotation.JsonFormat
import top.lolosia.web.model.ModelCompanion
import io.ebean.DB
import io.ebean.Model
import io.ebean.annotation.DbName
import io.ebean.annotation.SoftDelete
import io.ebean.annotation.WhenCreated
import io.ebean.annotation.WhenModified
import java.sql.Timestamp
import java.util.UUID
import jakarta.persistence.Entity
import jakarta.persistence.Table
import jakarta.persistence.Id

@DbName("db")
@Entity
@Table(name = "Sessions")
class SessionEntity : Model("db") {

    companion object : ModelCompanion {
        @JvmStatic
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