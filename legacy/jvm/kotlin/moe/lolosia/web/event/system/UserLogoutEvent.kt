package moe.lolosia.web.event.system

import moe.lolosia.web.util.bundle.Bundle

/**
 * 用户登出事件
 *
 * @author 洛洛希雅Lolosia
 * @since 2026-01-16 17:46
 */
class UserLogoutEvent(source: Any, val bundle: Bundle, val sessionId: String) : SystemEvent(source)
