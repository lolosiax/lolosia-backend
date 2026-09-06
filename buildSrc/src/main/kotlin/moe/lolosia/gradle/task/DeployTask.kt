package moe.lolosia.gradle.task

import groovy.lang.Closure
import moe.lolosia.gradle.ui.ApplicationYamlDialog
import moe.lolosia.gradle.ui.YamlDialogResult
import org.gradle.api.DefaultTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.TaskAction
import org.gradle.kotlin.dsl.*
import org.hidetake.groovy.ssh.connection.AllowAnyHosts
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

    /**
     * 部署方式："docker"（默认）或 "systemd"
     */
    @Input
    var serverMode: String = "docker"

    @Input
    @Optional
    var serverPwd: String? = null

    @Input
    @Optional
    var dockerImages: Map<String, DockerImageInfo> = emptyMap()

    @TaskAction
    @Suppress("UNCHECKED_CAST")
    fun deploy() {
        val remote = createRemote()
        val sshService = project.extensions.getByType(Service::class.java)
        var basePwd = serverPwd ?: "/srv/${project.rootProject.name}"
        if (basePwd.endsWith("/")) basePwd = basePwd.dropLast(1)

        val jarFile = project.tasks.named("bootJar").get().outputs.files.singleFile

        val yamlContent = resolveApplicationYaml(sshService, remote, basePwd)

        val tempDir = Files.createTempDirectory("deploy-extract").toFile()
        try {
            val slimResult = prepareSlimJar(jarFile, tempDir)
            sshService.run(delegateClosureOf<RunHandler> {
                session(remote, delegateClosureOf<SessionHandler> {
                    if (isSystemdMode()) {
                        deploySystemd(basePwd, slimResult, jarFile.name, yamlContent)
                    } else {
                        deployDocker(basePwd, slimResult, jarFile.name, yamlContent)
                    }
                } as Closure<Any>)
            })
        } finally {
            tempDir.deleteRecursively()
        }
    }

    private fun isSystemdMode(): Boolean = serverMode.equals("systemd", ignoreCase = true)

    // ===== docker 模式：布到 <pwd>/docker_build，构建并启动容器 =====

    private fun SessionHandler.deployDocker(
        basePwd: String,
        slimResult: SlimJarResult,
        jarFileName: String,
        yamlContent: String?
    ) {
        val pwd = "$basePwd/docker_build/"
        val dockerfile = project.rootDir.resolve("Dockerfile")
        val dockerShell = project.rootDir.resolve("docker_shell.sh")

        ensureDockerImages(dockerImages)
        ensureDirectories(pwd)
        if (yamlContent != null) {
            uploadContent(yamlContent, "$basePwd/application.yaml")
        }
        uploadFile(dockerfile, "${pwd}Dockerfile")
        uploadFile(slimResult.slimJarFile, "${pwd}slim.jar")
        uploadLibsWithCache(slimResult.libManifest, slimResult.libDir)
        repackageJar(pwd, slimResult.libManifest, jarFileName)
        ensureDockerShell(pwd, dockerShell)
        execute("cd $pwd && bash docker_shell.sh")
    }

    // ===== systemd 模式（no-docker）：直接布到 <basePwd>（work 的父目录），由 systemd 托管 =====

    private fun SessionHandler.deploySystemd(
        basePwd: String,
        slimResult: SlimJarResult,
        jarFileName: String,
        yamlContent: String?
    ) {
        val baseName = project.rootProject.name
        val svcFile = "$baseName.service"
        val localSvcFile = project.rootDir.resolve(svcFile)
        check(localSvcFile.isFile) {
            "systemd 模式部署需要项目根目录存在 $svcFile（其中 ExecStart= 需为 `-jar xxx.jar` 形式），请先创建该文件"
        }

        prepareSystemdDeployDir(basePwd)
        rotateActiveJar(basePwd, baseName, svcFile)
        if (yamlContent != null) {
            uploadContent(yamlContent, "$basePwd/application.yaml")
        }
        uploadFile(slimResult.slimJarFile, "$basePwd/slim.jar")
        uploadLibsWithCache(slimResult.libManifest, slimResult.libDir)
        repackageJar(basePwd, slimResult.libManifest, jarFileName)
        ensureSystemdServiceFile(basePwd, svcFile, localSvcFile)
        patchServiceExecStart(basePwd, svcFile, jarFileName)
        restartSystemdService(basePwd, svcFile)
    }

    private fun SessionHandler.prepareSystemdDeployDir(pwd: String) {
        val homeDir = execute("echo \$HOME").trim()
        val libCacheDir = "$homeDir/.cache/lolosia-gradle-cache/"
        execute("mkdir -p $pwd")
        execute("mkdir -p $libCacheDir")
        execute("cd $pwd && rm -rf slim.jar extracted")
        execute("(command -v unzip >/dev/null && command -v zip >/dev/null) || (echo '$serverPassword' | sudo -S bash -c 'apt-get update -qq && apt-get install -y -qq unzip zip')")
    }

    /**
     * 保留最近一份旧 jar 便于回滚：把 ExecStart 当前指向的活动 jar 移为 `<baseName>.previous.jar`，
     * 其余历史 *-fat.jar 全部清理（新 jar 稍后由 repackageJar 写出到同一目录）。
     */
    private fun SessionHandler.rotateActiveJar(pwd: String, baseName: String, svcFile: String) {
        val prevJar = "$baseName.previous.jar"
        execute(
            "cd $pwd && rm -f $prevJar; " +
                "oldJar=\$(grep '^ExecStart=' $svcFile 2>/dev/null | head -n1 | grep -o '[^ ]*\\.jar' | tail -n1 | tr -d '\\r'); " +
                "oldJarBase=\$(basename \"\$oldJar\" 2>/dev/null); " +
                "if [ -n \"\$oldJarBase\" ] && [ -f \"\$oldJarBase\" ]; then mv -f \"\$oldJarBase\" $prevJar; fi; " +
                "rm -f ./*-fat.jar"
        )
    }

    /**
     * 与 docker_shell.sh 的"只首次拷贝"策略一致：远端已存在 .service 就不覆盖，
     * 保留服务器上手工改过的内容（User=/Environment/资源限制等）。
     */
    private fun SessionHandler.ensureSystemdServiceFile(pwd: String, svcFile: String, localFile: File) {
        val remotePath = "$pwd/$svcFile"
        val exists = execute("test -f $remotePath && echo 'EXISTS' || echo 'NOT_FOUND'").contains("EXISTS")
        if (!exists) {
            put(
                hashMapOf(
                    "from" to localFile,
                    "into" to remotePath
                )
            )
            execute("sed -i 's/\\r//g' $remotePath")
        }
    }

    /**
     * 就地更新远端 .service 的 ExecStart：把其中 jar 指向替换为本次新 jar 的绝对路径，
     * 文件名带版本号不断变化也无需固定成 app.jar。
     * 注意 [^[:space:]]+\\.jar 前要有 [[:space:]] 边界，否则会命中 token 内部的 "t.jar" 之类片段。
     */
    private fun SessionHandler.patchServiceExecStart(pwd: String, svcFile: String, jarFileName: String) {
        val jarAbsPath = "$pwd/$jarFileName"
        val output = execute(
            "cd $pwd && sed -i -E 's|^(ExecStart=.*[[:space:]])[^[:space:]]+\\.jar|\\1$jarAbsPath|' $svcFile && " +
                "(grep -Fq '$jarAbsPath' $svcFile && echo PATCH_OK || echo PATCH_MISS)"
        )
        if (!output.contains("PATCH_OK")) {
            throw RuntimeException("更新 $svcFile 的 ExecStart 失败：请确认其中 ExecStart= 行是 `-jar xxx.jar` 形式")
        }
    }

    private fun SessionHandler.restartSystemdService(pwd: String, svcFile: String) {
        val svcAbsPath = "$pwd/$svcFile"
        // 部署用普通管理员账号，通过 sudo（沿用部署密码）软链到 /etc/systemd/system 并重启服务
        val sudoPrefix = if (serverUser == "root") "" else "echo '$serverPassword' | sudo -S "
        execute(
            "${sudoPrefix}bash -c 'ln -sf $svcAbsPath /etc/systemd/system/$svcFile && " +
                "systemctl daemon-reload && " +
                "(systemctl enable $svcFile >/dev/null 2>&1 || true) && " +
                "systemctl restart $svcFile'"
        )
    }

    private fun createRemote(): Remote {
        val params = mutableMapOf<String, Any?>(
            "name" to serverName,
            "host" to serverHost,
            "port" to serverPort,
            "user" to serverUser,
            "password" to serverPassword
        )

        if (!isHostKnown(serverHost, serverPort)) {
            logger.lifecycle("首次连接到 $serverHost:$serverPort，自动接受主机密钥")
            params["knownHosts"] = AllowAnyHosts.instance
        }

        return Remote(params)
    }

    private fun isHostKnown(host: String, port: Int): Boolean {
        val knownHostsFile = getKnownHostsFile() ?: return false
        if (!knownHostsFile.exists()) return false

        val searchPattern = if (port == 22) host else "[$host]:$port"
        return knownHostsFile.readLines().any { line ->
            val trimmed = line.trim()
            trimmed.isNotEmpty() && !trimmed.startsWith("#") &&
                trimmed.split(" ").firstOrNull()?.let { it == searchPattern || it == host } == true
        }
    }

    private fun getKnownHostsFile(): File? {
        val sshDir = File(System.getProperty("user.home"), ".ssh")
        return if (sshDir.exists()) File(sshDir, "known_hosts") else null
    }

    @Suppress("UNCHECKED_CAST")
    private fun resolveApplicationYaml(sshService: Service, remote: Remote, workDir: String): String? {
        if (checkRemoteFileExists(sshService, remote, "${workDir}/application.yaml")) return null

        val localYaml = project.rootDir.resolve("application.yaml")
        val defaultContent = if (localYaml.exists()) localYaml.readText() else ""

        val result = ApplicationYamlDialog.show(defaultContent)
        return when (result) {
            is YamlDialogResult.UseContent -> result.content
            is YamlDialogResult.CreateEmpty -> ""
            is YamlDialogResult.Abort -> throw RuntimeException("部署已取消：用户选择中止部署")
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun checkRemoteFileExists(sshService: Service, remote: Remote, path: String): Boolean {
        var exists = false
        sshService.run(delegateClosureOf<RunHandler> {
            session(remote, delegateClosureOf<SessionHandler> {
                val output = execute(
                    hashMapOf("ignoreError" to true),
                    "test -f $path && echo 'EXISTS' || echo 'NOT_FOUND'"
                )
                exists = output.contains("EXISTS")
            } as Closure<Any>)
        })
        return exists
    }

    private fun SessionHandler.uploadContent(content: String, remotePath: String) {
        val tempFile = File.createTempFile("deploy-yaml-", ".tmp")
        try {
            tempFile.writeText(content)
            put(
                hashMapOf(
                    "from" to tempFile,
                    "into" to remotePath
                )
            )
        } finally {
            tempFile.delete()
        }
    }

    private fun prepareSlimJar(jarFile: File, tempDir: File): SlimJarResult {
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

        return SlimJarResult(slimJarFile, libDir, libManifest)
    }

    private fun SessionHandler.ensureDockerImages(images: Map<String, DockerImageInfo>) {
        for ((imageName, info) in images) {
            val exists = execute("docker images -q $imageName 2>/dev/null").trim().isNotEmpty()
            if (!exists) {
                val pullImage = info.mirror.ifEmpty { imageName }
                execute("docker pull $pullImage")
                if (info.mirror.isNotEmpty()) {
                    execute("docker tag $pullImage $imageName")
                    if (info.autoUntagMirror) {
                        execute("docker image rm $pullImage")
                    }
                }
            }
        }
    }

    private fun SessionHandler.ensureDirectories(pwd: String) {
        val homeDir = execute("echo \$HOME").trim()
        val libCacheDir = "$homeDir/.cache/lolosia-gradle-cache/"
        execute("mkdir -p $pwd")
        execute("mkdir -p $libCacheDir")
        execute("cd $pwd && rm -rf *-fat.jar slim.jar extracted")
        execute("(command -v unzip >/dev/null && command -v zip >/dev/null) || (echo '$serverPassword' | sudo -S bash -c 'apt-get update -qq && apt-get install -y -qq unzip zip')")
    }

    private fun SessionHandler.uploadFile(from: File, into: String) {
        put(
            hashMapOf(
                "from" to from,
                "into" to into
            )
        )
    }

    private fun SessionHandler.uploadLibsWithCache(libManifest: List<LibEntry>, libDir: File) {
        val homeDir = execute("echo \$HOME").trim()
        val libCacheDir = "$homeDir/.cache/lolosia-gradle-cache/"

        val cachedFiles = execute("ls $libCacheDir 2>/dev/null").trim().lines().toSet()

        for (lib in libManifest) {
            if (lib.sha256 !in cachedFiles) {
                put(
                    hashMapOf(
                        "from" to File(libDir, lib.sha256),
                        "into" to "$libCacheDir${lib.sha256}"
                    )
                )
            }
        }
    }

    private fun SessionHandler.repackageJar(dir: String, libManifest: List<LibEntry>, jarFileName: String) {
        // 兼容带/不带尾部斜杠的目录（docker 传 docker_build/，systemd 传 work 的父目录）
        val pwd = if (dir.endsWith("/")) dir else "$dir/"
        val homeDir = execute("echo \$HOME").trim()
        val libCacheDir = "$homeDir/.cache/lolosia-gradle-cache/"

        execute("cd $pwd && mkdir -p extracted && unzip -o slim.jar -d extracted/")
        execute("mkdir -p ${pwd}extracted/BOOT-INF/lib/")

        val copyScriptFile = File(Files.createTempDirectory("deploy-extract").toFile(), "copy_libs.sh")
        try {
            copyScriptFile.writeText(buildString {
                appendLine("#!/usr/bin/env bash")
                appendLine("cd ${pwd}extracted/BOOT-INF/lib/")
                for (lib in libManifest) {
                    appendLine("cp $libCacheDir${lib.sha256} ./${lib.libRelativePath}")
                }
            })
            put(
                hashMapOf(
                    "from" to copyScriptFile,
                    "into" to "${pwd}copy_libs.sh"
                )
            )
        } finally {
            copyScriptFile.delete()
        }

        execute("bash ${pwd}copy_libs.sh")
        execute("rm -f ${pwd}copy_libs.sh")

        execute("cd ${pwd}extracted && zip -0Xr ../$jarFileName .")
        execute("cd $pwd && rm -rf extracted/ slim.jar")
    }

    private fun SessionHandler.ensureDockerShell(pwd: String, dockerShell: File) {
        val exits = execute(
            hashMapOf("ignoreError" to true),
            "cd $pwd && ls | cat"
        ).split("\n").any { it == "docker_shell.sh" }

        if (!exits) {
            put(
                hashMapOf(
                    "from" to dockerShell,
                    "into" to "${pwd}docker_shell.sh"
                )
            )
            execute("sed -i 's/\\r//g' ${pwd}docker_shell.sh")
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

    private data class SlimJarResult(
        val slimJarFile: File,
        val libDir: File,
        val libManifest: List<LibEntry>
    )
}

data class DockerImageInfo(
    val mirror: String,
    val autoUntagMirror: Boolean = false
)