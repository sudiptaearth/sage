package com.sage.reader.learn

import com.sage.reader.model.ChatSession
import com.sage.reader.model.Role
import com.sage.reader.model.SessionKind
import com.sage.reader.model.Turn
import com.sage.reader.model.TurnSide
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/** returns a fixed "did the thing" stdout for use in these tests */
private fun fakeRunner() = FakeProcessRunner(ProcessResult(0, "did the thing", ""))

private fun sampleSession(id: String = "session-1"): ChatSession = ChatSession(
    id = id,
    kind = SessionKind.CHAT,
    sourceDbPath = "n/a",
    turns = listOf(
        Turn(
            id = "turn-1",
            sessionId = id,
            createdAt = 0L,
            chatMode = null,
            model = "gpt-5",
            user = TurnSide(role = Role.USER, rawText = "please do X", blocks = emptyList()),
            assistant = TurnSide(role = Role.ASSISTANT, rawText = "did X wrong, then fixed it", blocks = emptyList())
        )
    )
)

class LearningAnalyzerTest {

    @Test
    fun `analyze rejects empty session list`() {
        val analyzer = LearningAnalyzer(invoker = CopilotCliInvoker(processRunner = FakeProcessRunner()))
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException::class.java) {
            analyzer.analyze(LearningRequest(sessions = emptyList(), targets = listOf(Paths.get("x.md"))))
        }
    }

    @Test
    fun `analyze rejects empty target list`() {
        val analyzer = LearningAnalyzer(invoker = CopilotCliInvoker(processRunner = FakeProcessRunner()))
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException::class.java) {
            analyzer.analyze(LearningRequest(sessions = listOf(sampleSession()), targets = emptyList()))
        }
    }

    @Test
    fun `writeSessionFiles renders one markdown file per session with a safe filename`() {
        val tempDir = Files.createTempDirectory("learning-analyzer-test-")
        try {
            val analyzer = LearningAnalyzer(invoker = CopilotCliInvoker(processRunner = FakeProcessRunner()))
            val files = analyzer.writeSessionFiles(
                tempDir,
                listOf(sampleSession("weird/id:with*chars")),
                com.sage.reader.RenderOptions.DEFAULT
            )
            assertEquals(1, files.size)
            assertTrue(Files.exists(files[0]))
            assertTrue(files[0].fileName.toString().startsWith("session-1-"))
            val content = Files.readString(files[0])
            assertTrue(content.contains("please do X"))
        } finally {
            files_delete(tempDir)
        }
    }

    @Test
    fun `buildPrompt references every session file, target path and proposed-output path`() {
        val analyzer = LearningAnalyzer(invoker = CopilotCliInvoker(processRunner = FakeProcessRunner()))
        val prompt = analyzer.buildPrompt(
            sessionFiles = listOf(Paths.get("C:/tmp/session-1-a.md"), Paths.get("C:/tmp/session-2-b.md")),
            targets = listOf(Paths.get("C:/repo/.github/copilot-instructions.md")),
            proposedFiles = listOf(Paths.get("C:/tmp/proposed-0-copilot-instructions.md")),
            timestamp = "6:40 PM, 8th Aug, 2026"
        )
        assertTrue(prompt.contains("C:/tmp/session-1-a.md") || prompt.contains("C:\\tmp\\session-1-a.md"))
        assertTrue(prompt.contains("C:/tmp/session-2-b.md") || prompt.contains("C:\\tmp\\session-2-b.md"))
        assertTrue(prompt.contains(".github"))
        assertTrue(prompt.contains("applyTo"))
        assertTrue(prompt.contains("proposed-0-copilot-instructions.md"))
        assertTrue(prompt.contains("🧠"))
        assertTrue(prompt.contains("6:40 PM, 8th Aug, 2026"))
    }

    @Test
    fun `buildPrompt includes conservative guidance by default`() {
        val analyzer = LearningAnalyzer(invoker = CopilotCliInvoker(processRunner = FakeProcessRunner()))
        val prompt = analyzer.buildPrompt(
            sessionFiles = listOf(Paths.get("C:/tmp/session-1-a.md")),
            targets = listOf(Paths.get("C:/repo/.github/copilot-instructions.md"))
        )
        assertTrue(prompt.contains("CONSERVATIVE mode"))
        assertTrue(prompt.contains("prefer making no change over a speculative one"))
    }

    @Test
    fun `buildPrompt includes aggressive guidance when requested`() {
        val analyzer = LearningAnalyzer(invoker = CopilotCliInvoker(processRunner = FakeProcessRunner()))
        val prompt = analyzer.buildPrompt(
            sessionFiles = listOf(Paths.get("C:/tmp/session-1-a.md")),
            targets = listOf(Paths.get("C:/repo/.github/copilot-instructions.md")),
            mode = LearningMode.AGGRESSIVE
        )
        assertTrue(prompt.contains("AGGRESSIVE mode"))
        assertTrue(prompt.contains("rewrite, tighten, reorganize, or remove"))
    }

    @Test
    fun `formatTimestamp renders ordinal day, abbreviated month and 12-hour time`() {
        assertEquals(
            "6:40 PM, 8th Aug, 2026",
            LearningAnalyzer.formatTimestamp(java.time.ZonedDateTime.of(2026, 8, 8, 18, 40, 0, 0, java.time.ZoneId.systemDefault()))
        )
        assertEquals(
            "1:05 AM, 1st Jan, 2027",
            LearningAnalyzer.formatTimestamp(java.time.ZonedDateTime.of(2027, 1, 1, 1, 5, 0, 0, java.time.ZoneId.systemDefault()))
        )
        assertEquals(
            "12:00 PM, 11th Nov, 2025",
            LearningAnalyzer.formatTimestamp(java.time.ZonedDateTime.of(2025, 11, 11, 12, 0, 0, 0, java.time.ZoneId.systemDefault()))
        )
    }

    @Test
    fun `analyze does not write to the real target -- proposed content is only returned as a TargetChange`() {
        val runner = fakeRunner()
        val targetDir = Files.createTempDirectory("learning-analyzer-target-")
        val target = targetDir.resolve("copilot-instructions.md")

        var capturedTempDir: Path? = null
        val analyzerWithHook = LearningAnalyzer(
            invoker = CopilotCliInvoker(processRunner = runner, executablePath = "copilot"),
            tempDirFactory = {
                val d = Files.createTempDirectory("learning-analyzer-session-")
                capturedTempDir = d
                d
            }
        )

        val result = analyzerWithHook.analyze(
            LearningRequest(sessions = listOf(sampleSession()), targets = listOf(target))
        )

        assertEquals("did the thing", result.cliOutput)
        assertEquals(listOf(target), result.targets)
        assertEquals(1, result.changes.size)
        assertEquals(target, result.changes[0].path)
        // no proposed file was actually written by our fake CLI runner, so after falls back to before (null -> "")
        assertTrue(!Files.exists(target))

        val cmd = runner.lastCommand!!
        assertTrue(cmd.contains(capturedTempDir!!.toString()))
        assertTrue(cmd.contains(targetDir.toString()))

        // temp dir should be cleaned up after analyze() returns
        assertTrue(!Files.exists(capturedTempDir!!))

        files_delete(targetDir)
    }

    @Test
    fun `applyChanges writes after-content to path and creates parent directories`() {
        val analyzer = LearningAnalyzer(invoker = CopilotCliInvoker(processRunner = FakeProcessRunner()))
        val baseDir = Files.createTempDirectory("learning-analyzer-apply-")
        try {
            val target = baseDir.resolve("nested").resolve("copilot-instructions.md")
            analyzer.applyChanges(listOf(TargetChange(path = target, before = null, after = "- new rule")))
            assertTrue(Files.exists(target))
            assertEquals("- new rule", Files.readString(target))
        } finally {
            files_delete(baseDir)
        }
    }

    @Test
    fun `regression - does not explode a Path into its individual segments as separate add-dirs`() {
        // java.nio.file.Path implements Iterable<Path> (its own name elements), so
        // `someListOfPath + aBarePath` can silently resolve to the "add all elements"
        // overload instead of "append one element" -- this must never happen for the
        // session temp dir or target dirs passed through to --add-dir.
        val runner = fakeRunner()
        val targetDir = Files.createTempDirectory("learning-analyzer-target2-")
        val target = targetDir.resolve("copilot-instructions.md")
        var capturedTempDir: Path? = null
        val analyzer = LearningAnalyzer(
            invoker = CopilotCliInvoker(processRunner = runner, executablePath = "copilot"),
            tempDirFactory = {
                val d = Files.createTempDirectory("learning-analyzer-session2-")
                capturedTempDir = d
                d
            }
        )

        analyzer.analyze(LearningRequest(sessions = listOf(sampleSession()), targets = listOf(target)))

        val cmd = runner.lastCommand!!
        val addDirValues = cmd.withIndex()
            .filter { it.value == "--add-dir" }
            .map { cmd[it.index + 1] }

        // exactly one --add-dir per distinct real directory (session temp dir, target dir) --
        // never one per path *segment* of either directory.
        assertEquals(setOf(capturedTempDir.toString(), targetDir.toString()), addDirValues.toSet())
        assertEquals(2, addDirValues.size)

        files_delete(targetDir)
    }
}

private fun files_delete(dir: Path) {
    if (!Files.exists(dir)) return
    Files.walk(dir).sorted(java.util.Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
}
