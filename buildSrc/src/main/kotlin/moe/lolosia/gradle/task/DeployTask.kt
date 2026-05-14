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
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import java.util.zip.ZipFile

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

        val bootJarProvider = project.tasks.named("bootJar")
        val jarFile = bootJarProvider.get().outputs.files.singleFile

        val tempDir = Files.createTempDirectory("deploy-extract").toFile()
        try {
            val slimJarFile = File(tempDir, "slim.jar")
            val libDir = File(tempDir, "libs")
            libDir.mkdirs()

            val libManifest = mutableListOf<LibEntry>()

            ZipFile(jarFile).use { zip ->
                val slimEntries = linkedMapOf<String, ByteArray>()

                val entries = zip.entries()
                while (entries.hasMoreElements()) {
                    val entry = entries.nextElement()
                    if (entry.isDirectory) continue

                    val bytes = zip.getInputStream(entry).use { it.readBytes() }

                    if (entry.name.startsWith("BOOT-INF/lib/")) {
                        val sha256 = computeSha256(bytes)
                        val libRelativePath = entry.name.removePrefix("BOOT-INF/lib/")
                        libManifest.add(LibEntry(sha256, entry.name, libRelativePath))
                        File(libDir, sha256).writeBytes(bytes)
                    } else {
                        slimEntries[entry.name] = bytes
                    }
                }

                JarOutputStream(slimJarFile.outputStream()).use { jos ->
                    slimEntries.forEach { (name, bytes) ->
                        jos.putNextEntry(JarEntry(name))
                        jos.write(bytes)
                        jos.closeEntry()
                    }
                }
            }

            sshService.run(delegateClosureOf<RunHandler> {
                session(remote, delegateClosureOf<SessionHandler> {
                    val homeDir = execute("echo \$HOME").trim()
                    val libCacheDir = "$homeDir/.cache/lolosia-gradle-cache/"

                    execute("mkdir -p $pwd")
                    execute("mkdir -p $libCacheDir")
                    execute("cd $pwd && rm -rf *-fat.jar slim.jar extracted")
                    execute("(command -v unzip >/dev/null && command -v zip >/dev/null) || (echo '$serverPassword' | sudo -S bash -c 'apt-get update -qq && apt-get install -y -qq unzip zip')")

                    put(hashMapOf(
                        "from" to project.rootDir.resolve("Dockerfile"),
                        "into" to "${pwd}Dockerfile"
                    ))

                    put(hashMapOf(
                        "from" to slimJarFile,
                        "into" to "${pwd}slim.jar"
                    ))

                    val cachedFiles = execute("ls $libCacheDir 2>/dev/null").trim().lines().toSet()

                    for (lib in libManifest) {
                        if (lib.sha256 !in cachedFiles) {
                            put(hashMapOf(
                                "from" to File(libDir, lib.sha256),
                                "into" to "$libCacheDir${lib.sha256}"
                            ))
                        }
                    }

                    execute("cd $pwd && mkdir -p extracted && unzip -o slim.jar -d extracted/")
                    execute("mkdir -p ${pwd}extracted/BOOT-INF/lib/")

                    val copyScriptFile = File(tempDir, "copy_libs.sh")
                    copyScriptFile.writeText(buildString {
                        appendLine("#!/usr/bin/env bash")
                        appendLine("cd ${pwd}extracted/BOOT-INF/lib/")
                        for (lib in libManifest) {
                            appendLine("cp $libCacheDir${lib.sha256} ./${lib.libRelativePath}")
                        }
                    })
                    put(hashMapOf(
                        "from" to copyScriptFile,
                        "into" to "${pwd}copy_libs.sh"
                    ))
                    execute("bash ${pwd}copy_libs.sh")
                    execute("rm -f ${pwd}copy_libs.sh")

                    val finalJarName = jarFile.name
                    execute("cd ${pwd}extracted && zip -0Xr ../$finalJarName .")
                    execute("cd $pwd && rm -rf extracted/ slim.jar")

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
        } finally {
            tempDir.deleteRecursively()
        }
    }

    private fun computeSha256(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest(bytes).joinToString("") { "%02x".format(it) }
    }

    private data class LibEntry(
        val sha256: String,
        val originalPath: String,
        val libRelativePath: String
    )
}