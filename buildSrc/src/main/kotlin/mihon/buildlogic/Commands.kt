package mihon.buildlogic

import org.gradle.api.Project
import java.io.ByteArrayOutputStream
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

// Git is needed in your system PATH for these commands to work.
// If it's not installed, you can return a random value as a workaround
fun Project.getCommitCount(): String {
    return runCommand("git rev-list --count HEAD").ifEmpty { "0" }
}

fun Project.getGitSha(): String {
    return runCommand("git rev-parse --short HEAD").ifEmpty { "unknown" }
}

private val BUILD_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'")

/**
 * @param useLastCommitTime If `true`, the build time is based on the timestamp of the last Git commit;
 *                          otherwise, the current time is used. Both are in UTC.
 * @return A formatted string representing the build time. The format used is defined by [BUILD_TIME_FORMATTER].
 */
fun Project.getBuildTime(useLastCommitTime: Boolean): String {
    return if (useLastCommitTime) {
        val output = runCommand("git log -1 --format=%ct")
        if (output.isNotEmpty()) {
            try {
                val epoch = output.toLong()
                Instant.ofEpochSecond(epoch).atOffset(ZoneOffset.UTC).format(BUILD_TIME_FORMATTER)
            } catch (e: Exception) {
                LocalDateTime.now(ZoneOffset.UTC).format(BUILD_TIME_FORMATTER)
            }
        } else {
            LocalDateTime.now(ZoneOffset.UTC).format(BUILD_TIME_FORMATTER)
        }
    } else {
        LocalDateTime.now(ZoneOffset.UTC).format(BUILD_TIME_FORMATTER)
    }
}

private fun Project.runCommand(command: String): String {
    // Check if .git directory exists to avoid git errors when project is downloaded as a ZIP
    val isGitRepo = rootProject.layout.projectDirectory.dir(".git").asFile.exists()
    if (!isGitRepo) return ""

    return try {
        val stdout = ByteArrayOutputStream()
        project.exec {
            commandLine = command.split(" ")
            standardOutput = stdout
            errorOutput = ByteArrayOutputStream() // Ignore error output
            isIgnoreExitValue = true
        }
        stdout.toString().trim()
    } catch (e: Exception) {
        ""
    }
}
