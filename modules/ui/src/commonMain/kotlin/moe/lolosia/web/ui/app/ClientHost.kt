package moe.lolosia.web.ui.app

import moe.lolosia.web.ui.platform.UiBackend
import moe.lolosia.web.ui.state.State

/**
 * 把 [ClientApp] 的状态绑到视图层，并在每帧结束后同步表单与文档。
 *
 * 字段绑定必须在第一个文档加载前完成；切换文档时模型字段会保留。
 *
 * @param app 应用状态。
 * @param backend 视图层后端。
 * @param onError 绑定 / 加载失败时的日志出口。
 * @author 洛洛希雅Lolosia
 */
class ClientHost(
    private val app: ClientApp,
    private val backend: UiBackend,
    private val onError: (String) -> Unit,
) {

    private val subscriptions = mutableListOf<State.Subscription>()
    private var currentDocumentKey: String? = null

    fun start() {
        bindModel()
        backend.setActionHandler(app::onAction)
        app.bootstrap()
        syncDocument()
    }

    /** 每帧结束：先读回表单，再按阶段 / Tab 切换文档。 */
    fun onFrameEnd() {
        syncForm()
        syncDocument()
    }

    fun dispose() {
        subscriptions.forEach { it.cancel() }
        subscriptions.clear()
    }

    private fun bindModel() {
        bindText("loginUserName", app.login.userName)
        bindText("loginPassword", app.login.password)
        bindText("baseUrl", app.login.baseUrl)
        bindBool("loginLoading", app.login.loading)
        bindBool("loginHasError", app.login.hasError)
        bindText("loginError", app.login.error)

        bindText("displayName", app.profile.displayName)
        bindText("avatarInitial", app.profile.avatarInitial)
        bindText("realName", app.profile.realName)
        bindText("email", app.profile.email)
        bindText("phone", app.profile.phone)
        bindText("roleName", app.profile.roleName)
        bindText("localStatus", app.profile.localStatus)
        bindText("localCpu", app.profile.localCpu)
        bindText("localMem", app.profile.localMem)
        bindText("localDisk", app.profile.localDisk)

        bindText("homeStatus", app.home.status)
        bindBool("homeLoading", app.home.loading)
        bindBool("homeHasError", app.home.hasError)
        bindBool("homeEmpty", app.home.empty)
        bindText("homeError", app.home.error)

        backend.bindBool("loggedIn", app.stage.value == AppStage.Main)
        backend.bindBool("tabHome", app.tab.value == MainTab.Home)
        backend.bindBool("tabProfile", app.tab.value == MainTab.Profile)
        subscriptions += app.stage.observe { stage ->
            backend.setBool("loggedIn", stage == AppStage.Main)
        }
        subscriptions += app.tab.observe { tab ->
            backend.setBool("tabHome", tab == MainTab.Home)
            backend.setBool("tabProfile", tab == MainTab.Profile)
        }
    }

    private fun bindText(name: String, state: State<String>) {
        if (!backend.bindString(name, state.value)) onError("声明数据模型字段失败：$name")
        subscriptions += state.observe { backend.setString(name, it) }
    }

    private fun bindBool(name: String, state: State<Boolean>) {
        if (!backend.bindBool(name, state.value)) onError("声明数据模型字段失败：$name")
        subscriptions += state.observe { backend.setBool(name, it) }
    }

    /** 表单控件是双向绑定：等本帧事件处理完再读回，避免读到写之前的旧值。 */
    private fun syncForm() {
        if (app.stage.value != AppStage.Login) return
        backend.getString("loginUserName")?.let {
            if (it != app.login.userName.value) app.login.userName.value = it
        }
        backend.getString("loginPassword")?.let {
            if (it != app.login.password.value) app.login.password.value = it
        }
        backend.getString("baseUrl")?.let {
            if (it != app.login.baseUrl.value) app.login.baseUrl.value = it
        }
    }

    private fun syncDocument() {
        val path = app.documentPath()
        val isHome = app.stage.value == AppStage.Main &&
            app.tab.value == MainTab.Home &&
            app.home.document.value.isNotEmpty()
        val key = if (isHome) "home:${app.home.version.value}" else path
        if (key == currentDocumentKey) return

        val loaded = if (isHome) {
            backend.loadDocumentText(app.home.document.value)
        } else {
            backend.loadDocument(path)
        }
        if (!loaded) {
            onError("加载文档失败：$key")
            return
        }
        currentDocumentKey = key
    }
}
