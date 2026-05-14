package moe.lolosia.web.config

import moe.lolosia.web.util.bundle.bundleOf
import moe.lolosia.web.util.config.NodeChainConfigRoot
import org.yaml.snakeyaml.nodes.MappingNode
import java.io.StringReader
import kotlin.io.path.Path
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writer

object SConfig : NodeChainConfigRoot() {

    private const val CONFIG_FILE = "application.yaml"
    private var initialized = false
    private var initializeNeedSave = false

    override val node: MappingNode by lazy { init() }
    val host by lazy { HostConfig(this, node) }
    val aiServer by lazy { AiServerConfig(this, getMappingNodeOrCreate("ai-server")) }
    val server by lazy { ServerConfig(this, getMappingNodeOrCreate("server")) }
    val openApi by lazy { OpenAiConfig(this, getMappingNodeOrCreate("openApi")) }

    private fun init(): MappingNode {
        if (!Path(CONFIG_FILE).exists()) {
            return mapper.represent(bundleOf()) as MappingNode
        }
        val text = Path(CONFIG_FILE).readText()
        return mapper.compose(StringReader(text)) as MappingNode
    }

    override fun save() {
        if (!initialized) {
            initializeNeedSave = true
            return
        }
        beforeSaving()
        Path(CONFIG_FILE).writer(Charsets.UTF_8).use {
            mapper.serialize(node, it)
        }
    }

    init {
        initialized = true
        if (initializeNeedSave) {
            save()
        }
    }
}