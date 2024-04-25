package top.lolosia.web.event

import top.lolosia.web.util.bundle.bundleOf
import org.springframework.context.ApplicationEvent

open class BaseEvent(source: Any) : ApplicationEvent(source) {
    val attached = bundleOf()
}