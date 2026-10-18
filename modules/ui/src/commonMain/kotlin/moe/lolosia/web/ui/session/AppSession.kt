package moe.lolosia.web.ui.session

import moe.lolosia.web.ui.state.State

/**
 * 当前登录会话：token 与用户名。
 *
 * 远程请求的 `Authorization` 由 RemoteApi 显式接收 token，不从这里隐式读取，
 * 避免本地服务或 WebView 误用远程会话。
 *
 * @author 洛洛希雅Lolosia
 */
class AppSession {

    val token = State<String?>(null)
    val userName = State<String?>(null)

    val loggedIn: Boolean get() = !token.value.isNullOrEmpty()

    fun clear() {
        token.value = null
        userName.value = null
    }
}
