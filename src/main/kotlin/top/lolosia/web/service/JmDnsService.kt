package top.lolosia.web.service

import top.lolosia.web.config.web.WebConfig
import top.lolosia.web.util.property.MutableProperty
import top.lolosia.web.util.property.TriggerProperty
import jakarta.annotation.PostConstruct
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Service
import javax.jmdns.ServiceEvent
import javax.jmdns.ServiceListener

@Service
class JmDnsService : ServiceListener {

    @Autowired
    lateinit var jmDNS: WebConfig.JmDNSHolder
    private val mServices = mutableSetOf<String>()
    val services by TriggerProperty { mServices.toList() }
    final var isSupported by MutableProperty(false)
        private set

    @PostConstruct
    fun init() {
        if (jmDNS.jmDNS == null) return
        jmDNS().addServiceListener("_http._tcp.local.", this)
        isSupported = true
    }

    override fun serviceAdded(event: ServiceEvent) {}

    override fun serviceRemoved(event: ServiceEvent) {
        val ip = event.info.inet4Addresses.firstOrNull() ?: return
        val port = event.info.port
        mServices.remove("${ip.hostAddress}:$port")
        TriggerProperty.trigger(::services)
    }

    override fun serviceResolved(event: ServiceEvent) {
        if (event.name != "exp-ai-dns-service") return
        val ip = event.info.inet4Addresses.firstOrNull() ?: return
        val port = event.info.port
        mServices.add("${ip.hostAddress}:$port")
        TriggerProperty.trigger(::services)
    }
}