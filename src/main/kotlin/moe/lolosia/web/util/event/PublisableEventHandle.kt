package moe.lolosia.web.util.event

/**
 * @author 洛洛希雅Lolosia
 * @since 2024-12-02 09:51
 */

/** 可发布事件的 EventHandle */
interface PublisableEventHandle<E : IEvent> : IEventHandle<E> {
    fun publish(event: E)

    infix fun fire(e: E) {
        publish(e)
    }
}
