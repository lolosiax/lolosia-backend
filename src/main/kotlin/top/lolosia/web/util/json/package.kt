package top.lolosia.web.util.json

import com.fasterxml.jackson.databind.node.ObjectNode

operator fun ObjectNode.plusAssign(other: ObjectNode) {
    this.setAll<ObjectNode>(other)
}