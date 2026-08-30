package moe.lolosia.web.util.property

import kotlin.reflect.KProperty0
import kotlin.reflect.jvm.isAccessible

/**
 * 触发式可监听属性
 *
 * @author 洛洛希雅Lolosia
 * @since 2024-08-28 16:24
 */
class TriggerProperty<T>(private val getter: () -> T) : AbstractTriggerProperty<T>() {

    companion object {

        /**
         * 触发 TriggerProperty 的更新
         */
        fun trigger(prop: KProperty0<*>): TriggerProperty<*> {
            prop.isAccessible = true
            val prop1 = prop.getDelegate() as TriggerProperty<*>
            prop1.trigger()
            return prop1
        }
    }

    override val rawValue: T
        get() = getter()

    public override fun trigger() {
        super.trigger()
    }
}