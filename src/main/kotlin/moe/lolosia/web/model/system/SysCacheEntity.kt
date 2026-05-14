package moe.lolosia.web.model.system

import com.fasterxml.jackson.annotation.JsonFormat
import io.ebean.annotation.DbComment
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.sql.Timestamp

@Entity
@Table(name = "sys_cache")
class SysCacheEntity : SystemModel() {
    @Id
    @Column(nullable = false)
    @DbComment("索引")
    var id : Int? = null

    @DbComment("路径")
    @Column(nullable = false, length = 255)
    var path : String = ""

    @DbComment("标签")
    @Column(length = 255)
    var tag : String = ""

    @Column(nullable = false, length = 65535)
    @DbComment("值")
    var value : String = ""

    @Column(nullable = false)
    @DbComment("过期时间")
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
    // 默认过期时间：1天
    var expiresAt : Timestamp = Timestamp(System.currentTimeMillis() + 1000 * 60 * 60 * 24)
}