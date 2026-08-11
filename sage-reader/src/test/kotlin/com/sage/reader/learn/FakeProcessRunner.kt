package com.sage.reader.learn

import java.nio.file.Path

/** Shared test fake: records the command it was invoked with and returns a canned result. */
internal class FakeProcessRunner(
    private val result: ProcessResult = ProcessResult(0, "ok", "")
) : ProcessRunner {
    var lastCommand: List<String>? = null
        private set
    var lastWorkingDir: Path? = null
        private set
    var callCount = 0
        private set

    override fun run(command: List<String>, workingDir: Path?, onOutputLine: ((String) -> Unit)?): ProcessResult {
        lastCommand = command
        lastWorkingDir = workingDir
        callCount++
        return result
    }
}
