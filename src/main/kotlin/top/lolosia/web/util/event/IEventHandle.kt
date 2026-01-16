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

/**
 * 事件处理器接口
 *
 * @author 一七年夏
 * @since 2021-09-19 12:59
 */
interface IEventHandle<E : IEvent> {
    /**
     * 添加一个事件回调到侦听器
     * @param runnable 事件回调
     */
    fun addListener(runnable: IEventRunnable<E>)

    /**
     * 获取全部侦听器
     */
    val eventListeners: Iterable<IEventRunnable<E>>

    /**
     * 移除一个侦听器回调
     * @param runnable 回调
     */
    fun removeListener(runnable: IEventRunnable<E>)


    /**
     * 移除一个侦听器回调
     * @param runnable 回调
     */
    operator fun minusAssign(runnable: IEventRunnable<E>) {
        removeListener(runnable)
    }

    /**
     * 添加一个事件回调到侦听器
     * @param runnable 事件回调
     */
    operator fun plusAssign(runnable: IEventRunnable<E>) {
        addListener(runnable)
    }

    /**
     * 添加一个事件回调到侦听器
     * @param runnable 事件回调
     */
    operator fun plusAssign(runnable: (e: E) -> Unit) {
        addListener(runnable)
    }

    /**
     * 添加一个事件回调到侦听器
     * @param runnable 事件回调
     */
    fun addListener(runnable: (e: E) -> Unit) {
        addListener(KtLambdaEventRunnable<E>().apply {
            this.runnable = runnable
        })
    }

    /**
     * 移除一个侦听器回调
     * @param runnable 回调
     */
    fun removeListener(runnable: (e: E) -> Unit) {
        for (lis in this.eventListeners) {
            if (lis is KtLambdaEventRunnable) {
                if (lis == runnable) removeListener(lis as IEventRunnable)
                break
            }
        }
    }

    /**
     * 移除一个侦听器回调
     * @param runnable 回调
     */
    operator fun minusAssign(runnable: (e: E) -> Unit) {
        removeListener(runnable)
    }

}
