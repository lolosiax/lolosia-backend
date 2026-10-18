package moe.lolosia.web.util.xhrdata

import kotlinx.serialization.json.JsonElement

/**
 * 自定义请求通道的媒体类型：`application/vnd.lolosia.xhr.data`。
 *
 * 与前端基座 `utils/request.ts`、各后端 `MsgPackWebFilter` 是同一套口径 ——
 * 正文 = `JSON → msgpack → gzip`，再把前 10 字节用随机数盖掉；解码端把前 4 字节改回
 * gzip 魔数 `1f 8b 08 00` 即可还原（见 `doc/anime-timetable-design.md` §4.4）。
 */
const val XHR_DATA_CONTENT_TYPE: String = "application/vnd.lolosia.xhr.data"

/**
 * 还原通道正文：gzip 解压后再按 msgpack 解出 [JsonElement]。
 *
 * 实现只在 native 源集（依赖 `platform.zlib` 与 thirdparty 的 msgpack-c）。
 */
expect fun decodeXhrData(bytes: ByteArray): JsonElement
