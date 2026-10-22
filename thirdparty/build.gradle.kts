import org.apache.tools.ant.taskdefs.condition.Os

// 独立构建：第三方原生库的编译、打包与发布；不参与主工程编译。
//
// 日常主工程构建只从工件仓库拉取（或本地回退），不会自动跑这里的编译。
// 显式任务：
//   gradlew -p thirdparty compileAll          # 从源码重编全部库
//   gradlew -p thirdparty publishAll          # 打包并发布当前产物
//   gradlew -p thirdparty rebuildAndPublish   # 先编译，再发布

tasks.register("publishAll") {
    group = "publishing"
    description = "打包并发布所有第三方库到工件仓库"
    dependsOn(subprojects.map { "${it.path}:publish" })
}

val compileCodecs = tasks.register<Exec>("compileCodecs") {
    group = "build"
    description = "从源码编译 zlib / libpng / libwebp / libjpeg-turbo / giflib / msgpack-c"
    workingDir = rootProject.projectDir
    doFirst {
        if (!Os.isFamily(Os.FAMILY_WINDOWS)) {
            throw GradleException("第三方源码编译脚本目前只支持 Windows")
        }
    }
    commandLine("powershell", "-ExecutionPolicy", "Bypass", "-File", "gradle/build-codecs.ps1")
}

val compileAll = tasks.register("compileAll") {
    group = "build"
    description = "从源码编译全部第三方库（SDL3 / FreeType / RmlUi 与编解码库）"
    dependsOn(compileCodecs)
    dependsOn(":sdl3:buildFromSource", ":freetype:buildFromSource", ":rmlui:buildFromSource")
}

// RmlUi 的字体引擎要链接 FreeType，必须后跑。
gradle.projectsEvaluated {
    project(":rmlui").tasks.named("buildFromSource") {
        dependsOn(project(":freetype").tasks.named("buildFromSource"))
    }
}

tasks.named("publishAll") {
    mustRunAfter(compileAll)
}

tasks.register("rebuildAndPublish") {
    group = "publishing"
    description = "先编译全部第三方库，再打包并发布"
    dependsOn(compileAll, "publishAll")
}

tasks.register("listLibraries") {
    group = "help"
    description = "列出已声明的第三方库子项目"
    doLast {
        subprojects.sortedBy { it.name }.forEach { println(it.name) }
    }
}
