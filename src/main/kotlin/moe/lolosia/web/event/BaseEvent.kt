package moe.lolosia.web.event

import moe.lolosia.web.util.bundle.bundleOf
import moe.lolosia.web.util.event.Event

open class BaseEvent(source: Any) : Event(source) {
    val attached = bundleOf()
}