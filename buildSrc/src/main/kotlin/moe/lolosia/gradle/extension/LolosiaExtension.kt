package moe.lolosia.gradle.extension

import org.gradle.api.Action
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Optional

/**
 * Lolosia 插件扩展
 * @author 洛洛希雅Lolosia
 * @since 2026-05-14 11:37:41
 */
open class LolosiaExtension {

    @Input
    @Optional
    var packageName: String? = "moe.lolosia"

    @Input
    @Optional
    var projectName: String? = "未命名的应用程序平台"

    val deploy: DeployExtension = DeployExtension()
    val viteJar: ViteJarExtension = ViteJarExtension()
    val constants: ConstantsExtension = ConstantsExtension()
    val docker: DockerExtension = DockerExtension()
    val classpath: ClasspathExtension = ClasspathExtension()
    val windows: WindowsResourceExtension = WindowsResourceExtension()

    fun deploy(action: Action<DeployExtension>) {
        action.execute(deploy)
    }

    fun viteJar(action: Action<ViteJarExtension>) {
        action.execute(viteJar)
    }

    fun constants(action: Action<ConstantsExtension>) {
        action.execute(constants)
    }

    fun docker(action: Action<DockerExtension>) {
        action.execute(docker)
    }

    fun classpath(action: Action<ClasspathExtension>) {
        action.execute(classpath)
    }

    fun windows(action: Action<WindowsResourceExtension>) {
        action.execute(windows)
    }
}