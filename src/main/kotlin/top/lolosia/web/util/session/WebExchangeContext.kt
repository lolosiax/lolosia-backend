package top.lolosia.web.util.session

import top.lolosia.web.manager.SessionManager
import top.lolosia.web.util.bundle.Bundle
import top.lolosia.web.util.ebean.toUuid
import org.springframework.web.server.ServerWebExchange
import java.util.*

class WebExchangeContext(val exchange: ServerWebExchange) :
    SessionBasedContext(exchange.applicationContext!!, UUID.randomUUID()) {
    private val sessionManager by lazy { applicationContext.getBean(SessionManager::class.java) }

    private val mSession: Bundle by lazy {
        sessionManager.mySession(exchange) ?: throw IllegalStateException("请重新登录")
    }

    override val sessionId: UUID by lazy { session["sessionId"]!!.toString().toUuid() }

    val isWebSocket by lazy { exchange.request.headers.upgrade == "websocket" }

    override val session: Bundle
        get() {
            mSession["session:lastAccess"] = Date().time
            return mSession
        }
}