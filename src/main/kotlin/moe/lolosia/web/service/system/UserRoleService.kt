package moe.lolosia.web.service.system

import com.fasterxml.jackson.databind.json.JsonMapper
import moe.lolosia.web.model.system.SysUserRolesEntity
import moe.lolosia.web.model.system.query.QSysRoleEntity
import moe.lolosia.web.model.system.query.QSysUserRolesEntity
import moe.lolosia.web.model.system.query.QViewUserRoleEntity
import moe.lolosia.web.util.bundle.Bundle
import moe.lolosia.web.util.bundle.bundleScope
import moe.lolosia.web.util.bundle.toBundle
import moe.lolosia.web.util.ebean.createModel
import moe.lolosia.web.util.ebean.query
import moe.lolosia.web.util.ebean.toModel
import moe.lolosia.web.util.ebean.toUuid
import moe.lolosia.web.util.session.Context
import moe.lolosia.web.util.success
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
        val rs = ctx.query<QViewUserRoleEntity> {
            this.userId.eq(ctx.user.id)
        }.findOne()
        return rs?.let { mapper.toBundle(it) } ?: bundleScope {
            "roleId" set 0
            "userId" set 0
        }
    }

    /**
     * 根据用户ID获取关联的角色信息
     */
    fun getRoleByUserId(ctx: Context, user: Bundle): Bundle = ctx {
        val userId = user["userId"]?.toString()!!.toUuid()
        val has = query<QSysUserRolesEntity>().userId.eq(userId).exists()
        // 找不到角色时自动创建用户角色
        if (!has) {
            val roleUser = query<QSysRoleEntity> {
                type.eq("user")
            }.findOne() ?: throw NoSuchElementException("找不到用户角色")
            createModel<SysUserRolesEntity> {
                this.userId = userId
                this.roleId = roleUser.id
            }.insert()
        }
        val rs = query<QViewUserRoleEntity> {
            this.userId.eq(userId)
        }.findOne()
        val out = rs?.let { mapper.toBundle(it) } ?: bundleScope {
            "roleId" set 0
            "userId" set 0
        }
        out["roleType"] = out["type"]
        return out
    }
}