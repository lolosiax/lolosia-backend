package moe.lolosia.web.event.system

class UserLoginEvent(source: Any, val data: Map<String, Any?>, val sessionId: String) : SystemEvent(source)
class UserLogoutEvent(source: Any, val session: Map<String, Any?>, val sessionId: String) : SystemEvent(source)