package moe.lolosia.web.util.router

import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receive
import io.ktor.server.request.receiveParameters
import kotlin.reflect.KClass
import kotlin.uuid.Uuid

/**
 * 自研路由插件的运行时：参数解析 / 按类型注入 / 表单与文件读取。
 *
 * 不依赖任何反射（Kotlin/Native 无通用反射），所有类型判断都由 KSP 在编译期完成，
 * 这里只提供编译器生成的代码所调用的工具函数。
 *
 * @author 洛洛希雅Lolosia
 */

/** 请求参数不合法或缺席（由 StatusPages 统一转成 400）。 */
public class RouterParamException(message: String) : RuntimeException(message)

// ===== 按类型注入 =====

/**
 * 可注入类型的注册表。
 *
 * 由应用在启动时注册，例如：
 * ```
 * RouterInjection.register(Context::class) { call -> call.attributes[ContextKey] }
 * ```
 * 控制器形参声明为已注册类型（或其子类型）时，**无需任何注解**即自动注入。
 */
public object RouterInjection {
    private val factories = mutableMapOf<KClass<*>, (ApplicationCall) -> Any>()

    public fun <T : Any> register(type: KClass<T>, factory: (ApplicationCall) -> T) {
        factories[type] = factory
    }

    public fun resolve(type: KClass<*>, call: ApplicationCall): Any =
        factories[type]?.invoke(call)
            ?: error("未注册的可注入类型：${type.simpleName}；请先在启动时调用 RouterInjection.register")

    public fun isRegistered(type: KClass<*>): Boolean = factories.containsKey(type)
}

/** 取按类型注册的实例（KSP 生成 `call.inject<T>()`）。 */
public inline fun <reified T : Any> ApplicationCall.inject(): T =
    RouterInjection.resolve(T::class, this) as T

// ===== 单值参数 =====

/** 取到值则返回，为 null 时抛 400。 */
public fun <T : Any> required(value: T?, name: String): T =
    value ?: throw RouterParamException("缺少必需的参数：$name")

public fun asStringOrNull(raw: String?): String? = raw

public fun asIntOrNull(raw: String?, name: String): Int? {
    if (raw == null) return null
    return raw.toIntOrNull() ?: throw RouterParamException("参数 $name 不是合法整数：$raw")
}

public fun asLongOrNull(raw: String?, name: String): Long? {
    if (raw == null) return null
    return raw.toLongOrNull() ?: throw RouterParamException("参数 $name 不是合法长整数：$raw")
}

public fun asDoubleOrNull(raw: String?, name: String): Double? {
    if (raw == null) return null
    return raw.toDoubleOrNull() ?: throw RouterParamException("参数 $name 不是合法小数：$raw")
}

public fun asBooleanOrNull(raw: String?, name: String): Boolean? {
    if (raw == null) return null
    return when (raw.lowercase()) {
        "true", "1", "yes", "on" -> true
        "false", "0", "no", "off" -> false
        else -> throw RouterParamException("参数 $name 不是合法布尔值：$raw")
    }
}

public fun asUuidOrNull(raw: String?, name: String): Uuid? {
    if (raw == null) return null
    return runCatching { Uuid.parse(raw) }
        .getOrElse { throw RouterParamException("参数 $name 不是合法 UUID：$raw") }
}

public inline fun <reified E : Enum<E>> asEnumOrNull(raw: String?, name: String): E? {
    if (raw == null) return null
    return enumValues<E>().firstOrNull { it.name == raw }
        ?: throw RouterParamException(
            "参数 $name 不是合法枚举值：$raw（可选：${enumValues<E>().joinToString(",") { it.name }}）"
        )
}

// ===== 表单与文件 =====

/** multipart / urlencoded 请求里读出的文件项。 */
public class UploadedFile(
    public val fieldName: String,
    public val fileName: String?,
    public val contentType: String?,
    public val bytes: ByteArray,
)

/** 表单字段 + 文件项。 */
public class FormData(
    public val fields: Map<String, String>,
    public val files: List<UploadedFile>,
) {
    public fun fieldOrNull(name: String): String? = fields[name]
    public fun field(name: String): String = required(fields[name], name)
    public fun fileOrNull(name: String): UploadedFile? =
        files.firstOrNull { it.fieldName == name } ?: files.firstOrNull()

    public companion object {
        public val Empty: FormData = FormData(emptyMap(), emptyList())
    }
}

/**
 * 读取表单（multipart 或 urlencoded）。
 *
 * 注意：Ktor 3.4 在 Kotlin/Native 上未实现 multipart（平台侧直接 `error(...)`），
 * 因此 multipart 分支改为读原始字节后用自研解析器处理，见 [parseMultipartBody]。
 */
public suspend fun ApplicationCall.readForm(): FormData {
    val contentType = request.headers[HttpHeaders.ContentType]?.trim().orEmpty()

    if (contentType.startsWith("multipart/", ignoreCase = true)) {
        val boundary = parseMultipartBoundary(contentType) ?: return FormData.Empty
        val body: ByteArray = receive()
        return parseMultipartBody(body, boundary)
    }

    val fields = if (contentType.startsWith(ContentType.Application.FormUrlEncoded.toString(), ignoreCase = true)) {
        runCatching { receiveParameters() }.getOrNull()
            ?.entries()?.associate { it.key to it.value.first() }
            ?: emptyMap()
    } else {
        request.queryParameters.entries().associate { it.key to it.value.first() }
    }
    return FormData(fields, emptyList())
}

/** 读取 urlencoded 表单字段（不处理 multipart）。 */
public suspend fun ApplicationCall.readFormFields(): Map<String, List<String>> {
    val params = receiveParameters()
    return params.entries().associate { it.key to it.value }
}
