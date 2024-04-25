package top.lolosia.web.model.system

import io.ebean.annotation.DbComment
import java.util.*
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Table
import jakarta.persistence.Id

@Entity
@Table(name = "view_user_role")
class ViewUserRoleEntity : SystemModel() {
    @Id
    @DbComment("用户和角色关系id")
    var id = 0

    @Column(nullable = false)
    @DbComment("用户关联ID")
    lateinit var userId: UUID

    @Column(nullable = false)
    @DbComment("角色ID")
    var roleId = 0

    @DbComment("角色名称")
    @Column(nullable = false)
    lateinit var roleName: String

    @DbComment("用户名")
    @Column(nullable = false)
    var userName: String = ""

    @DbComment("用户昵称")
    var realName: String? = ""

    @DbComment("手机号")
    @Column(nullable = false)
    var phone = ""

    @DbComment("用户头像")
    var avatar: String? = null

    @DbComment("类型")
    @Column(nullable = false)
    lateinit var type: String
}