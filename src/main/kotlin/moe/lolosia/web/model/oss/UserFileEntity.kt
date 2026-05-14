package moe.lolosia.web.model.oss

import io.ebean.annotation.DbComment
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.util.*

@Entity
@Table(name = "oss_user_file")
class UserFileEntity : OssModel() {
    @Id
    @Column(nullable = false)
    @DbComment("文件ID")
    lateinit var id: UUID

    @Column(nullable = false)
    @DbComment("MD5")
    lateinit var md5: String

    @Column(nullable = false)
    @DbComment("文件名")
    lateinit var fileName: String

    @Column(nullable = false)
    @DbComment("服务名")
    lateinit var service: String
}