package top.lolosia.web.config

import top.lolosia.web.util.config.NodeChainConfig
import org.yaml.snakeyaml.nodes.MappingNode

class ServerConfig(parent: NodeChainConfig, node: MappingNode) : NodeChainConfig(parent, node){
    var port by notnull(7051)
}