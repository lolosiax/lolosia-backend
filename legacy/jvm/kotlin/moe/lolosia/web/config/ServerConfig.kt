package moe.lolosia.web.config

import moe.lolosia.web.util.config.NodeChainConfig
import org.yaml.snakeyaml.nodes.MappingNode

/**
 * 服务端配置
 *
 * @author 洛洛希雅Lolosia
 * @since 2024-12-02 09:51
 */
class ServerConfig(parent: NodeChainConfig, node: MappingNode) : NodeChainConfig(parent, node){
    var port by notnull(7129)
    var hookCssHostUrl by notnull(false)
}