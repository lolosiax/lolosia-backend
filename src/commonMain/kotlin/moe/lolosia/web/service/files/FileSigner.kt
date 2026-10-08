package moe.lolosia.web.service.files

import kotlin.uuid.Uuid
import moe.lolosia.web.settings.AppSettings
import moe.lolosia.web.util.log.Log
import moe.lolosia.web.util.router.Injectable
import moe.lolosia.web.util.router.Service
import moe.lolosia.web.util.time.nowEpochMs
import okio.ByteString
import okio.ByteString.Companion.encodeUtf8

/**
 * 缩略图 / 原图**直链**的签名器（HMAC-SHA256）。
 *
 * 为什么需要：这两个接口是给 `<img src>` 直接访问的，浏览器原生标签没有地方放
 * `Authorization` 头，所以不能靠会话鉴权。改成 URL 签名后，只有先经过 `files/list`、
 * `files/detail`（都要 `lolosia.files.read` 权限）拿到 `exp` + `sig` 的请求才放行，
 * 光知道路径猜不出签名。
 *
 * 签名只覆盖**相对路径 + 过期时间**，不含尺寸档位 —— 同一个签名可以取该文件的所有档位
 * 缩略图与原始文件；有效期由 [AppSettings.FilesConfig.signTtlSeconds] 控制。
 *
 * @author 洛洛希雅Lolosia
 */
@Injectable
@Service
class FileSigner(settings: AppSettings) {

    private val log = Log.of("Files")

    private val key: ByteString
    private val ttlMillis: Long

    init {
        val configured = settings.files.signKey.trim()
        if (configured.isEmpty()) {
            // 不落库、不持久化：重启即换钥匙，旧直链自然失效（前端重新列一次目录即可）
            key = Uuid.random().toString().encodeUtf8()
            log.warn {
                "files.signKey 未配置，已为本进程生成随机签名密钥；重启后旧直链全部失效。" +
                    "需要跨重启保持有效请显式配置 signKey。"
            }
        } else {
            key = configured.encodeUtf8()
        }
        ttlMillis = settings.files.signTtlSeconds.coerceAtLeast(60).toLong() * 1000L
        log.info { "直链签名有效期：${ttlMillis / 1000}s" }
    }

    /** 现在签发的话，过期时间戳（毫秒）。 */
    fun expireAt(): Long = nowEpochMs() + ttlMillis

    /** 计算签名（十六进制小写）。 */
    fun sign(relative: String, expiresAt: Long): String =
        "$relative|$expiresAt".encodeUtf8().hmacSha256(key).hex()

    /**
     * 校验签名与有效期。
     *
     * 比较用等长的整串异或累加（不做提前返回），避免按字节长度/位置泄露信息。
     */
    fun verify(relative: String, expiresAt: Long?, sig: String?): Boolean {
        if (expiresAt == null || sig.isNullOrEmpty()) return false
        if (nowEpochMs() > expiresAt) return false
        return constantTimeEquals(sign(relative, expiresAt), sig.lowercase())
    }

    private fun constantTimeEquals(a: String, b: String): Boolean {
        if (a.length != b.length) return false
        var diff = 0
        for (i in a.indices) diff = diff or (a[i].code xor b[i].code)
        return diff == 0
    }
}
