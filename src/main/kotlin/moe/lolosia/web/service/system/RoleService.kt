package moe.lolosia.web.service.system

import moe.lolosia.web.model.system.SysRoleEntity
import moe.lolosia.web.model.system.query.QSysRoleEntity
import moe.lolosia.web.util.ebean.orderBy
import moe.lolosia.web.util.ebean.toAsync
import moe.lolosia.web.util.session.Context
import moe.lolosia.web.util.session.query
import moe.lolosia.web.annotation.Service

/**
 * 角色服务。
 * @author 洛洛希雅Lolosia
 * @since 2024-04-25 10:01
 */
@Service
class RoleService {

    suspend fun list(ctx: Context): List<SysRoleEntity> {
        return ctx.query<QSysRoleEntity>().orderBy { id.asc() }.toAsync().findList()
    }
}
