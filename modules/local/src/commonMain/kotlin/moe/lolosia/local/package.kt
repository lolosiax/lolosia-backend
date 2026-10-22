package moe.lolosia.local

/** 本地服务版本；与 UI 的展示对齐。 */
const val LOCAL_SERVICE_VERSION: String = "1.0.0"

/** 构建标识。 */
const val LOCAL_SERVICE_BUILD: String = "native"

/** v1 对外声明的能力标识。 */
val LOCAL_SERVICE_CAPABILITIES: List<String> = listOf("metrics", "version")
