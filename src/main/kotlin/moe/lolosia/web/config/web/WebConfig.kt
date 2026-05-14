package moe.lolosia.web.config.web

import com.alibaba.cloud.ai.dashscope.api.DashScopeApi
import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatModel
import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatOptions
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.json.JsonMapper
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import moe.lolosia.web.LolosiaApplication.Companion.applicationContext
import moe.lolosia.web.config.IOpenAiConfig
import moe.lolosia.web.config.OpenAiType
import moe.lolosia.web.config.ParentConfig
import moe.lolosia.web.config.SConfig
import moe.lolosia.web.util.property.PropertyCallback
import moe.lolosia.web.util.property.asProperty
import moe.lolosia.web.util.spring.ApplicationContextProvider
import moe.lolosia.web.util.spring.ContextArgumentResolver
import moe.lolosia.web.util.spring.THYMELEAF_APP
import org.slf4j.LoggerFactory
import org.springframework.ai.anthropic.AnthropicChatModel
import org.springframework.ai.anthropic.AnthropicChatOptions
import org.springframework.ai.anthropic.api.AnthropicApi
import org.springframework.ai.chat.client.ChatClient
import org.springframework.ai.openai.OpenAiChatModel
import org.springframework.ai.openai.OpenAiChatOptions
import org.springframework.ai.openai.api.OpenAiApi
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.codec.ServerCodecConfigurer
import org.springframework.scheduling.annotation.EnableScheduling
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.web.reactive.config.EnableWebFlux
import org.springframework.web.reactive.config.WebFluxConfigurer
import org.springframework.web.reactive.result.method.annotation.ArgumentResolverConfigurer
import org.thymeleaf.spring6.web.webflux.SpringWebFluxWebApplication
import javax.jmdns.JmDNS
import javax.jmdns.ServiceInfo
import javax.jmdns.impl.JmDNSImpl

@Configuration
@EnableWebFlux
@EnableScheduling
class WebConfig : WebFluxConfigurer {

    val logger = LoggerFactory.getLogger(WebConfig::class.java)

    @Autowired
    lateinit var contextArgumentResolver: ContextArgumentResolver

    override fun configureHttpMessageCodecs(configurer: ServerCodecConfigurer) {
        configurer.defaultCodecs().maxInMemorySize(128 * 1024 * 1024)
    }

    override fun configureArgumentResolvers(configurer: ArgumentResolverConfigurer) {
        configurer.addCustomResolver(contextArgumentResolver)
    }

    @Bean
    fun getPasswordEncoder(): PasswordEncoder = BCryptPasswordEncoder(10)

    @Bean
    fun getJsonMapper() = JsonMapper().apply {
        registerModule(JavaTimeModule())
        configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
    }

    @Bean
    fun getThymeleafApplication(): SpringWebFluxWebApplication {
        return THYMELEAF_APP
    }

    @Bean
    fun getApplicationContextProvider(): ApplicationContextProvider {
        return ApplicationContextProvider { applicationContext }
    }

    private lateinit var jmDnsHook: PropertyCallback<ParentConfig.HostType>

    @Bean
    fun jmDns(): JmDNSHolder {
        // JmDNS创建可能会失败
        try {
            val jmDns = JmDNS.create()
            val serviceName = "lolosia-home-dns-service"
            val parent = SConfig.host.serviceParent

            jmDnsHook = parent::mode.asProperty().addListener {
                val service = (jmDns as JmDNSImpl).services[serviceName]

                if (it == ParentConfig.HostType.SERVER) {
                    if (service != null) return@addListener
                    val info = ServiceInfo.create(
                        "_http._tcp.local.",
                        serviceName,
                        SConfig.server.port,
                        "hello-world"
                    )
                    jmDns.registerService(info)
                } else {
                    if (service != null) {
                        jmDns.unregisterService(service)
                    }
                }
            }

            jmDnsHook.invoke(parent.mode)

            return JmDNSHolder(jmDns)
        } catch (e: Throwable) {
            logger.error("初始化JmDNS服务时发生异常", e)
            return JmDNSHolder(e = e)
        }
    }

    data class JmDNSHolder(val jmDNS: JmDNS? = null, val e: Throwable? = null) {
        operator fun invoke(): JmDNS {
            return jmDNS ?: throw e!!
        }
    }

    @Bean
    fun chatClient(): ChatClient {
        return createChatClient(SConfig.openApi)
    }

    class ChatClients(private val clients: MutableMap<String, ChatClient>) : Map<String, ChatClient> by clients

    @Bean
    fun chatClients(): ChatClients {
        val map = mutableMapOf<String, ChatClient>()

        val configClients = SConfig.openApi.clients

        configClients.keys.forEach { key ->
            if (key == "default") {
                map["default"] = chatClient()
                return@forEach
            }

            val config = configClients[key]
            try {
                map[key] = createChatClient(config)
            } catch (e: Throwable) {
                logger.error("创建ChatClient时发生异常", e)
            }
        }

        return ChatClients(map)
    }

    private fun createChatClient(config: IOpenAiConfig): ChatClient {
        val model = when (config.type) {
            OpenAiType.OPENAI -> {
                val api = OpenAiApi.builder()
                    .baseUrl(config.baseUrl)
                    .completionsPath(config.completionsPath)
                    .apiKey(config.apiKey)
                    .build()

                val options = OpenAiChatOptions.builder()
                    .model(config.model)
                    .temperature(0.7)
                    .build()

                OpenAiChatModel.builder()
                    .openAiApi(api)
                    .defaultOptions(options)
                    .build()

            }

            OpenAiType.ANTHROPIC -> {
                val api = AnthropicApi.builder()
                    .baseUrl(config.baseUrl)
                    .completionsPath(config.completionsPath)
                    .apiKey(config.apiKey)
                    .build()

                val options = AnthropicChatOptions.builder()
                    .model(config.model)
                    .temperature(0.7)
                    .maxTokens(4096)
                    .build()

                AnthropicChatModel.builder()
                    .anthropicApi(api)
                    .defaultOptions(options)
                    .build()
            }

            OpenAiType.DASH_SCOPE -> {
                val api = DashScopeApi.builder()
                    .baseUrl(config.baseUrl)
                    .completionsPath(config.completionsPath)
                    .apiKey(config.apiKey)
                    .build()

                val options = DashScopeChatOptions.builder()
                    .model(config.model)
                    .temperature(0.7)
                    .enableThinking(!config.noThink)
                    .multiModel(true)
                    .build()

                DashScopeChatModel.builder()
                    .dashScopeApi(api)
                    .defaultOptions(options)
                    .build()
            }

            else -> throw UnsupportedOperationException("OpenAiType ${config.type} is not supported")
        }

        val builder = ChatClient.builder(model)
        return builder.defaultSystem("A user has raised a question. Please answer him.")
            .build()
    }
}