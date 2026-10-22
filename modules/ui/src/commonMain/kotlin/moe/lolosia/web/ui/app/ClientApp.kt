package moe.lolosia.web.ui.app

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import moe.lolosia.web.dto.ApiException
import moe.lolosia.web.dto.AnimeSeriesDto
import moe.lolosia.web.dto.UserInfoRow
import moe.lolosia.web.ui.api.LocalServiceApi
import moe.lolosia.web.ui.api.RemoteApi
import moe.lolosia.web.ui.image.ImageLoader
import moe.lolosia.web.ui.pages.home.HomeViewModel
import moe.lolosia.web.ui.pages.home.coverPath
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
 * @param imageLoader 图片资源登记。
 * @param scope 后台协程作用域。
 * @param postToUi 把一段逻辑投递到 UI 线程。
 * @param baseUrlOverride 环境变量等外部覆盖值，优先级高于设置文件。
 * @author 洛洛希雅Lolosia
 */
class ClientApp(
    private val settingsStore: SettingsStore,
    private val remoteApiFactory: (String) -> RemoteApi,
    private val localServiceApi: LocalServiceApi,
    private val imageLoader: ImageLoader,
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
                val avatar = loadAvatar(api, info)
                postToUi {
                    profile.apply(info)
                    avatar?.let { (path, bytes) ->
                        imageLoader.register(path, bytes)?.let(profile::applyAvatar)
                    }
                    login.loading.value = false
                    stage.value = AppStage.Main
                    tab.value = MainTab.Home
                }
                loadHome()
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
            "open-components" -> openComponents()
            "back" -> if (tab.value == MainTab.Components) openProfile()
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
            MainTab.Components -> "ui/pages/components.rml"
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
                val avatar = info?.let { loadAvatar(api, it) }
                postToUi {
                    if (info != null) profile.apply(info) else profile.apply(result)
                    avatar?.let { (path, bytes) ->
                        imageLoader.register(path, bytes)?.let(profile::applyAvatar)
                    }
                    login.loading.value = false
                    login.clearPassword()
                    stage.value = AppStage.Main
                    tab.value = MainTab.Home
                }
                loadHome()
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

    /** 控件库是全屏页，不占底部 Tab；返回时回到「我的」。 */
    private fun openComponents() {
        if (stage.value != AppStage.Main) return
        tab.value = MainTab.Components
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
                val covers = loadCovers(api, items)
                postToUi {
                    covers.forEach { (id, bytes) -> imageLoader.register(coverPath(id), bytes) }
                    home.apply(items, covers.keys)
                }
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
                val avatar = loadAvatar(api, info)
                postToUi {
                    profile.apply(info)
                    avatar?.let { (path, bytes) ->
                        imageLoader.register(path, bytes)?.let(profile::applyAvatar)
                    }
                }
            } catch (e: Throwable) {
                postToUi { if (isAuthError(e)) expireSession() }
            }
        }
    }

    /** 首屏封面：并行取前若干张，单张超时不影响整页；失败的行继续显示首字占位。 */
    private suspend fun loadCovers(api: RemoteApi, items: List<AnimeSeriesDto>): Map<Long, ByteArray> = coroutineScope {
        items.asSequence()
            .filter { it.cover != null || it.bangumiId != null }
            .take(MAX_COVERS)
            .map { item ->
                async {
                    withTimeoutOrNull(COVER_TIMEOUT_MS) { api.animeCover(item) }
                        ?.let { item.id to it }
                }
            }
            .toList()
            .awaitAll()
            .filterNotNull()
            .toMap()
    }

    /** 头像：没有头像或取件失败返回 `null`，界面继续用首字占位。 */
    private suspend fun loadAvatar(api: RemoteApi, user: UserInfoRow): Pair<String, ByteArray>? {
        if (user.avatar.isNullOrBlank()) return null
        val bytes = withTimeoutOrNull(AVATAR_TIMEOUT_MS) { api.avatar(user.id) } ?: return null
        return avatarPath(user.id) to bytes
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

/** 首屏最多预取多少张封面。 */
private const val MAX_COVERS: Int = 10

/** 单张封面 / 头像的取件上限，避免拖慢整页。 */
private const val COVER_TIMEOUT_MS: Long = 6000

private const val AVATAR_TIMEOUT_MS: Long = 4000

/** 头像在视图层资源表里的路径；与 `ProfileViewModel.applyAvatar` 约定一致。 */
private fun avatarPath(userId: String): String = "/ui/images/avatar/$userId.img"

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
