package moe.lolosia.thirdparty

/**
 * 单个第三方库子项目的声明。
 *
 * 版本、头文件、各目标静态库清单统一来自 `thirdparty/manifest.json`；
 * 这里只声明库 id 与可选的源码构建脚本。
 *
 * @author 洛洛希雅Lolosia
 */
open class ThirdpartyLibraryExtension {

    /** 与 manifest.json 的 libraries 键一致，如 zlib / png / sdl3。 */
    var id: String? = null

    /** 源码构建脚本，相对子项目目录；默认 `build.ps1`，不存在则不注册 buildFromSource。 */
    var buildScript: String? = "build.ps1"

    /** 显式声明库 id。 */
    fun id(value: String) {
        id = value
    }

    /** 显式声明源码构建脚本。 */
    fun buildScript(value: String) {
        buildScript = value
    }
}
