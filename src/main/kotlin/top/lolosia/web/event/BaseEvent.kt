package top.lolosia.web.event

import top.lolosia.web.util.bundle.bundleOf
import top.lolosia.web.util.event.Event

open class BaseEvent(source: Any) : Event(source) {
    val attached = bundleOf()
}