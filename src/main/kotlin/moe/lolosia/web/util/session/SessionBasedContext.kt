package moe.lolosia.web.util.session

import moe.lolosia.web.manager.SessionManager
import moe.lolosia.web.util.bundle.Bundle
import org.springframework.context.ApplicationContext
import java.util.*

open class SessionBasedContext(
    override val applicationContext: ApplicationContext,
    override val sessionId: UUID
) : Context() {
    private val sessionManager by lazy { applicationContext.getBean(SessionManager::class.java) }
    private val mSession by lazy { sessionManager[this.sessionId] }
    override val session: SessionMap
        get() {
            mSession["session:lastAccess"] = Date().time
            return mSession
        }
}