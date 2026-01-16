package top.lolosia.web.util.session

import top.lolosia.web.api.SSEResult
import top.lolosia.web.manager.SessionManager
import org.springframework.context.ApplicationContext
import java.util.*

open class SSEContext(override val applicationContext: ApplicationContext) : Context() {

    constructor(ctx: Context) : this(ctx.applicationContext){
        try {
            mSessionId = ctx.sessionId
        }
        catch (_: Exception){}
    }

    private val sessionManager by lazy { applicationContext.getBean(SessionManager::class.java) }
    private val mSession by lazy { sessionManager[this.sessionId] }
    private val emptySession = SSESessionMap(mutableMapOf())
    private var mSessionId: UUID? = null

    var parentSse : SSEResult? = null

    val hasSessionId get() = mSessionId === null
    override var sessionId: UUID
        get() = mSessionId ?: throw IllegalStateException("sessionId is null")
        set(value) {
            mSessionId = value
        }

    override val session: SessionMap
        get() {
            val out = mSessionId?.let { mSession } ?: emptySession
            out["session:lastAccess"] = Date().time
            return out
        }

    inner class SSESessionMap(val map : MutableMap<String, Any?>) :SessionMap, MutableMap<String, Any?> by map {
        override fun set(key: String, value: Any?) {
            map[key] = value
        }
    }
}