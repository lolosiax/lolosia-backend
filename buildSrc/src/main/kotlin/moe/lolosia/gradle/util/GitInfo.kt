package moe.lolosia.gradle.util

import org.gradle.api.Project
import java.io.ByteArrayOutputStream

/**
 * Git 信息数据类
 * @author 洛洛希雅Lolosia
 * @since 2026-05-14 11:38:24
 */
data class GitInfo(val shortHash: String, val branchName: String)

fun getGitInfo(project: Project): GitInfo? {
    return try {
        val gitDirResult = project.exec {
            commandLine("git", "rev-parse", "--git-dir")
            workingDir = project.rootProject.projectDir
            isIgnoreExitValue = true
        }
        if (gitDirResult.exitValue != 0) return null

        val hashOutput = ByteArrayOutputStream()
        project.exec {
            commandLine("git", "rev-parse", "--short", "HEAD")
            workingDir = project.rootProject.projectDir
            standardOutput = hashOutput
        }
        val shortHash = hashOutput.toString().trim()

        val branchOutput = ByteArrayOutputStream()
        project.exec {
            commandLine("git", "rev-parse", "--abbrev-ref", "HEAD")
            workingDir = project.rootProject.projectDir
            standardOutput = branchOutput
        }
        val branchName = branchOutput.toString().trim()

        GitInfo(shortHash, branchName)
    } catch (_: Exception) {
        null
    }
}