package moe.lolosia.web.service.system

import com.fasterxml.jackson.databind.json.JsonMapper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import moe.lolosia.web.annotation.Service
import moe.lolosia.web.model.system.SysRoleEntity
import moe.lolosia.web.model.system.query.QSysRoleEntity
import moe.lolosia.web.model.system.query.QSysUserRolesEntity
import moe.lolosia.web.util.bundle.Bundle
import moe.lolosia.web.util.bundle.bundleScope
import moe.lolosia.web.util.bundle.invoke
import moe.lolosia.web.util.bundle.scope
import moe.lolosia.web.util.bundle.toBundle
import moe.lolosia.web.util.ebean.applyDatabase
import moe.lolosia.web.util.ebean.or
import moe.lolosia.web.util.ebean.orderBy
import moe.lolosia.web.util.ebean.toAsync
import moe.lolosia.web.util.ebean.toModel
import moe.lolosia.web.util.session.Context
import moe.lolosia.web.util.session.createModel
import moe.lolosia.web.util.session.query
import moe.lolosia.web.util.success
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

/**
 * 角色服务。
 * @author 洛洛希雅Lolosia
 * @since 2024-04-25 10:01
 */
@Service
class RoleService : KoinComponent {

    private val mapper: JsonMapper by inject()

    suspend fun list(ctx: Context): List<SysRoleEntity> {
        return ctx.query<QSysRoleEntity>().orderBy { id.asc() }.toAsync().findList()
    }

    /** 分页查询角色（支持 roleName/type 模糊搜索） */
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

        // 菜单相关字段已弃用，仅保留用于前端兼容
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

    suspend fun update(ctx: Context, bundle: Bundle): Bundle {
        val model = mapper.toModel<SysRoleEntity>(bundle).apply {
            applyDatabase()
            updatedBy = ctx.user.id
        }
        withContext(Dispatchers.IO) {
            model.update()
        }
        return success()
    }

    suspend fun destroy(ctx: Context, id: Int): Bundle {
        val role = ctx.query<QSysRoleEntity> {
            this.id.eq(id)
        }.toAsync().findOne() ?: return success()
        if (role.type == "super_admin") throw IllegalStateException("不能删除超级管理员角色")
        val inUse = ctx.query<QSysUserRolesEntity> {
            this.roleId.eq(id)
        }.toAsync().exists()
        if (inUse) throw IllegalStateException("该角色正在使用")
        withContext(Dispatchers.IO) {
            role.delete()
        }
        return success()
    }
}
