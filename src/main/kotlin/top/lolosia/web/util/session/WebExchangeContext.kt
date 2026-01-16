package top.lolosia.web.util.session

import top.lolosia.web.manager.SessionManager
import top.lolosia.web.util.bundle.Bundle
import top.lolosia.web.util.ebean.toUuid
import org.springframework.web.server.ServerWebExchange
import java.util.*

class WebExchangeContext(override val exchange: ServerWebExchange) :
    SessionBasedContext(exchange.applicationContext!!, UUID.randomUUID()),
    IWebExchangeContext {

    private val sessionManager by lazy { applicationContext.getBean(SessionManager::class.java) }

    private val mSession: SessionMap by lazy {
        sessionManager.mySession(exchange) ?: throw IllegalStateException("请重新登录")
    }

    override val sessionId: UUID by lazy { session["sessionId"]!!.toString().toUuid() }

    override val session: SessionMap
        get() {
            mSession["session:lastAccess"] = Date().time
            return mSession
        }
}