/*
 * Copyright (C) 2022 一七年夏
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
 *  可发布事件的EventHandle
 *
 * @author 一七年夏
 * @since 2022-04-16 19:35
 */
interface PublisableEventHandle<E : IEvent> : IEventHandle<E> {
    /**
     * 发布一个事件到所有侦听器
     * @param event 事件
     */
    fun publish(event: E)

    /**
     * 发布一个事件到所有侦听器
     * @param event 事件
     */
    infix fun fire(e: E) {
        publish(e)
    }
}