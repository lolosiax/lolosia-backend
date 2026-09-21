package moe.lolosia.web.util.session

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.uri
import io.ktor.server.request.httpMethod
import io.ebean.Database
import io.ebean.Transaction
import io.ebean.typequery.IQueryBean
import moe.lolosia.web.util.ErrorResponseException
import moe.lolosia.web.util.ebean.AbstractModel
import moe.lolosia.web.util.ebean.toUuid
import moe.lolosia.web.util.ebean.transaction
import java.util.*

/**
 * 请求级上下文。
 *
 * 每个请求由 ctx 绑定中间件创建一次，通过 `call.attributes` 注入，控制器用
 * ktorfitx 的 `@Attribute("ctx")` 获取。
 *
 * @author 洛洛希雅Lolosia
 * @since 2024-04-25 10:01
 */
abstract class Context : IWebExchangeContext {
    abstract val session: SessionMap
    protected val proxy = proxy()
    protected fun proxy(prop: String? = null) = SessionProxy(prop)

    //
    // 变量表
    //

    open val sessionId: UUID get() = session["sessionId"]!!.toString().toUuid()
    val userId: String
        get() = session("id") ?: throw ErrorResponseException(
            HttpStatusCode.Unauthorized,
            "身份认证失败，请重新登录"
        )
    val userIdOrNull: UUID?
        get() = try {
            session.invoke<String>("id")?.toUuid()
        } catch (e: Exception) {
            null
        }

    val user = UserInfo(this)

    inline operator fun <R> invoke(block: Context.() -> R): R {
        return block(this)
    }

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
        operator fun <T> getValue(target: Context, prop: kotlin.reflect.KProperty<*>): T {
            return target.session[propName ?: prop.name] as T
        }

        operator fun <T> setValue(target: Context, prop: kotlin.reflect.KProperty<*>, value: T) {
            target.session[propName ?: prop.name] = value
        }
    }
}

/** 请求级 Context 在 call.attributes 中的键 */
val ContextKey = io.ktor.util.AttributeKey<Context>("ctx")

// ===== 数据库访问扩展：ctx.query<Q...>() / ctx.createModel<T>() / ctx.database / ctx.transaction =====

inline fun <reified T : IQueryBean<*, *>> Context.query(): T = moe.lolosia.web.util.ebean.query()
inline fun <reified T : IQueryBean<*, *>> Context.query(block: T.() -> Unit): T = moe.lolosia.web.util.ebean.query(block)
inline fun <reified T : AbstractModel> Context.createModel(block: T.() -> Unit = {}): T = moe.lolosia.web.util.ebean.createModel(block)
val Context.database: Database get() = moe.lolosia.web.util.ebean.database()
inline fun <R> Context.transaction(block: (tran: Transaction) -> R): R = database.transaction(block)
