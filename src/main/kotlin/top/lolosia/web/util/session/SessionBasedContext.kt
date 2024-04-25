package top.lolosia.web.util.session

import top.lolosia.web.manager.SessionManager
import top.lolosia.web.util.bundle.Bundle
import org.springframework.context.ApplicationContext
import java.util.*

open class SessionBasedContext(
    override val applicationContext: ApplicationContext,
    override val sessionId: UUID
) : Context() {
    private val sessionManager by lazy { applicationContext.getBean(SessionManager::class.java) }
    private val mSession by lazy { sessionManager[this.sessionId] }
    override val session: Bundle
        get() {
            mSession["session:lastAccess"] = Date().time
            return mSession
        }
}