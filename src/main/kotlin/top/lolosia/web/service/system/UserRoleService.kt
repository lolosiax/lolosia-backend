package top.lolosia.web.service.system

import com.fasterxml.jackson.databind.json.JsonMapper
import top.lolosia.web.model.system.SysUserRolesEntity
import top.lolosia.web.model.system.query.QSysRoleEntity
import top.lolosia.web.model.system.query.QSysUserRolesEntity
import top.lolosia.web.model.system.query.QViewUserRoleEntity
import top.lolosia.web.util.bundle.Bundle
import top.lolosia.web.util.bundle.bundleScope
import top.lolosia.web.util.bundle.toBundle
import top.lolosia.web.util.ebean.toModel
import top.lolosia.web.util.ebean.toUuid
import top.lolosia.web.util.session.Context
import top.lolosia.web.util.success
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Service

@Service
class UserRoleService {

    @Autowired
    lateinit var mapper: JsonMapper

    fun create(context: Context, userRole: Bundle): Any {
        mapper.toModel<SysUserRolesEntity>(userRole).insert()
        return success()
    }

    fun getByUserId(ctx: Context, userId: String): Bundle {
        return QViewUserRoleEntity().run {
            this.userId.eq(ctx.user.id)
            val rs = findOne()
            rs?.let { mapper.toBundle(it) } ?: bundleScope {
                "roleId" set 0
                "userId" set 0
            }
        }
    }

    /**
     * 根据用户ID获取关联的角色信息
     */
    fun getRoleByUserId(ctx: Context, user: Bundle): Bundle {
        val userId = user["userId"]?.toString()!!.toUuid()
        val has = QSysUserRolesEntity().run {
            this.userId.eq(userId)
            exists()
        }
        // 找不到角色时自动创建学生角色
        if (!has) {
            val student = QSysRoleEntity().run {
                type.eq("student")
                findOne() ?: throw NoSuchElementException("找不到学生角色")
            }
            SysUserRolesEntity().apply {
                this.userId = userId
                this.roleId = student.id
                insert()
            }
        }
        val db = QViewUserRoleEntity().run {
            this.userId.eq(userId)
            val rs = findOne()
            rs?.let { mapper.toBundle(it) } ?: bundleScope {
                "roleId" set 0
                "userId" set 0
            }
        }
        db["roleType"] = db["type"]
        return db
    }
}