package moe.lolosia.web.config

import moe.lolosia.web.util.config.ChainProp
import moe.lolosia.web.util.config.NodeChainConfig
import org.yaml.snakeyaml.nodes.MappingNode
import org.yaml.snakeyaml.nodes.ScalarNode


interface IOpenAiConfig {
    var type: OpenAiType
    var baseUrl: String
    var completionsPath: String
    var apiKey: String
    var model: String
    var noThink: Boolean
}

enum class OpenAiType {
    OPENAI,
    ANTHROPIC,
    DASH_SCOPE,
}

open class BaseOpenAiConfig(parent: NodeChainConfig, node: MappingNode) : NodeChainConfig(parent, node), IOpenAiConfig {
    override var baseUrl: String by notnull("")
    override var completionsPath: String by notnull("")
    override var apiKey: String by notnull("")
    override var model: String by notnull("")
    override var type: OpenAiType by notnull(OpenAiType.OPENAI)
    override var noThink: Boolean by notnull(true)
}

interface IOpenAiConfigGroups {
    val keys: Iterable<String>
    operator fun contains(key: String): Boolean
    operator fun get(key: String): IOpenAiConfig
    fun delete(key: String)
}

class OpenAiConfig(parent: NodeChainConfig, node: MappingNode) : BaseOpenAiConfig(parent, node) {
    private val defaultConfig by lazy { BaseOpenAiConfig(this, node) }

    @ChainProp("default")
    var defaultRef by notnull("default")

    override var type: OpenAiType
        get() = if (defaultRef == "default") defaultConfig.type
        else clients[defaultRef].type
        set(value) {
            if (defaultRef == "default") defaultConfig.type = value
            else clients[defaultRef].type = value
        }

    override var baseUrl: String
        get() = if (defaultRef == "default") defaultConfig.baseUrl
        else clients[defaultRef].baseUrl
        set(value) {
            if (defaultRef == "default") defaultConfig.baseUrl = value
            else clients[defaultRef].baseUrl = value
        }

    override var completionsPath: String
        get() = if (defaultRef == "default") defaultConfig.completionsPath
        else clients[defaultRef].completionsPath
        set(value) {
            if (defaultRef == "default") defaultConfig.completionsPath = value
            else clients[defaultRef].completionsPath = value
        }

    override var apiKey: String
        get() = if (defaultRef == "default") defaultConfig.apiKey
        else clients[defaultRef].apiKey
        set(value) {
            if (defaultRef == "default") defaultConfig.apiKey = value
            else clients[defaultRef].apiKey = value
        }

    override var model: String
        get() = if (defaultRef == "default") defaultConfig.model
        else clients[defaultRef].model
        set(value) {
            if (defaultRef == "default") defaultConfig.model = value
            else clients[defaultRef].model = value
        }

    override var noThink: Boolean
        get() = if (defaultRef == "default") defaultConfig.noThink
        else clients[defaultRef].noThink
        set(value) {
            if (defaultRef == "default") defaultConfig.noThink = value
            else clients[defaultRef].noThink = value
        }

    private val clientsNode = NodeChainConfig(this, getMappingNodeOrCreate("clients"))

    val clients: IOpenAiConfigGroups = object : IOpenAiConfigGroups {
        override val keys = (listOf("default") + clientsNode.node.value.mapNotNull {
            (it.keyNode as? ScalarNode)?.value
        }).toSet()

        override fun contains(key: String): Boolean {
            if (key == "default") return true
            return clientsNode.getNode(key) != null
        }

        override fun get(key: String): IOpenAiConfig {
            if (key == "default") return this@OpenAiConfig
            return BaseOpenAiConfig(clientsNode, clientsNode.getMappingNodeOrCreate(key))
        }

        override fun delete(key: String) {
            val deleted = clientsNode.node.value.removeIf { (it.keyNode as? ScalarNode)?.value == key }
            if (deleted) {
                clientsNode.save()
            }
        }
    }
}