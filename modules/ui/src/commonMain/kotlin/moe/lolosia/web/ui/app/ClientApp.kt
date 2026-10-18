package moe.lolosia.web.ui.app

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import moe.lolosia.web.dto.ApiException
import moe.lolosia.web.ui.api.LocalServiceApi
import moe.lolosia.web.ui.api.RemoteApi
import moe.lolosia.web.ui.pages.home.HomeViewModel
import moe.lolosia.web.ui.pages.login.LoginViewModel
import moe.lolosia.web.ui.pages.profile.ProfileViewModel
import moe.lolosia.web.ui.session.AppSession
import moe.lolosia.web.ui.session.ClientSettings
import moe.lolosia.web.ui.session.SettingsStore
import moe.lolosia.web.ui.state.State

/**
 * 应用状态与业务编排。
 *
 * 所有网络 / 磁盘操作都在 [scope] 上执行，结果经 [postToUi] 转回 UI 线程；
 * 本类不直接碰视图层。
 *
 * @param settingsStore 本地设置。
 * @param remoteApiFactory 按 baseUrl 创建远程 API。
 * @param localServiceApi 本地系统服务。
 * @param scope 后台协程作用域。
 * @param postToUi 把一段逻辑投递到 UI 线程。
 * @param baseUrlOverride 环境变量等外部覆盖值，优先级高于设置文件。
 * @author 洛洛希雅Lolosia
 */
class ClientApp(
    private val settingsStore: SettingsStore,
    private val remoteApiFactory: (String) -> RemoteApi,
    private val localServiceApi: LocalServiceApi,
    private val scope: CoroutineScope,
    private val postToUi: (() -> Unit) -> Unit,
    private val baseUrlOverride: String? = null,
) {

    val stage = State(AppStage.Splash)
    val tab = State(MainTab.Profile)
    val login = LoginViewModel()
    val profile = ProfileViewModel()
    val home = HomeViewModel()
    val session = AppSession()

    private var remoteApi: RemoteApi? = null

    /** 启动：恢复设置、校验 token、拉取本地服务状态。 */
    fun bootstrap() {
        val settings = settingsStore.load()
        val baseUrl = normalizeBaseUrl(baseUrlOverride ?: settings.baseUrl)
        login.applySettings(settings.copy(baseUrl = baseUrl))
        refreshLocalService()

        val token = settings.token
        if (token.isNullOrBlank()) {
            stage.value = AppStage.Login
            return
        }

        session.token.value = token
        session.userName.value = settings.userName
        login.loading.value = true
        val api = remoteApiFactory(baseUrl).also { remoteApi = it }
        scope.launch {
            try {
                val info = api.myInfo(token)
                postToUi {
                    profile.apply(info)
                    login.loading.value = false
                    stage.value = AppStage.Main
                    tab.value = MainTab.Profile
                }
                refreshLocalService()
            } catch (e: Throwable) {
                postToUi {
                    if (isAuthError(e)) {
                        clearSession(settings)
                        login.error.value = "登录已过期，请重新登录"
                    } else {
                        login.error.value = "连接服务端失败：${friendlyMessage(e)}"
                    }
                    login.loading.value = false
                    stage.value = AppStage.Login
                }
            }
        }
    }

    /** 视图层动作入口。 */
    fun onAction(name: String) {
        when (name) {
            "login" -> login()
            "logout" -> logout()
            "refresh" -> refresh()
            "tab-home", "open-home" -> openHome()
            "tab-profile", "open-profile" -> openProfile()
            "save-server" -> saveServer()
            "retry" -> if (stage.value == AppStage.Login) login() else refresh()
        }
    }

    /** 当前应加载的 RML 文档。 */
    fun documentPath(): String = when (stage.value) {
        AppStage.Splash -> "ui/pages/splash.rml"
        AppStage.Login -> "ui/pages/login.rml"
        AppStage.Main -> when (tab.value) {
            MainTab.Home -> "ui/pages/home.rml"
            MainTab.Profile -> "ui/pages/profile.rml"
        }
    }

    private fun login() {
        if (!login.canSubmit) {
            login.error.value = "请输入用户名、密码和服务器地址"
            return
        }
        val baseUrl = normalizeBaseUrl(login.baseUrl.value)
        login.baseUrl.value = baseUrl
        login.loading.value = true
        login.clearError()
        val api = remoteApiFactory(baseUrl).also { remoteApi = it }
        val settings = settingsStore.load()
        val userName = login.userName.value.trim()
        val password = login.password.value
        scope.launch {
            try {
                val result = api.login(userName, password)
                session.token.value = result.authorization
                session.userName.value = result.userName
                settingsStore.save(
                    settings.copy(baseUrl = baseUrl, token = result.authorization, userName = result.userName),
                )
                val info = runCatching { api.myInfo(result.authorization) }.getOrNull()
                postToUi {
                    if (info != null) profile.apply(info) else profile.apply(result)
                    login.loading.value = false
                    login.clearPassword()
                    stage.value = AppStage.Main
                    tab.value = MainTab.Profile
                }
                refreshLocalService()
            } catch (e: Throwable) {
                postToUi {
                    login.loading.value = false
                    login.error.value = friendlyMessage(e)
                }
            }
        }
    }

    private fun logout() {
        val token = session.token.value
        val api = remoteApi
        clearSession(settingsStore.load())
        profile.clear()
        home.items.clear()
        postToUi {
            stage.value = AppStage.Login
            tab.value = MainTab.Profile
            login.clearPassword()
            login.clearError()
        }
        if (!token.isNullOrBlank() && api != null) {
            scope.launch { runCatching { api.logout(token) } }
        }
    }

    private fun refresh() {
        when (stage.value) {
            AppStage.Main -> if (tab.value == MainTab.Home) loadHome() else refreshProfile()
            AppStage.Login -> login()
            AppStage.Splash -> Unit
        }
        refreshLocalService()
    }

    private fun openHome() {
        if (stage.value != AppStage.Main) return
        tab.value = MainTab.Home
        loadHome()
    }

    private fun openProfile() {
        if (stage.value != AppStage.Main) return
        tab.value = MainTab.Profile
        refreshProfile()
    }

    private fun saveServer() {
        val baseUrl = normalizeBaseUrl(login.baseUrl.value)
        login.baseUrl.value = baseUrl
        settingsStore.save(settingsStore.load().copy(baseUrl = baseUrl))
        remoteApi = null
    }

    private fun loadHome() {
        val token = session.token.value ?: return
        val api = remoteApi ?: return
        home.startLoading()
        scope.launch {
            try {
                val items = api.animeTimetable(token)
                postToUi { home.apply(items) }
            } catch (e: Throwable) {
                postToUi {
                    if (isAuthError(e)) expireSession() else home.fail(friendlyMessage(e))
                }
            }
        }
    }

    private fun refreshProfile() {
        val token = session.token.value ?: return
        val api = remoteApi ?: return
        scope.launch {
            try {
                val info = api.myInfo(token)
                postToUi { profile.apply(info) }
            } catch (e: Throwable) {
                postToUi { if (isAuthError(e)) expireSession() }
            }
        }
    }

    private fun refreshLocalService() {
        scope.launch {
            val health = runCatching { localServiceApi.health() }.getOrNull()
            val metrics = runCatching { localServiceApi.metrics() }.getOrNull()
            postToUi { profile.applyLocal(health, metrics) }
        }
    }

    private fun expireSession() {
        clearSession(settingsStore.load())
        stage.value = AppStage.Login
        login.error.value = "登录已过期，请重新登录"
    }

    private fun clearSession(settings: ClientSettings) {
        settingsStore.save(settings.copy(token = null))
        session.clear()
    }
}

/** 认证类错误：清本地会话并回登录页。 */
private fun isAuthError(e: Throwable): Boolean =
    e is ApiException && (e.status == 401 || e.status == 403)

/** 面向用户的错误文案；不把堆栈或 URL 暴露到页面。 */
private fun friendlyMessage(e: Throwable): String = when (e) {
    is ApiException -> e.message?.takeIf { it.isNotBlank() } ?: "请求失败"
    else -> e.message?.takeIf { it.isNotBlank() } ?: "网络异常"
}

/** 去掉末尾 `/`；没写协议时按 HTTPS 处理（本机开发默认值自带 `http://`）。 */
private fun normalizeBaseUrl(raw: String): String {
    val trimmed = raw.trim().trimEnd('/')
    if (trimmed.isEmpty()) return trimmed
    return if (trimmed.contains("://")) trimmed else "https://$trimmed"
}
