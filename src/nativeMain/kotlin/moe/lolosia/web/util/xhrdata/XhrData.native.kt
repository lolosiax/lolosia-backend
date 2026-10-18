package moe.lolosia.web.util.xhrdata

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ULongVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.get
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.pointed
import kotlinx.cinterop.ptr
import kotlinx.cinterop.readBytes
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.sizeOf
import kotlinx.cinterop.toKString
import kotlinx.cinterop.usePinned
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import moe.lolosia.thirdparty.msgpack.MSGPACK_OBJECT_ARRAY
import moe.lolosia.thirdparty.msgpack.MSGPACK_OBJECT_BIN
import moe.lolosia.thirdparty.msgpack.MSGPACK_OBJECT_BOOLEAN
import moe.lolosia.thirdparty.msgpack.MSGPACK_OBJECT_FLOAT32
import moe.lolosia.thirdparty.msgpack.MSGPACK_OBJECT_FLOAT64
import moe.lolosia.thirdparty.msgpack.MSGPACK_OBJECT_MAP
import moe.lolosia.thirdparty.msgpack.MSGPACK_OBJECT_NEGATIVE_INTEGER
import moe.lolosia.thirdparty.msgpack.MSGPACK_OBJECT_NIL
import moe.lolosia.thirdparty.msgpack.MSGPACK_OBJECT_POSITIVE_INTEGER
import moe.lolosia.thirdparty.msgpack.MSGPACK_OBJECT_STR
import moe.lolosia.thirdparty.msgpack.MSGPACK_UNPACK_SUCCESS
import moe.lolosia.thirdparty.msgpack.msgpack_object
import moe.lolosia.thirdparty.msgpack.msgpack_unpack_next
import moe.lolosia.thirdparty.msgpack.msgpack_unpacked
import moe.lolosia.thirdparty.msgpack.msgpack_zone_free
import platform.zlib.Z_NO_FLUSH
import platform.zlib.Z_OK
import platform.zlib.Z_STREAM_END
import platform.zlib.inflate
import platform.zlib.inflateEnd
import platform.zlib.inflateInit2_
import platform.zlib.z_stream
import platform.zlib.zlibVersion

/**
 * 通道正文还原：把前 4 字节改回 gzip 魔数 → gunzip → msgpack 解包。
 *
 * 见 [XHR_DATA_CONTENT_TYPE]（`doc/anime-timetable-design.md` §4.4）。
 */
@OptIn(ExperimentalForeignApi::class)
internal actual fun decodeXhrData(bytes: ByteArray): JsonElement {
    check(bytes.size >= 4) { "通道正文过短：${bytes.size}" }
    val patched = bytes.copyOf()
    patched[0] = 0x1f
    patched[1] = 0x8b.toByte()
    patched[2] = 0x08
    patched[3] = 0x00
    val raw = gunzip(patched)
    return memScoped {
        // alloc 已零初始化，等价于 msgpack_unpacked_init（后者是 static inline，cinterop 不导出）
        val unpacked = alloc<msgpack_unpacked>()
        try {
            val off = alloc<ULongVar>()
            raw.usePinned { pinned ->
                val rc = msgpack_unpack_next(unpacked.ptr, pinned.addressOf(0), raw.size.convert(), off.ptr)
                check(rc == MSGPACK_UNPACK_SUCCESS) { "msgpack 解析失败：rc=$rc" }
            }
            jsonOf(unpacked.ptr.pointed.data)
        } finally {
            // 等价于 msgpack_unpacked_destroy：释放 unpacker 为本次解析分配的 zone
            unpacked.ptr.pointed.zone?.let { msgpack_zone_free(it) }
        }
    }
}

/** gzip 解压（`windowBits = 15 + 16`，自动识别 gzip 头）；输出大小未知，按块增长。 */
@OptIn(ExperimentalForeignApi::class)
private fun gunzip(input: ByteArray): ByteArray = memScoped {
    val strm = alloc<z_stream>()
    // inflateInit2_ 只校验 version 首字符与 stream_size，取运行时版本最稳（同 ZipClasspath）
    val version = zlibVersion()?.toKString() ?: "1.2.0"
    val init = inflateInit2_(strm.ptr, 15 + 16, version, sizeOf<z_stream>().convert())
    check(init == Z_OK) { "inflateInit2(gzip) 失败：rc=$init" }
    try {
        val chunks = ArrayList<ByteArray>()
        val buf = ByteArray(16 * 1024)
        var done = false
        input.usePinned { inPinned ->
            strm.next_in = inPinned.addressOf(0).reinterpret()
            strm.avail_in = input.size.convert()
            while (!done) {
                var produced = 0
                buf.usePinned { outPinned ->
                    strm.next_out = outPinned.addressOf(0).reinterpret()
                    strm.avail_out = buf.size.convert()
                    val rc = inflate(strm.ptr, Z_NO_FLUSH)
                    check(rc == Z_OK || rc == Z_STREAM_END) { "inflate 失败：rc=$rc" }
                    produced = buf.size - strm.avail_out.toInt()
                    if (produced > 0) chunks += buf.copyOf(produced)
                    if (rc == Z_STREAM_END) done = true
                }
                if (!done && produced == 0 && strm.avail_in.toInt() == 0) error("inflate 数据不完整")
            }
        }
        val total = chunks.sumOf { it.size }
        val result = ByteArray(total)
        var pos = 0
        for (c in chunks) {
            c.copyInto(result, pos)
            pos += c.size
        }
        result
    } finally {
        inflateEnd(strm.ptr)
    }
}

/** msgpack 对象 → [JsonElement]（只覆盖我们的信封用到的类型：nil/bool/int/float/str/bin/array/map）。 */
@OptIn(ExperimentalForeignApi::class)
private fun jsonOf(o: msgpack_object): JsonElement = when (o.type) {
    MSGPACK_OBJECT_NIL -> JsonNull
    MSGPACK_OBJECT_BOOLEAN -> JsonPrimitive(o.via.boolean)
    MSGPACK_OBJECT_POSITIVE_INTEGER -> JsonPrimitive(o.via.u64.toLong())
    MSGPACK_OBJECT_NEGATIVE_INTEGER -> JsonPrimitive(o.via.i64)
    MSGPACK_OBJECT_FLOAT32, MSGPACK_OBJECT_FLOAT64 -> JsonPrimitive(o.via.f64)
    MSGPACK_OBJECT_STR, MSGPACK_OBJECT_BIN ->
        JsonPrimitive(o.via.str.ptr?.readBytes(o.via.str.size.toInt())?.decodeToString() ?: "")
    MSGPACK_OBJECT_ARRAY -> {
        val arr = o.via.array
        JsonArray((0 until arr.size.toInt()).map { jsonOf(arr.ptr!![it]) })
    }
    MSGPACK_OBJECT_MAP -> {
        val map = o.via.map
        buildJsonObject {
            for (i in 0 until map.size.toInt()) {
                val kv = map.ptr!![i]
                // `val` 是 Kotlin 关键字，C 结构体字段同名，需反引号访问
                put(jsonOf(kv.key).jsonPrimitive.content, jsonOf(kv.`val`))
            }
        }
    }
    else -> JsonNull
}
