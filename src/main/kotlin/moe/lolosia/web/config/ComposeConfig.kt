package moe.lolosia.web.config

import moe.lolosia.web.util.config.NodeChainConfig
import org.yaml.snakeyaml.nodes.MappingNode

class ComposeConfig(parent: NodeChainConfig, node: MappingNode) : NodeChainConfig(parent, node) {
    /** 在启动后打开浏览器 */
    var openBrowserOnStart by notnull(true)
}