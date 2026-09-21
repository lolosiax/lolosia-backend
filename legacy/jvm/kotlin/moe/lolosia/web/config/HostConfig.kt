package moe.lolosia.web.config

import moe.lolosia.web.util.config.ChainProp
import moe.lolosia.web.util.config.NodeChainConfig
import org.yaml.snakeyaml.nodes.MappingNode
import kotlin.io.path.Path
import kotlin.io.path.absolutePathString

/**
 * 主机配置
 *
 * @author 洛洛希雅Lolosia
 * @since 2024-12-02 09:51
 */

class HostConfig(parent: NodeChainConfig, node: MappingNode) : NodeChainConfig(parent, node) {

    val serviceParent by lazy { ParentConfig(this, getMappingNodeOrCreate("parent")) }

    /**
     * 程序在主机上的工作目录。
     * 此目录指持有Docker的主机，若未配置，则为当前程序运行路径。
     */
    @ChainProp("host-work-dir", writeDefault = false)
    var workDir: String by notnull {
        Path("").absolutePathString().replace("\\", "/")
    }
}