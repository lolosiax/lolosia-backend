package moe.lolosia.web.ui.client

import kotlinx.serialization.Serializable

/** spool 文件里的一行；`ts` 是毫秒时间戳。 */
@Serializable
internal data class EventSpoolRecord(val ts: Long, val title: String, val text: String)
