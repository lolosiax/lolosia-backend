package moe.lolosia.gradle.task

import groovy.lang.Closure
import org.gradle.api.DefaultTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.TaskAction
import org.gradle.kotlin.dsl.*
import org.hidetake.groovy.ssh.core.Remote
import org.hidetake.groovy.ssh.core.RunHandler
import org.hidetake.groovy.ssh.core.Service
import org.hidetake.groovy.ssh.session.SessionHandler

/**
 * 部署任务
 * @author 洛洛希雅Lolosia
 * @since 2026-05-14 11:37:42
 */
open class DeployTask : DefaultTask() {

    @Input
    var serverName: String = ""

    @Input
    var serverHost: String = "localhost"

    @Input
    var serverPort: Int = 22

    @Input
    var serverUser: String = "root"

    @Input
    var serverPassword: String = "123456"

    @Input
    @org.gradle.api.tasks.Optional
    var serverPwd: String? = null

    @TaskAction
    @Suppress("UNCHECKED_CAST")
    fun deploy() {
        val remote = Remote(mutableMapOf(
            "name" to serverName,
            "host" to serverHost,
            "port" to serverPort,
            "user" to serverUser,
            "password" to serverPassword
        ) as MutableMap<String, Any?>)

        val sshService = project.extensions.getByType(Service::class.java)

        val basePwd = serverPwd ?: "/srv/${project.rootProject.name}"
        val pwd = "$basePwd/docker_build/"

        sshService.run(delegateClosureOf<RunHandler> {
            session(remote, delegateClosureOf<SessionHandler> {
                execute("mkdir -p $pwd")
                execute("cd $pwd && rm -rf *-fat.jar")

                put(hashMapOf(
                    "from" to project.rootDir.resolve("Dockerfile"),
                    "into" to "${pwd}Dockerfile"
                ))

                val bootJarProvider = project.tasks.named("bootJar")
                val jarFile = bootJarProvider.get().outputs.files.singleFile
                put(hashMapOf(
                    "from" to jarFile,
                    "into" to "$pwd${jarFile.name}"
                ))

                val exits = execute(
                    hashMapOf("ignoreError" to true),
                    "cd $pwd && ls | cat"
                ).split("\n").any { it == "docker_shell.sh" }

                if (!exits) {
                    put(hashMapOf(
                        "from" to project.rootDir.resolve("docker_shell.sh"),
                        "into" to "${pwd}docker_shell.sh"
                    ))
                    execute("sed -i 's/\\r//g' ${pwd}docker_shell.sh")
                }

                execute("cd $pwd && bash docker_shell.sh")
            } as Closure<Any>)
        })
    }
}