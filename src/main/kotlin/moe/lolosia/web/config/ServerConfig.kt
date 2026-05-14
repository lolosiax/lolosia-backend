package moe.lolosia.web.config

import moe.lolosia.web.util.config.NodeChainConfig
import org.yaml.snakeyaml.nodes.MappingNode

class ServerConfig(parent: NodeChainConfig, node: MappingNode) : NodeChainConfig(parent, node){
    var port by notnull(7031)
    var hookCssHostUrl by notnull(false)
}