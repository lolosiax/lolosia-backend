package moe.lolosia.web.util.event

/**
 * @author 洛洛希雅Lolosia
 * @since 2024-12-02 09:51
 */

import java.util.*

/** 事件基类 */
open class Event(val source: Any) : IEvent {
    val time: Date get() = Date()
}
