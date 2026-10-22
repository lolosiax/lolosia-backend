package moe.lolosia.web.database

import io.ktor.network.selector.SelectorManager
import io.ktor.network.sockets.aSocket
import io.ktor.network.sockets.openReadChannel
import io.ktor.network.sockets.openWriteChannel
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.readAvailable
import io.ktor.utils.io.writeFully
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withTimeoutOrNull
import moe.lolosia.web.settings.AppSettings
import okio.Buffer
import okio.ByteString.Companion.toByteString

/**
 * MySQL / MariaDB 的**轻量鉴权预检**。
 *
 * sqlx4k 的建池走 Rust FFI，**账号密码错时会直接 panic 把进程打崩**（捕获不了）。所以在
 * `Database.connect()` 真正调 `mySQL(...)` 之前，这里用一条裸 TCP 连接做一次极简握手：
 * 发 `HandshakeResponse41`、看服务端回 `OK` 还是 `ERR`，以此判定「账号密码是否可用」。
 *
 * 只做判定、不建池也不查询，连上即关；结果分四类（见 [Result]），[Inconclusive] 会交回 sqlx4k
 * 走原流程，避免预检本身把能用的库挡在门外。
 *
 * 支持 `mysql_native_password`（MariaDB 默认）与 `caching_sha2_password`（MySQL 8 快路径）。
 * 之外的情形（TLS 强制、要求缓存全量认证、认证插件切换）一律判 [Inconclusive]。
 *
 * @author 洛洛希雅Lolosia
 */
internal object MySqlAuthProbe {

    /** 预检结论。 */
    sealed interface Result {
        /** 握手成功：账号密码可用，可放心交给 sqlx4k 建池。 */
        data object Ok : Result

        /** 主机不可达 / 超时：直接判失败，省掉 sqlx4k 那约 30s 的阻塞。 */
        data class Unreachable(val message: String) : Result

        /** 鉴权失败 / 库不存在等**确定性**配置错误：不要再调 sqlx4k（会 panic）。 */
        data class Denied(val code: Int, val message: String) : Result

        /** 超出预检能力（协议不认识 / 需要 TLS / 未知插件）：交回 sqlx4k 处理。 */
        data class Inconclusive(val message: String) : Result
    }

    /** 用数据源配置做预检。URL 解析不了时判 [Result.Inconclusive]。 */
    suspend fun probe(config: AppSettings.DatasourceConfig, timeoutMillis: Long = DEFAULT_TIMEOUT_MS): Result {
        val (host, port, database) = parseUrl(config.url)
            ?: return Result.Inconclusive("无法解析数据库 URL：${config.url}")
        return probe(host, port, config.username, config.password, database, timeoutMillis)
    }

    /** 对 `host:port` 做一次鉴权预检。 */
    suspend fun probe(
        host: String,
        port: Int,
        username: String,
        password: String,
        database: String,
        timeoutMillis: Long = DEFAULT_TIMEOUT_MS,
    ): Result {
        val outcome = withTimeoutOrNull(timeoutMillis) {
            runCatching { handshake(host, port, username, password, database) }
                .getOrElse { Result.Unreachable(it.message ?: it.toString()) }
        }
        return outcome ?: Result.Unreachable("连接或握手超时（${timeoutMillis}ms）")
    }

    // ===== 协议实现 =====

    private suspend fun handshake(
        host: String,
        port: Int,
        username: String,
        password: String,
        database: String,
    ): Result {
        val selector = SelectorManager(Dispatchers.Default)
        try {
            val socket = aSocket(selector).tcp().connect(host, port)
            try {
                val input = socket.openReadChannel()
                val output = socket.openWriteChannel(autoFlush = true)

                val greeting = readPacket(input).second
                if (greeting.isEmpty()) {
                    return Result.Inconclusive("服务端问候包为空")
                }
                // 个别情况下服务端直接回 ERR（如 host 未授权），按错误分类
                if ((greeting[0].toInt() and 0xff) == PACKET_ERR) return classifyError(greeting)
                if (greeting[0] != PROTOCOL_VERSION) {
                    return Result.Inconclusive("非预期握手包（0x${hex(greeting.firstOrNull())}）")
                }
                val server = parseGreeting(greeting)

                val plugin = server.authPlugin.ifEmpty { NATIVE_PLUGIN }
                val token = authToken(plugin, password, server.nonce) ?: ByteArray(0)
                writePacket(output, 1, buildResponse(server, username, token, database, plugin))
                var (replySeq, reply) = readPacket(input)

                repeat(MAX_ROUNDS) {
                    when (reply.firstOrNull()?.toInt()?.and(0xff)) {
                        null -> return Result.Inconclusive("服务端应答为空")

                        PACKET_OK -> return Result.Ok

                        PACKET_ERR -> return classifyError(reply)

                        PACKET_AUTH_MORE ->
                            // caching_sha2 的快路径：0x01 0x03 之后紧跟一个 OK 包
                            if (reply.size >= 2 && (reply[1].toInt() and 0xff) == AUTH_MORE_FAST_OK) {
                                val (s, p) = readPacket(input)
                                replySeq = s
                                reply = p
                            } else {
                                return Result.Inconclusive("服务端要求缓存全量认证（需 TLS/RSA）")
                            }

                        PACKET_AUTH_SWITCH -> {
                            val switched = parseAuthSwitch(reply)
                                ?: return Result.Inconclusive("无法解析 AuthSwitchRequest")
                            val switchedToken = authToken(switched.plugin, password, switched.nonce)
                            if (switchedToken == null) {
                                // 预检不支持的插件。MariaDB 在「账号或口令有误」时会切到 auth_gssapi_client
                                // 之类的插件（防枚举），这类确定性失败若交给 sqlx4k 会 panic —— 直接判失败。
                                return Result.Denied(0, "服务端要求认证插件 ${switched.plugin}（预检不支持；多见于账号或口令有误）")
                            }
                            // 应答序号在收到的包基础上 +1
                            writePacket(output, replySeq + 1, switchedToken)
                            val (s, p) = readPacket(input)
                            replySeq = s
                            reply = p
                        }

                        else -> return Result.Inconclusive("未知应答类型 0x${hex(reply[0])}")
                    }
                }
                return Result.Inconclusive("握手往返次数超限")
            } finally {
                socket.close()
            }
        } finally {
            selector.close()
        }
    }

    private class Greeting(val nonce: ByteArray, val capabilities: Int, val charset: Int, val authPlugin: String)

    private fun parseGreeting(p: ByteArray): Greeting {
        var i = 1 // 跳过 protocol version
        while (i < p.size && p[i] != 0.toByte()) i++ // server version
        i++
        i += 4 // connection id
        val part1 = p.copyOfRange(i, i + 8)
        i += 8
        i += 1 // filler
        val capsLower = le16(p, i)
        i += 2
        val charset = p[i].toInt() and 0xff
        i += 1
        i += 2 // status flags
        val capsUpper = le16(p, i)
        i += 2
        val capabilities = capsLower or (capsUpper shl 16)
        val authDataLen = if (i < p.size) p[i].toInt() and 0xff else 0
        i += 1
        i += 10 // reserved

        var part2 = ByteArray(0)
        if (capabilities and CLIENT_SECURE_CONNECTION != 0 && i < p.size) {
            val len = maxOf(13, authDataLen - 8)
            part2 = p.copyOfRange(i, minOf(p.size, i + len))
            i += len
        }

        // nonce = part1 + part2（去掉尾部 NUL），标准 20 字节
        val p2 = if (part2.isNotEmpty() && part2.last() == 0.toByte()) part2.copyOf(part2.size - 1) else part2
        val nonce = (part1 + p2).let { if (it.size > 20) it.copyOf(20) else it }

        var authPlugin = ""
        if (capabilities and CLIENT_PLUGIN_AUTH != 0 && i < p.size) {
            val start = i
            while (i < p.size && p[i] != 0.toByte()) i++
            authPlugin = p.decodeToString(start, i)
        }

        return Greeting(nonce, capabilities, if (charset == 0) DEFAULT_CHARSET else charset, authPlugin)
    }

    private class AuthSwitch(val plugin: String, val nonce: ByteArray)

    private fun parseAuthSwitch(p: ByteArray): AuthSwitch? {
        var i = 1 // 跳过 0xfe
        val start = i
        while (i < p.size && p[i] != 0.toByte()) i++
        if (i >= p.size) return null
        val plugin = p.decodeToString(start, i)
        i++
        var nonce = p.copyOfRange(i, p.size)
        if (nonce.isNotEmpty() && nonce.last() == 0.toByte()) nonce = nonce.copyOf(nonce.size - 1)
        return AuthSwitch(plugin, nonce)
    }

    private fun buildResponse(
        server: Greeting,
        username: String,
        token: ByteArray,
        database: String,
        plugin: String,
    ): ByteArray {
        val buf = Buffer()
        buf.writeIntLe(CLIENT_CAPS)
        buf.writeIntLe(MAX_PACKET_SIZE)
        buf.writeByte(server.charset)
        repeat(23) { buf.writeByte(0) }
        buf.writeUtf8(username)
        buf.writeByte(0)
        buf.writeByte(token.size)
        buf.write(token)
        if (database.isNotEmpty()) {
            buf.writeUtf8(database)
            buf.writeByte(0)
        }
        buf.writeUtf8(plugin)
        buf.writeByte(0)
        return buf.readByteArray()
    }

    private fun classifyError(p: ByteArray): Result {
        val code = le16(p, 1)
        // ERR 包：0xff + errno(2) + '#' + sqlstate(5) + message
        val message = if (p.size > 9) p.decodeToString(9, p.size) else ""
        return if (code in DENIED_CODES) {
            Result.Denied(code, "ERR $code：$message")
        } else {
            Result.Inconclusive("ERR $code：$message")
        }
    }

    private fun authToken(plugin: String, password: String, nonce: ByteArray): ByteArray? {
        if (password.isEmpty()) return ByteArray(0)
        if (nonce.isEmpty()) return null
        val pwd = password.encodeToByteArray().toByteString()
        return when (plugin) {
            NATIVE_PLUGIN -> {
                val stage1 = pwd.sha1()
                val stage2 = stage1.sha1()
                val stage3 = (nonce + stage2.toByteArray()).toByteString().sha1()
                xor(stage1.toByteArray(), stage3.toByteArray())
            }

            CACHING_SHA2_PLUGIN -> {
                val stage1 = pwd.sha256()
                val stage2 = stage1.sha256()
                val stage3 = (stage2.toByteArray() + nonce).toByteString().sha256()
                xor(stage1.toByteArray(), stage3.toByteArray())
            }

            else -> null
        }
    }

    // ===== 字节/IO 小工具 =====

    /** 读一个 MySQL 包，返回「序号 to 载荷」。 */
    private suspend fun readPacket(input: ByteReadChannel): Pair<Int, ByteArray> {
        val header = input.readExact(4)
        val length = (header[0].toInt() and 0xff) or
            ((header[1].toInt() and 0xff) shl 8) or
            ((header[2].toInt() and 0xff) shl 16)
        val seq = header[3].toInt() and 0xff
        return seq to input.readExact(length)
    }

    private suspend fun writePacket(output: ByteWriteChannel, seq: Int, payload: ByteArray) {
        val header = byteArrayOf(
            (payload.size and 0xff).toByte(),
            ((payload.size ushr 8) and 0xff).toByte(),
            ((payload.size ushr 16) and 0xff).toByte(),
            seq.toByte(),
        )
        output.writeFully(header)
        output.writeFully(payload)
        output.flush()
    }

    private suspend fun ByteReadChannel.readExact(size: Int): ByteArray {
        val buf = ByteArray(size)
        var offset = 0
        while (offset < size) {
            val read = readAvailable(buf, offset, size - offset)
            if (read < 0) throw IllegalStateException("连接在读取 $size 字节时中断")
            offset += read
        }
        return buf
    }

    /** 解析 `mysql://host:port/database[?opts]`。 */
    private fun parseUrl(url: String): Triple<String, Int, String>? {
        val rest = url.substringAfter("://", "")
        if (rest.isEmpty()) return null
        val withoutQuery = rest.substringBefore('?')
        val authority = withoutQuery.substringBefore('/')
        val database = withoutQuery.substringAfter('/', "").substringBefore('/')
        val hostPort = authority.substringAfterLast('@')
        val host = hostPort.substringBeforeLast(':', hostPort)
        if (host.isEmpty()) return null
        val port = hostPort.substringAfterLast(':', "").toIntOrNull() ?: DEFAULT_PORT
        return Triple(host, port, database)
    }

    private fun le16(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xff) or ((bytes[offset + 1].toInt() and 0xff) shl 8)

    private fun xor(a: ByteArray, b: ByteArray): ByteArray {
        val out = ByteArray(minOf(a.size, b.size))
        for (i in out.indices) out[i] = (a[i].toInt() xor b[i].toInt()).toByte()
        return out
    }

    private fun hex(byte: Byte?) = (byte?.toInt()?.and(0xff) ?: 0).toString(16).padStart(2, '0')

    private const val DEFAULT_PORT = 3306
    private const val DEFAULT_CHARSET = 0x21
    private const val DEFAULT_TIMEOUT_MS = 5_000L
    private const val MAX_ROUNDS = 4
    private const val MAX_PACKET_SIZE = 16 * 1024 * 1024

    private const val NATIVE_PLUGIN = "mysql_native_password"
    private const val CACHING_SHA2_PLUGIN = "caching_sha2_password"

    private const val PROTOCOL_VERSION: Byte = 10
    private const val PACKET_OK = 0x00
    private const val PACKET_ERR = 0xff
    private const val PACKET_AUTH_MORE = 0x01
    private const val PACKET_AUTH_SWITCH = 0xfe
    private const val AUTH_MORE_FAST_OK = 0x03

    private const val CLIENT_LONG_PASSWORD = 0x00000001
    private const val CLIENT_LONG_FLAG = 0x00000004
    private const val CLIENT_CONNECT_WITH_DB = 0x00000008
    private const val CLIENT_PROTOCOL_41 = 0x00000200
    private const val CLIENT_TRANSACTIONS = 0x00002000
    private const val CLIENT_SECURE_CONNECTION = 0x00008000
    private const val CLIENT_PLUGIN_AUTH = 0x00080000

    private const val CLIENT_CAPS = CLIENT_LONG_PASSWORD or CLIENT_LONG_FLAG or CLIENT_CONNECT_WITH_DB or
        CLIENT_PROTOCOL_41 or CLIENT_TRANSACTIONS or CLIENT_SECURE_CONNECTION or CLIENT_PLUGIN_AUTH

    /**
     * 属「配置写错」的确定性错误，交给 sqlx4k 也只会失败（甚至 panic），故直接判 [Result.Denied]：
     * - 1044/1045 账号或库无权限、1049 库不存在
     * - 1251 客户端不支持服务端要求的认证协议
     * - 1130 客户端主机不被允许
     * - 3159 服务端要求安全传输（TLS），而连接是非 TLS 的
     */
    private val DENIED_CODES = setOf(1044, 1045, 1049, 1130, 1251, 3159)
}
