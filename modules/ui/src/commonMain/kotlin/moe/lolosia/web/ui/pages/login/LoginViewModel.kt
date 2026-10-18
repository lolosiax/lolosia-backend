package moe.lolosia.web.ui.pages.login

import moe.lolosia.web.ui.session.ClientSettings
import moe.lolosia.web.ui.state.State

/**
 * 登录页状态。
 *
 * 输入框由视图层双向绑定；[hasError] 是给 `data-if` 用的布尔镜像，
 * 避免 RmlUi 对字符串做真值判断。
 *
 * @author 洛洛希雅Lolosia
 */
class LoginViewModel {

    val userName = State("")
    val password = State("")
    val baseUrl = State("")
    val loading = State(false)
    val error = State("")
    val hasError = State(false)

    init {
        error.observe { hasError.value = it.isNotEmpty() }
    }

    val canSubmit: Boolean
        get() = userName.value.isNotBlank() && password.value.isNotBlank() && baseUrl.value.isNotBlank() && !loading.value

    fun applySettings(settings: ClientSettings) {
        if (settings.baseUrl.isNotBlank()) baseUrl.value = settings.baseUrl
        if (!settings.userName.isNullOrBlank()) userName.value = settings.userName
    }

    fun clearError() {
        error.value = ""
    }

    fun clearPassword() {
        password.value = ""
    }
}
