package com.sage.reader.learn

import java.nio.file.Path
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Result of running an external process: exit code plus fully-captured
 * stdout/stderr text.
 */
data class ProcessResult(
    val exitCode: Int,
    val stdout: String,
    val stderr: String
)

/**
 * Thin seam around actually spawning an OS process, so [CopilotCliInvoker]
 * can be unit-tested with a fake instead of really invoking the `copilot`
 * CLI (which requires it to be installed, authenticated, and burns real AI
 * credits).
 */
interface ProcessRunner {
    /**
     * Runs [command] (argv-style: first element is the executable, rest are
     * arguments -- no shell interpretation) in [workingDir] (or the current
     * directory if null), waits for it to exit, and returns the captured
     * result.
     *
     * @param onOutputLine optional callback invoked (from a background thread,
     *   not necessarily the caller's thread) once per line of stdout as it is
     *   produced, so callers can surface live progress (e.g. into a UI status
     *   indicator) instead of waiting for the process to fully exit. Never
     *   invoked for stderr. Ignored entirely by fakes that don't stream.
     * @throws java.io.IOException if the executable cannot be found/started.
     */
    fun run(command: List<String>, workingDir: Path?, onOutputLine: ((String) -> Unit)? = null): ProcessResult
}

/**
 * Real implementation: spawns the process via [ProcessBuilder] and reads
 * stdout/stderr concurrently on background threads while waiting for exit,
 * to avoid the classic deadlock where a child process blocks writing to a
 * full stderr pipe while the parent is still synchronously draining stdout.
 *
 * stdout is read line-by-line (rather than in one big [java.io.Reader.readText]
 * call) so [onOutputLine] can be invoked live as the CLI produces output,
 * letting callers show real-time progress instead of a static "running..."
 * message until the whole process exits.
 */
class RealProcessRunner : ProcessRunner {
    override fun run(command: List<String>, workingDir: Path?, onOutputLine: ((String) -> Unit)?): ProcessResult {
        val builder = ProcessBuilder(command)
        if (workingDir != null) {
            builder.directory(workingDir.toFile())
        }
        val process = builder.start()

        val executor = Executors.newFixedThreadPool(2)
        try {
            val stdoutFuture = executor.submit<String> {
                val lines = mutableListOf<String>()
                process.inputStream.bufferedReader().forEachLine { line ->
                    lines += line
                    onOutputLine?.invoke(line)
                }
                lines.joinToString("\n")
            }
            val stderrFuture = executor.submit<String> { process.errorStream.bufferedReader().readText() }

            val exitCode = process.waitFor()
            val stdout = stdoutFuture.get(5, TimeUnit.MINUTES)
            val stderr = stderrFuture.get(5, TimeUnit.MINUTES)
            return ProcessResult(exitCode, stdout, stderr)
        } finally {
            executor.shutdownNow()
        }
    }
}
