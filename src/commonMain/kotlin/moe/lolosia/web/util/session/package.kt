package moe.lolosia.web.util.session

import io.ktor.util.AttributeKey

/** `call.attributes` 里存放 [Context] 的键。 */
public val ContextKey: AttributeKey<Context> = AttributeKey("ctx")
