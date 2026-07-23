package dev.sixbridge.core.wrapper

import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.util.concurrent.TimeUnit

data class ProcessResult(
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
)

class ExternalToolException(
    message: String,
    val result: ProcessResult,
) : IllegalStateException(message)

object ProcessRunner {
    fun run(
        command: List<String>,
        workingDirectory: Path? = null,
        environment: Map<String, String> = emptyMap(),
        timeoutSeconds: Long = 120,
    ): ProcessResult {
        val builder = ProcessBuilder(command)
        if (workingDirectory != null) builder.directory(workingDirectory.toFile())
        builder.environment().putAll(environment)
        val process = builder.start()
        val stdout = StringBuilder()
        val stderr = StringBuilder()
        val stdoutThread = Thread {
            process.inputStream.bufferedReader(StandardCharsets.UTF_8).useLines { lines ->
                lines.forEach { stdout.appendLine(it) }
            }
        }.apply { start() }
        val stderrThread = Thread {
            process.errorStream.bufferedReader(StandardCharsets.UTF_8).useLines { lines ->
                lines.forEach { stderr.appendLine(it) }
            }
        }.apply { start() }

        if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            stdoutThread.join()
            stderrThread.join()
            throw ExternalToolException(
                "외부 도구가 ${timeoutSeconds}초 안에 끝나지 않았습니다: ${command.first()}",
                ProcessResult(-1, stdout.toString(), stderr.toString()),
            )
        }
        stdoutThread.join()
        stderrThread.join()
        val result = ProcessResult(process.exitValue(), stdout.toString(), stderr.toString())
        if (result.exitCode != 0) {
            throw ExternalToolException(
                "외부 도구 실행 실패(${result.exitCode}): ${command.joinToString(" ")}\n${result.stderr}",
                result,
            )
        }
        return result
    }
}
