package moe.lolosia.web.util.event

/**
 * @author 洛洛希雅Lolosia
 * @since 2026-08-30 15:33
 */

import kotlin.reflect.KClass

/**
 * 轻量事件总线。
 * 按事件类型注册监听器，发布时精确匹配事件类。
 */
class EventBus {
    private val handles = mutableMapOf<KClass<out IEvent>, IEventHandle<out IEvent>>()

    @Suppress("UNCHECKED_CAST")
    fun <E : IEvent> handle(clazz: KClass<E>): EventHandle<E> {
        return handles.getOrPut(clazz) { EventHandle<E>() } as EventHandle<E>
    }

    /** 注册监听器 */
    inline fun <reified E : IEvent> on(noinline block: (E) -> Unit) {
        handle(E::class).addListener(block)
    }

    /** 发布事件 */
    @Suppress("UNCHECKED_CAST")
    fun publish(event: IEvent) {
        (handles.getOrPut(event::class) { EventHandle<IEvent>() } as EventHandle<IEvent>).publish(event)
    }
}
