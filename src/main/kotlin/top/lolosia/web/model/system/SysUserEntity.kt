package top.lolosia.web.model.system

import io.ebean.annotation.DbComment
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.util.*

@Entity
@Table(name = "sys_user")
class SysUserEntity : SystemModel() {
    @Id
    @Column(nullable = false)
    @DbComment("用户编号")
    lateinit var id: UUID

    @DbComment("用户名")
    @Column(nullable = false)
    var userName: String = ""

    @DbComment("用户昵称")
    var realName: String? = ""

    @DbComment("密码")
    var password: String? = null

    @DbComment("手机号")
    var phone: String? = null

    @DbComment("邮箱")
    var email: String? = null

    @DbComment("用户头像")
    var avatar: String? = null

    @Column(nullable = false)
    @DbComment("是否已启用账户")
    @JvmField
    var isUse = true
}