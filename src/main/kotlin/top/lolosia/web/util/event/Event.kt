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

import org.springframework.context.ApplicationEvent
import java.util.*

/**
 * 事件基类
 *
 * @author 一七年夏
 * @since 2021-09-19 13:10
 */
open class Event(source: Any) : ApplicationEvent(source) {
    val time get() = Date(timestamp)
}