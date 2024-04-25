package top.lolosia.web.model.system

import io.ebean.annotation.DbComment
import io.ebean.annotation.DbEnumValue
import java.util.UUID
import jakarta.persistence.*

@Entity
@Table(name = "sys_logs")
class SysLogsEntity : SystemModel() {
    @Id
    @Column(nullable = false)
    var id = 0

    @Enumerated(EnumType.STRING)
    @DbComment("日志级别，info、error")
    var level = Level.Info

    @DbComment("用户编号")
    var userId: UUID? = null

    @Enumerated(EnumType.STRING)
    @DbComment("请求方式，get、post...")
    var method: Method? = null

    @DbComment("请求的接口")
    var url = ""

    @DbComment("请求参数")
    var params: String? = ""

    @DbComment("响应的数据")
    var response: String? = ""

    enum class Level(private val value: String) {
        Info("info"),
        Error("error");

        @DbEnumValue
        override fun toString(): String {
            return value
        }
    }

    enum class Method(private val value: String) {
        GET("get"),
        POST("post"),
        HEAD("head"),
        OPTIONS("options"),
        PUT("put"),
        PATCH("patch"),
        DELETE("delete"),
        TRACE("trace"),
        CONNECT("connect");

        @DbEnumValue
        override fun toString(): String {
            return value
        }
    }
}