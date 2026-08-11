package com.sage.reader.learn

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Path
import java.nio.file.Paths

class CopilotCliInvokerTest {

    @Test
    fun `builds expected argv for a minimal call`() {
        val runner = FakeProcessRunner()
        val invoker = CopilotCliInvoker(processRunner = runner, executablePath = "copilot")

        invoker.runPrompt(prompt = "do the thing", addDirs = emptyList())

        val cmd = runner.lastCommand!!
        assertEquals("copilot", cmd[0])
        assertTrue(cmd.contains("-p"))
        assertEquals("do the thing", cmd[cmd.indexOf("-p") + 1])
        assertTrue(cmd.contains("--allow-all-tools"))
        assertTrue(cmd.contains("--output-format"))
        assertTrue(cmd.contains("text"))
        assertTrue(!cmd.contains("--model"))
    }

    @Test
    fun `adds one --add-dir per distinct directory`() {
        val runner = FakeProcessRunner()
        val invoker = CopilotCliInvoker(processRunner = runner, executablePath = "copilot")
        val dirA = Paths.get("C:/temp/a")
        val dirB = Paths.get("C:/temp/b")

        invoker.runPrompt(prompt = "p", addDirs = listOf(dirA, dirB, dirA))

        val cmd = runner.lastCommand!!
        val addDirIndices = cmd.withIndex().filter { it.value == "--add-dir" }.map { it.index }
        assertEquals(2, addDirIndices.size, "duplicate dirs should be de-duplicated")
        assertTrue(cmd.contains(dirA.toString()))
        assertTrue(cmd.contains(dirB.toString()))
    }

    @Test
    fun `passes model through when provided`() {
        val runner = FakeProcessRunner()
        val invoker = CopilotCliInvoker(processRunner = runner, executablePath = "copilot")

        invoker.runPrompt(prompt = "p", addDirs = emptyList(), model = "gpt-5")

        val cmd = runner.lastCommand!!
        assertEquals("gpt-5", cmd[cmd.indexOf("--model") + 1])
    }

    @Test
    fun `throws CopilotCliException on non-zero exit code`() {
        val runner = FakeProcessRunner(result = ProcessResult(1, "", "boom"))
        val invoker = CopilotCliInvoker(processRunner = runner, executablePath = "copilot")

        val ex = assertThrows(CopilotCliException::class.java) {
            invoker.runPrompt(prompt = "p", addDirs = emptyList())
        }
        assertTrue(ex.message!!.contains("boom"))
    }

    @Test
    fun `wraps process start failure in a clear CopilotCliException`() {
        val throwingRunner = object : ProcessRunner {
            override fun run(command: List<String>, workingDir: Path?, onOutputLine: ((String) -> Unit)?): ProcessResult {
                throw java.io.IOException("cannot find executable")
            }
        }
        val invoker = CopilotCliInvoker(processRunner = throwingRunner, executablePath = "copilot")

        val ex = assertThrows(CopilotCliException::class.java) {
            invoker.runPrompt(prompt = "p", addDirs = emptyList())
        }
        assertTrue(ex.message!!.contains("copilot"))
    }
}
