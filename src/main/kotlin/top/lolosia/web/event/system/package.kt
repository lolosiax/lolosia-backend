package top.lolosia.web.event.system

import top.lolosia.web.util.bundle.Bundle


class UserLoginEvent(source: Any, val data: Bundle, val sessionId: String) : SystemEvent(source)
class UserLogoutEvent(source: Any, val session: Bundle, val sessionId: String) : SystemEvent(source)