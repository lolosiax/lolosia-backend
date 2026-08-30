package moe.lolosia.web.event

import moe.lolosia.web.util.event.IEvent

/**
 * 事件基类。
 * 实现 [IEvent]，使系统事件（[moe.lolosia.web.event.system.SystemEvent] 及其子类）可被 [moe.lolosia.web.util.event.EventBus] 发布。
 *
 * @author 洛洛希雅Lolosia
 * @since 2026-05-14 21:48
 */
open class BaseEvent(val source: Any) : IEvent
