package moe.lolosia.web.config

import com.fasterxml.jackson.annotation.JsonValue
import moe.lolosia.web.util.config.NodeChainConfig
import org.yaml.snakeyaml.nodes.MappingNode

/**
 * 父级服务设置
 * @author 洛洛希雅Lolosia
 * @since 2024-10-27 15:11
 */
class ParentConfig(parent: NodeChainConfig, node: MappingNode) : NodeChainConfig(parent, node) {
    var mode by notnull(HostType.SERVER)

    /** 选中的服务器地址 */
    var selected: String? by default(null)

    var records: List<String> by notnull(listOf())

    val rootUrl: String
        get() {
            if (selected == null) throw NoSuchElementException("Parent server has not been loaded yet!")
            return "http://${selected}"
        }

    val baseUrl: String get() = "${rootUrl}/api"

    enum class HostType(@JsonValue val value: String) {
        CLIENT("client"),
        SERVER("server"),
    }
}