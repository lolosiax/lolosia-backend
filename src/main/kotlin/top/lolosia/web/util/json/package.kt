package top.lolosia.web.util.json

import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.node.ObjectNode
import org.springframework.core.ParameterizedTypeReference
import kotlin.reflect.KType
import kotlin.reflect.jvm.javaType

operator fun ObjectNode.plusAssign(other: ObjectNode) {
    this.setAll<ObjectNode>(other)
}


fun <T> KType.typeReference(): TypeReference<T> {
    return object: TypeReference<T>() {
        override fun getType() = javaType
    }
}

fun <T> KType.parameterizedTypeReference() : ParameterizedTypeReference<T>{
    return ParameterizedTypeReference.forType(this.javaType)
}