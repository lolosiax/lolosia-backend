package moe.lolosia.web.model.system


import io.ebean.annotation.DbComment
import java.util.*
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Table
import jakarta.persistence.Id

/**
 * 用户角色实体
 *
 * @author 洛洛希雅Lolosia
 * @since 2024-04-25 10:01
 */
@Entity
@Table(name = "sys_user_roles")
class SysUserRolesEntity : SystemModel() {

    @Id
    @Column(nullable = false)
    var id = 0

    @Column(nullable = false)
    @DbComment("用户关联ID")
    lateinit var userId: UUID

    @Column(nullable = false)
    @DbComment("角色ID")
    var roleId = 0
}