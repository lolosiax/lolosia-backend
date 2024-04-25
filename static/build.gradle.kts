import com.fasterxml.jackson.databind.json.JsonMapper
import kotlinx.coroutines.*
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine
import kotlin.io.path.Path
import kotlin.io.path.absolute
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText

buildscript {
    dependencies {
        classpath("com.fasterxml.jackson.core:jackson-databind:2.14.2")
    }
}

plugins {
    java
}

tasks.jar {
    dependsOn("buildResources")
    archiveFileName = "lolosia-static-web-${rootProject.version}.jar"

    from(buildDir.resolve("tmp/webJar/resources/"))
    manifest {
        attributes["Implementation-Version"] = rootProject.version
        attributes["Implementation-Title"] = rootProject.name
    }
}

task("buildResources") {
    group = "build"

    val platformsPath = Path("${rootDir}/platforms.json")
    if (!platformsPath.exists()) {
        platformsPath.writeText(
            """
            |{
            |   "lolosiaWebPath": null
            |}
        """.trimMargin("|")
        )
    }

    doLast {
        val buildDir = project.buildDir
        delete(buildDir.resolve("tmp/webJar"))

        @Suppress("UNCHECKED_CAST")
        val platforms = JsonMapper().readValue(
            platformsPath.readText(),
            LinkedHashMap::class.java
        ) as Map<String, String?>

        // 构建多个平台文件
        runBlocking {
            val jobs = mutableListOf<Job>()

            // 智能决策平台
            platforms["lolosiaWebPath"]?.let {
                jobs += launch {
                    buildPlatform("home", it)
                }
            }
            jobs.joinAll()
        }
    }
}

suspend fun buildPlatform(name: String, dirPath: String) {
    val path = Path(dirPath).absolute()
    val vite = path.resolve("node_modules/.bin/vite.CMD")
    suspendCoroutine {
        val process = ProcessBuilder(vite.toString(), "build", "--mode", "build").apply {
            directory(path.toFile())
            val env = environment()
            env["VITE_APP_BASE_MODE"] = "local"
        }.start()

        CoroutineScope(Dispatchers.IO).launch {
            process.inputStream.transferTo(System.out)
        }
        CoroutineScope(Dispatchers.IO).launch {
            process.errorStream.transferTo(System.err)
        }

        process.onExit().thenAccept { p1 ->
            if (p1.exitValue() != 0) {
                it.resumeWithException(RuntimeException("Vite的退出值不为0"))
            } else it.resume(0)
        }
    }

    copy {
        into(buildDir.resolve("tmp/webJar/resources/static/$name"))
        from(path.resolve("dist").toString())
    }
}