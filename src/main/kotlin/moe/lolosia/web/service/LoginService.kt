package moe.lolosia.web.service

import cn.hutool.captcha.CaptchaUtil
import cn.hutool.captcha.generator.MathGenerator
import cn.hutool.core.math.Calculator
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import moe.lolosia.web.event.system.UserLoginEvent
import moe.lolosia.web.event.system.UserLogoutEvent
import moe.lolosia.web.manager.CacheManager
import moe.lolosia.web.manager.SessionManager
import moe.lolosia.web.model.system.query.QSysUserEntity
import moe.lolosia.web.service.system.UserService
import moe.lolosia.web.util.bundle.*
import moe.lolosia.web.util.ebean.or
import moe.lolosia.web.util.ebean.query
import moe.lolosia.web.util.email.sendMail
import moe.lolosia.web.util.session.Context
import moe.lolosia.web.util.success
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.ApplicationEventPublisher
import org.springframework.http.ResponseEntity
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder
import org.springframework.stereotype.Service
import org.thymeleaf.spring6.SpringWebFluxTemplateEngine
import moe.lolosia.web.model.system.ViewUserRoleEntity
import moe.lolosia.web.util.ebean.applyDatabase
import moe.lolosia.web.util.ebean.toAsync
import moe.lolosia.web.util.session.IWebExchangeContext
import moe.lolosia.web.util.spring.ThymeleafContext
import java.awt.Color
import java.util.Base64
import kotlin.time.Duration.Companion.minutes
import kotlin.toString

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

    @Autowired
    lateinit var templateEngine: SpringWebFluxTemplateEngine

    @Autowired
    lateinit var cacheManager: CacheManager

    @Autowired
    lateinit var userService: UserService

    suspend fun login(ctx: Context, userName: String, password: String?): Bundle {
        val user = ctx.query<QSysUserEntity> {
            or {
                this.userName.eq(userName)
                this.phone.eq(userName)
            }
            deleted.ne(true)
        }.toAsync().findOne() ?: throw NoSuchElementException("用户不存在！")
        var firstLogin = false
        if (user.password.isNullOrEmpty() && password.isNullOrEmpty()) {
            firstLogin = true
        }
        // 判断密码是否正确
        else if (user.password.isNullOrEmpty() || !encoder.matches(password, user.password)) {
            throw IllegalAccessException("密码不正确")
        }
        val obj = session.get()
        obj["id"] = user.id.toString()

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

    private val captchaCache by lazy { cacheManager["/system/login/captcha"] }

    suspend fun captcha(ctx: Context): Bundle {
        ctx as IWebExchangeContext
        val captcha = CaptchaUtil.createLineCaptcha(100, 40)
        captcha.setBackground(Color.BLACK)
        captcha.generator = MathGenerator()
        captcha.createCode()
        val result = Calculator.conversion(captcha.code).toInt()

        val cache = captchaCache.create {
            this.tag = ctx.clientAddressString
            this.value = result.toString()
        }

        val image = Base64.getEncoder().encodeToString(captcha.imageBytes)
        return bundleScope {
            "image" set image
            "id" set cache.id
        }
    }

    // 邮件验证
    val emailVerifyCache by lazy { cacheManager["/system/login/email-verify"] }

    /***
     * 进行人机验证并发送邮件
     */
    suspend fun verify(
        ctx: Context,
        id: Int,
        code: String,
        userName: String,
        email: String
    ): ResponseEntity<ByteArray> {
        ctx as IWebExchangeContext

        val cache = captchaCache.query {
            this.id.eq(id)
        }.findOne()

        cache ?: throw IllegalArgumentException("验证码已过期")
        if (cache.value != code) throw IllegalArgumentException("验证码不正确")
        val hasUser = ctx.query<QSysUserEntity> {
            or {
                this.userName.eq(userName)
                this.email.eq(email)
            }
        }.exists()
        if (hasUser) throw IllegalArgumentException("用户名或邮箱已存在")
        cache.applyDatabase(ctx)
        cache.delete()

        // 邮件验证码
        val code1 = Math.random().toString().removePrefix("0.").substring(0 until 6)
        emailVerifyCache.put(email, code1, 5.minutes)

        val ctx1 = ThymeleafContext(ctx.exchange)

        val map = bundleScope {
            "code" set code1
            "userName" set userName
            "email" set email
            "emailBase64" set Base64.getUrlEncoder().encodeToString(email.toByteArray())
            "server" set ctx.serverHostString
        }

        ctx1.setVariables(map)

        val html = templateEngine.process("email-verify", ctx1)
        ctx.sendMail(email, "用户注册", html)

        return success()
    }


    suspend fun register(
        ctx: Context,
        userName: String,
        password: String,
        email: String,
        code: String
    ): ViewUserRoleEntity {
        if (code != emailVerifyCache[email]) throw IllegalArgumentException("邮件验证码不正确")

        val out = userService.create(ctx, bundleScope {
            "userName" set userName
            "password" set password
            "email" set email
            "roleId" set 4
        })

        emailVerifyCache.remove(email)

        return out
    }
}