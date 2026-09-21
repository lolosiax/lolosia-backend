package moe.lolosia.web.util.session

import moe.lolosia.web.model.system.SysRoleEntity
import moe.lolosia.web.model.system.query.QSysRoleEntity
import moe.lolosia.web.model.system.query.QSysUserEntity
import moe.lolosia.web.model.system.query.QSysUserRolesEntity
import moe.lolosia.web.util.ebean.toUuid
import java.util.*

/**
 * 当前登录用户信息。
 *
 * @author 洛洛希雅Lolosia
 * @since 2024-04-25 10:01
 */
class UserInfo(val context: Context) {
    val id: UUID by lazy { context.userId.toUuid() }
    val userEntity by lazy {
        context.query<QSysUserEntity>().id.eq(id).findOne() ?: throw NoSuchElementException("未找到用户 $id")
    }

    private val roleEntity: SysRoleEntity by lazy {
        val ae = context.query<QSysUserRolesEntity>().userId.eq(id).findOne()
            ?: throw NoSuchElementException("未找到用户角色 $id")
        val role = context.query<QSysRoleEntity>().id.eq(ae.roleId).findOne()
            ?: throw NoSuchElementException("找不到角色 ${ae.id}")
        role
    }

    val userName get() = userEntity.userName
    val realName get() = userEntity.realName
    val phone get() = userEntity.phone
    val isUse get() = userEntity.isUse
    val roleId get() = roleEntity.id
    val roleName get() = roleEntity.roleName
    val roleType get() = roleEntity.type
    val isAdmin get() = "admin" in roleType
    val isTeacher get() = roleType == "teacher"
    val isStudent get() = roleType == "student"
}
