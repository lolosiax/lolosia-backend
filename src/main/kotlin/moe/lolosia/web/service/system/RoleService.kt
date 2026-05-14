package moe.lolosia.web.service.system

import com.fasterxml.jackson.databind.json.JsonMapper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import moe.lolosia.web.model.system.SysRoleEntity
import moe.lolosia.web.model.system.query.QSysRoleEntity
import moe.lolosia.web.model.system.query.QSysUserRolesEntity
import moe.lolosia.web.util.bundle.*
import moe.lolosia.web.util.ebean.*
import moe.lolosia.web.util.session.Context
import moe.lolosia.web.util.success
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Service

@Service
class RoleService {

    @Autowired
    lateinit var mapper: JsonMapper

    suspend fun list(ctx: Context): List<SysRoleEntity?> {
        return ctx.query<QSysRoleEntity>().toAsync().findList()
    }

    suspend fun queryRoleByPage(ctx: Context, pageNo: Int, pageSize: Int, roleName: String?): Bundle {
        val roles = ctx.query<QSysRoleEntity> {
            if (!roleName.isNullOrEmpty()) {
                or {
                    this.roleName.contains(roleName)
                    this.type.contains(roleName)
                }
            }
            setMaxRows(pageSize)
            setFirstRow((pageNo - 1) * pageSize)
        }.toAsync().findPagedList()

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

    suspend fun create(ctx: Context, bundle: Bundle): SysRoleEntity {
        val model = ctx.createModel<SysRoleEntity> {
            roleName = bundle("roleName") ?: throw IllegalArgumentException("必须填写roleName")
            type = bundle("type") ?: throw IllegalArgumentException("必须填写type")
            createdBy = ctx.user.id
        }
        withContext(Dispatchers.IO) {
            model.insert()
            model.refresh()
        }

        return model
    }

    suspend fun update(ctx: Context, bundle: Bundle): Any {
        val model = mapper.toModel<SysRoleEntity>(bundle).apply {
            applyDatabase(ctx)
            updatedBy = ctx.user.id
        }
        withContext(Dispatchers.IO) {
            model.update()
        }
        return success()
    }

    suspend fun destroy(ctx: Context, id: Int): Any {
        val role = ctx.query<QSysRoleEntity> {
            this.id.eq(id)
        }.toAsync().findOne() ?: return success()
        if (role.type == "super_admin") throw IllegalStateException("不能删除超级管理员角色")
        ctx.query<QSysUserRolesEntity> {
            this.roleId.eq(id)
            if (toAsync().exists()) throw IllegalStateException("该角色正在使用")
        }
        withContext(Dispatchers.IO) {
            role.delete()
        }

        return success()
    }
}