package top.lolosia.web.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import top.lolosia.web.event.system.UserLoginEvent
import top.lolosia.web.event.system.UserLogoutEvent
import top.lolosia.web.manager.SessionManager
import top.lolosia.web.model.system.query.QSysUserEntity
import top.lolosia.web.util.session.Context
import top.lolosia.web.util.bundle.*
import top.lolosia.web.util.ebean.or
import top.lolosia.web.util.success
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.ApplicationEventPublisher
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder
import org.springframework.stereotype.Service

@Service
class LoginService {

    companion object {
        private val encoder = BCryptPasswordEncoder(10)
        private val mapper = ObjectMapper().apply {
            registerModule(JavaTimeModule())
        }
    }

    @Autowired
    lateinit var session: SessionManager

    @Autowired
    lateinit var publisher: ApplicationEventPublisher

    suspend fun login(ctx: Context, userName: String, password: String?): Bundle {
        val user = QSysUserEntity().run {
            or {
                this.userName.eq(userName)
                this.phone.eq(userName)
            }
            deleted.ne(true)
            findOne()
        } ?: throw NoSuchElementException("用户不存在！")
        var firstLogin = false
        if (user.password.isNullOrEmpty() && password.isNullOrEmpty()) {
            firstLogin = true
        }
        // 判断密码是否正确
        else if (user.password.isNullOrEmpty() || !encoder.matches(password, user.password)) {
            throw IllegalAccessException("密码不正确")
        }
        val obj = session.get().scope {
            "id" set user.id.toString()
        }
        session.save(obj["sessionId"]!!.toString())
        val out = bundleScope {
            current.putAll(mapper.toBundle(user))
            current.putAll(obj)
            "Authorization" set obj["sessionId"]
            "firstLogin" set firstLogin
            "password" set null
        }

        publisher.publishEvent(UserLoginEvent(this, out, obj("sessionId")!!))
        return out
    }

    @Suppress("NAME_SHADOWING")
    suspend fun logout(ctx: Context, sessionId: String? = null): Any {
        val sessionId = try {
            sessionId ?: ctx.sessionId.toString()
        } catch (_: Throwable) {
            return success("SessionId哪去了？")
        }
        val out = if (session.contains(sessionId)) session[sessionId]
        else bundleOf()
        session.remove(sessionId)
        publisher.publishEvent(UserLogoutEvent(this, out, sessionId))
        return success()
    }
}