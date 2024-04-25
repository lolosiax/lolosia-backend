package top.lolosia.web.service.system

import com.fasterxml.jackson.databind.json.JsonMapper
import top.lolosia.web.model.system.SysRoleEntity
import top.lolosia.web.model.system.query.QSysRoleEntity
import top.lolosia.web.model.system.query.QSysUserRolesEntity
import top.lolosia.web.util.session.Context
import top.lolosia.web.util.bundle.*
import top.lolosia.web.util.ebean.or
import top.lolosia.web.util.ebean.toModel
import top.lolosia.web.util.success
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Service

@Service
class RoleService {

    @Autowired
    lateinit var mapper: JsonMapper

    fun list(): MutableList<SysRoleEntity> {
        return QSysRoleEntity().findList()
    }

    fun queryRoleByPage(ctx: Context, pageNo: Int, pageSize: Int, roleName: String?): Bundle {
        val roles = QSysRoleEntity().run {
            if (!roleName.isNullOrEmpty()) or {
                this.roleName.contains(roleName)
                this.type.contains(roleName)
            }
            setMaxRows(pageSize)
            setFirstRow((pageNo - 1) * pageSize)
            findPagedList()
        }

        // 查询菜单的相关代码已被弃用，此处仅保留用于兼容的代码。
        val data = roles.list.map {
            mapper.toBundle(it).scope {
                "menuId" set emptyList<Int>()
                "menuTitle" set emptyList<String>()
            }
        }

        return bundleScope {
            "total" set roles.totalCount
            "rows" set data
        }
    }

    fun create(ctx: Context, bundle: Bundle): SysRoleEntity {
        return SysRoleEntity().apply {
            roleName = bundle("roleName") ?: throw IllegalArgumentException("必须填写roleName")
            type = bundle("type") ?: throw IllegalArgumentException("必须填写type")
            createdBy = ctx.user.id
            insert()
            refresh()
        }
    }

    fun update(ctx: Context, bundle: Bundle): Any {
        mapper.toModel<SysRoleEntity>(bundle).apply {
            updatedBy = ctx.user.id
            insert()
        }
        return success()
    }

    fun destroy(ctx: Context, id: Int): Any {
        val role = QSysRoleEntity().run {
            this.id.eq(id)
            findOne() ?: return success()
        }
        if (role.type == "super_admin") throw IllegalStateException("不能删除超级管理员角色")
        QSysUserRolesEntity().run {
            this.roleId.eq(id)
            if (exists()) throw IllegalStateException("该角色正在使用")
        }
        role.delete()
        return success()
    }
}