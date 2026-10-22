package moe.lolosia.web.ui.router

import moe.lolosia.web.ui.state.State

/**
 * 路由栈：维护当前页与全屏栈，Tab 切换走 [selectTab]。
 *
 * 返回键 / Esc 先关全屏栈，栈空时由宿主决定是否退出。
 *
 * @param initial 初始路由。
 * @author 洛洛希雅Lolosia
 */
class Navigator(initial: Route) {

    val current = State(initial)
    val tab = State(initial)

    private val stack = mutableListOf<Route>()

    /** 压入全屏页。 */
    fun push(route: Route) {
        stack += current.value
        current.value = route
    }

    /** 返回上一页；栈空返回 `false`。 */
    fun pop(): Boolean {
        val previous = stack.removeLastOrNull() ?: return false
        current.value = previous
        return true
    }

    /** 替换当前页（登录成功 / 登出时用）。 */
    fun replace(route: Route) {
        stack.clear()
        current.value = route
        if (route.layout == LayoutType.Tab) tab.value = route
    }

    /** 切换主壳 Tab；会清空全屏栈。 */
    fun selectTab(route: Route) {
        stack.clear()
        tab.value = route
        current.value = route
    }
}
