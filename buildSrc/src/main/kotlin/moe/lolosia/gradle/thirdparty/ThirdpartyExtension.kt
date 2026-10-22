package moe.lolosia.gradle.thirdparty

import org.gradle.api.file.Directory
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.TaskProvider

/**
 * 第三方原生库的解析结果。
 *
 * 每个库、每个目标都有独立的目录（`include/` + `lib/`），由 `thirdpartySync<库><目标>` 任务准备。
 * 默认从工件仓库拉取；未配置仓库时回退到主工程现有的 `thirdparty/include` 与 `thirdparty/<目标>`。
 *
 * @author 洛洛希雅Lolosia
 */
open class ThirdpartyExtension {

    var enabled: Boolean = false
        internal set

    var repoUrl: String = ""
        internal set

    var repoUsername: String = ""
        internal set

    var repoPassword: String = ""
        internal set

    private val dirs = linkedMapOf<Pair<String, String>, Provider<Directory>>()

    private val syncTasks = linkedMapOf<Pair<String, String>, TaskProvider<*>>()

    internal fun register(library: String, target: String, dir: Provider<Directory>, task: TaskProvider<*>) {
        dirs[library to target] = dir
        syncTasks[library to target] = task
    }

    /** 某库某目标的解析目录。 */
    fun dir(library: String, target: String): Provider<Directory> =
        dirs[library to target] ?: error("未声明第三方库 $library / $target")

    /** 对应的准备任务，用于让 cinterop 依赖它。 */
    fun syncTask(library: String, target: String): TaskProvider<*> =
        syncTasks[library to target] ?: error("未声明第三方库 $library / $target")

    /** 所有已声明的库。 */
    fun libraries(): Set<String> = dirs.keys.map { it.first }.toSet()

    /** 某库声明的目标。 */
    fun targets(library: String): Set<String> = dirs.keys.filter { it.first == library }.map { it.second }.toSet()
}
