package moe.lolosia.web.util.session

import io.ktor.util.*

/** `call.attributes` 里存放 [Context] 的键。 */
val ContextKey: AttributeKey<Context> = AttributeKey("ctx")
