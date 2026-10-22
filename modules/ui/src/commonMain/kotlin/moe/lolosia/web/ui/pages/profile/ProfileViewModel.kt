package moe.lolosia.web.ui.pages.profile

import moe.lolosia.web.dto.LoginResult
import moe.lolosia.web.dto.UserInfoRow
import moe.lolosia.web.ui.api.LocalHealth
import moe.lolosia.web.ui.api.LocalMetrics
import moe.lolosia.web.ui.state.State

/**
 * 个人资料页状态。
 *
 * 所有可空字段都落成字符串（空串表示未填写），视图层不需要处理 null。
 *
 * @author 洛洛希雅Lolosia
 */
class ProfileViewModel {

    val displayName = State("")
    val avatarInitial = State("?")
    val avatarPath = State("")
    val hasAvatarText = State(true)
    val hasAvatarImage = State(false)
    val realName = State("")
    val email = State("")
    val phone = State("")
    val roleName = State("")
    val localStatus = State("本地服务未运行")
    val localCpu = State("--")
    val localMem = State("--")
    val localDisk = State("--")

    fun apply(user: UserInfoRow) {
        displayName.value = user.userName
        avatarInitial.value = initialOf(user.userName)
        resetAvatar()
        realName.value = user.realName.orEmpty().ifBlank { "未填写" }
        email.value = user.email.orEmpty().ifBlank { "未填写" }
        phone.value = user.phone.orEmpty().ifBlank { "未填写" }
        roleName.value = roleNameOf(user.roleId)
    }

    fun apply(result: LoginResult) {
        displayName.value = result.userName
        avatarInitial.value = initialOf(result.userName)
        resetAvatar()
        realName.value = result.realName.orEmpty().ifBlank { "未填写" }
        email.value = result.email.orEmpty().ifBlank { "未填写" }
        phone.value = result.phone.orEmpty().ifBlank { "未填写" }
        roleName.value = roleNameOf(0)
    }

    /** 头像字节已登记到视图层后调用；没有头像时保持首字占位。 */
    fun applyAvatar(path: String) {
        avatarPath.value = path
        hasAvatarImage.value = true
        hasAvatarText.value = false
    }

    fun applyLocal(health: LocalHealth?, metrics: LocalMetrics?) {
        if (health == null || !health.ok) {
            localStatus.value = "本地服务未运行"
            localCpu.value = "--"
            localMem.value = "--"
            localDisk.value = "--"
            return
        }
        localStatus.value = "本地服务在线 · ${health.version}"
        localCpu.value = metrics?.cpu?.let { "CPU ${it.toPercent()}" } ?: "CPU --"
        localMem.value = metrics?.mem?.let { "内存 ${it.toPercent()}" } ?: "内存 --"
        localDisk.value = metrics?.disk?.let { "磁盘 ${it.toPercent()}" } ?: "磁盘 --"
    }

    fun clear() {
        displayName.value = ""
        avatarInitial.value = "?"
        resetAvatar()
        realName.value = ""
        email.value = ""
        phone.value = ""
        roleName.value = ""
        localStatus.value = "本地服务未运行"
        localCpu.value = "--"
        localMem.value = "--"
        localDisk.value = "--"
    }

    /** 回到首字占位；新的用户数据到达时先重置，等头像登记成功再切换。 */
    private fun resetAvatar() {
        avatarPath.value = ""
        hasAvatarImage.value = false
        hasAvatarText.value = true
    }
}

/** 头像占位字：取用户名首字符，中文与英文都成立。 */
private fun initialOf(userName: String): String =
    userName.trim().take(1).uppercase().ifEmpty { "?" }

/** 角色 id 到展示名；与服务端 `sql/sys_role.sql` 的固定 id 对齐。 */
private fun roleNameOf(roleId: Long): String = when (roleId) {
    1L -> "拥有者"
    2L -> "管理员"
    4L -> "普通用户"
    5L -> "访客"
    else -> "未分配"
}

/** 0..1 的比例转百分比；服务端也可能给 0..100，超过 1 时按百分数原样显示。 */
private fun Double.toPercent(): String {
    val percent = if (this <= 1.0) this * 100 else this
    return "${percent.toInt()}%"
}
