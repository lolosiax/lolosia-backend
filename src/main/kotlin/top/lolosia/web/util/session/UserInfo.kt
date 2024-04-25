package top.lolosia.web.util.session

import top.lolosia.web.model.system.query.QSysRoleEntity
import top.lolosia.web.model.system.query.QSysUserEntity
import top.lolosia.web.model.system.query.QSysUserRolesEntity
import top.lolosia.web.util.ebean.toUuid
import java.util.*

class UserInfo(val context: Context) {
    val id: UUID by lazy { context.userId.toUuid() }
    private val userEntity by lazy {
        QSysUserEntity().id.eq(id).findOne() ?: throw NoSuchElementException("未找到用户 $id")
    }

    private val roleEntity by lazy {
        val ae = QSysUserRolesEntity().userId.eq(id).findOne() ?: throw NoSuchElementException("未找到用户角色 $id")
        return@lazy QSysRoleEntity().id.eq(ae.roleId).findOne() ?: throw NoSuchElementException("找不到角色 ${ae.id}")
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