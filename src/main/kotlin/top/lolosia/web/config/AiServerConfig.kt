package top.lolosia.web.config

import top.lolosia.web.util.bundle.Bundle
import top.lolosia.web.util.bundle.invoke
import top.lolosia.web.util.config.ChainProp
import top.lolosia.web.util.config.ChangeableMap
import top.lolosia.web.util.config.NodeChainConfig
import org.springframework.ai.mcp.client.common.autoconfigure.properties.McpSseClientProperties
import org.yaml.snakeyaml.nodes.MappingNode
import kotlin.collections.mapValues

class AiServerConfig(parent: NodeChainConfig, node: MappingNode) : NodeChainConfig(parent, node) {

    val docker = DockerConfig(this, getMappingNodeOrCreate("docker"))
    val ssh = SSHConfig(getBundleOrCreate("ssh"))
    val embedding = EmbeddingConfig(getBundleOrCreate("embedding"))
    val milvus = MilvusConfig(getBundleOrCreate("milvus"))
    val mcp = getBundleOrCreate("mcp").mapValues { (k, v) ->
        v as Bundle
        McpSseClientProperties.SseParameters(v("url"), v("sseEndpoint"))
    }

    val elastic = ElasticConfig(getBundleOrCreate("elastic"))

    class DockerConfig(parent: NodeChainConfig, node: MappingNode) : NodeChainConfig(parent, node) {
        var host by notnull("tcp://localhost:2375")
    }

    class SSHConfig(override val data: Bundle) : ChangeableMap() {
        val host by this("localhost")
        val port by this(22)
        val user by this("root")
        val password by this("123456")
    }

    class ElasticConfig(override val data: Bundle) : ChangeableMap() {
        val url by this("https://localhost:9201")
        val user by this("elastic")
        val password by this("")
    }

    class EmbeddingConfig(override val data: Bundle) : ChangeableMap() {
        val baseUrl by this("http://172.17.0.1:7027/")
        val apiKey by this("123456")
        val model by this("jina-embeddings-v3")
    }

    class MilvusConfig(override val data: Bundle) : ChangeableMap() {
        val host by this("localhost")
        val port by this(19530)
    }
}