package top.lolosia.web.model.system

import io.ebean.annotation.DbComment
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Table
import jakarta.persistence.Id

@Entity
@Table(name = "sys_role")
class SysRoleEntity : SystemModel() {
    @Id
    @Column(nullable = false)
    @DbComment("角色ID")
    var id = 0

    @Column(nullable = false)
    @DbComment("角色名称")
    var roleName = ""

    @Column(nullable = false)
    @DbComment("角色类型")
    var type = ""
}