package moe.lolosia.web.util.config

import moe.lolosia.web.util.kotlin.pass
import org.yaml.snakeyaml.DumperOptions
import org.yaml.snakeyaml.nodes.*
import org.yaml.snakeyaml.nodes.NodeId.*


abstract class NodeChainConfigRoot : NodeChainConfig(null, null) {
    override val mapper = ConfigYaml()

    abstract override val node: MappingNode
    abstract override fun save()

    protected fun beforeSaving(){
        beforeSavingNode(node)
    }

    private fun beforeSavingNode(node: Node){
        when(node.nodeId){
            mapping -> beforeSavingMap(node as MappingNode)
            sequence -> beforeSavingSeq(node as SequenceNode)
            scalar -> beforeSavingScalar(node as ScalarNode)
            else -> pass
        }
    }

    private fun beforeSavingMap(node: MappingNode){
        node.tag = Tag.MAP
        node.flowStyle = DumperOptions.FlowStyle.BLOCK
        node.value.forEach {
            beforeSavingNode(it.keyNode)
            beforeSavingNode(it.valueNode)
        }
    }
    private fun beforeSavingSeq(node: SequenceNode){
        node.tag = Tag.SEQ
        node.flowStyle = DumperOptions.FlowStyle.BLOCK
        node.value.forEach(::beforeSavingNode)
    }

    private fun beforeSavingScalar(node: ScalarNode){
        if (node.tag !in Tag.standardTags){
            node.tag = Tag.STR
        }
    }
}