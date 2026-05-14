package moe.lolosia.web.config

import moe.lolosia.web.util.config.NodeChainConfig
import org.yaml.snakeyaml.nodes.MappingNode

class OpenAiConfig(parent: NodeChainConfig, node: MappingNode) : NodeChainConfig(parent, node) {
    var baseUrl: String by notnull("https://dashscope.aliyuncs.com/compatible-mode")
    var apiKey: String by notnull("123456")
    var model: String by notnull("qwen-plus")

    var noThink: Boolean by notnull(false)
}