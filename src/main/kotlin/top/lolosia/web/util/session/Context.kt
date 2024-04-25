package top.lolosia.web.util.session

import top.lolosia.web.util.ErrorResponseException
import top.lolosia.web.util.bundle.Bundle
import top.lolosia.web.util.bundle.invoke
import top.lolosia.web.util.ebean.toUuid
import org.springframework.context.ApplicationContext
import org.springframework.http.HttpStatus
import java.util.*
import kotlin.reflect.KProperty

abstract class Context {
    abstract val applicationContext: ApplicationContext
    abstract val session: Bundle
    protected val proxy = proxy()
    protected fun proxy(prop: String? = null) = SessionProxy(prop)

    //
    // 变量表
    //

    open val sessionId: UUID get() = session["sessionId"]!!.toString().toUuid()
    val userId: String
        get() = session("id") ?: throw ErrorResponseException(
            HttpStatus.UNAUTHORIZED,
            "身份认证失败，请重新登录"
        )

    val user = UserInfo(this)

    /** 决策竞赛实例ID */
    var decisionGameId: String? by proxy
    var decisionTeamId: String? by proxy

    override fun equals(other: Any?): Boolean {
        return if (other is Context) other.sessionId == sessionId
        else super.equals(other)
    }

    override fun hashCode(): Int {
        return sessionId.hashCode()
    }

    //
    //  Session代理对象
    //

    protected class SessionProxy(private val propName: String? = null) {
        @Suppress("UNCHECKED_CAST")
        operator fun <T> getValue(target: Context, prop: KProperty<*>): T {
            return target.session[propName ?: prop.name] as T
        }

        operator fun <T> setValue(target: Context, prop: KProperty<*>, value: T) {
            target.session[propName ?: prop.name] = value
        }
    }
}