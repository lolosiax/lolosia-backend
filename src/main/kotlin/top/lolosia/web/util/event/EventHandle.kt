/*
 * Copyright (C) 2021 一七年夏
 *
 * The part of program is free: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published
 * by the Free Software Foundation, either version 3 of the License,
 * or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see https://www.gnu.org/licenses/
 */
package top.lolosia.web.util.event

import top.lolosia.web.util.packageLogger

/**
 * 事件控制器
 *
 * @author 一七年夏
 * @since 2021-09-19 13:15
 */
open class EventHandle<E : IEvent> : PublisableEventHandle<E> {

    companion object{
        val logger = packageLogger<EventHandle<*>>()
    }

    private var callbacks = mutableSetOf<IEventRunnable<E>>()
    private var queue = ArrayDeque<() -> Unit>()
    private var running = false
    override fun addListener(runnable: IEventRunnable<E>) {
        if (running) queue.add { callbacks.add(runnable) }
        else callbacks.add(runnable)
    }

    override fun removeListener(runnable: IEventRunnable<E>) {
        if (running) queue.add { callbacks.remove(runnable) }
        else callbacks.remove(runnable)
    }

    override fun publish(event: E) {
        running = true
        try {
            for (runnable in callbacks) {
                try {
                    runnable.onEvent(event)
                } catch (e: Throwable) {
                    logger.warn("EventHandle执行中发生异常", e)
                }
            }
            while (queue.isNotEmpty()) queue.removeFirst().invoke()
        } catch (e: Throwable) {
            throw e
        } finally {
            running = false
        }
    }

    override val eventListeners: Iterable<IEventRunnable<E>> get() = callbacks.toList()

    fun clear(){
        callbacks.clear()
    }
}