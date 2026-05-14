package moe.lolosia.web.config

import moe.lolosia.web.util.bundle.Bundle
import moe.lolosia.web.util.bundle.bundle
import moe.lolosia.web.util.bundle.bundleOf
import moe.lolosia.web.util.config.ChangeableMap

class ResourcesConfig(override val data: Bundle) : ChangeableMap() {

    private val keys by lazy { data.keys.toList().sortedBy { -it.length } }

    operator fun get(key: String): Entity {
        val matched = keys.firstOrNull { key.startsWith(it) } ?: ""
        return Entity(matched)
    }

    inner class Entity(key: String) : ChangeableMap() {
        override val data = this@ResourcesConfig.data.bundle(key) ?: bundleOf()

        /**
         * 转发模式。
         *
         * pass: 直接前往资源文件夹，
         * redirect：进行302跳转，
         * proxy：通过服务器直接进行转发。
         */
        val mode by this("pass")

        /**
         * 重定向到的服务器地址
         */
        val redirect : String? by this(null)
        // val overrider : String? by this(null)
    }
}