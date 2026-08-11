package com.sage.reader.learn

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.nio.file.Path
import java.nio.file.Paths

class InstructionsFileTargetsTest {

    @Test
    fun `project path is under dot-github`() {
        val repoRoot = Paths.get("C:/repo")
        val path = InstructionsFileTargets.projectPath(repoRoot)
        assertEquals(Paths.get("C:/repo/.github/copilot-instructions.md"), path)
    }

    @Test
    fun `global path matches the real convention seen on disk`() {
        val path = InstructionsFileTargets.globalPath(homeDir = "C:/Users/someone")
        assertEquals(
            Paths.get("C:/Users/someone/.copilot/instructions/learnings.instructions.md"),
            path
        )
    }

    @Test
    fun `global path supports a custom file name`() {
        val path = InstructionsFileTargets.globalPath(homeDir = "C:/Users/someone", fileName = "custom.instructions.md")
        assertEquals(Paths.get("C:/Users/someone/.copilot/instructions/custom.instructions.md"), path)
    }

    @Test
    fun `resolve returns both paths when both scopes requested`() {
        val repoRoot = Paths.get("C:/repo")
        val paths = InstructionsFileTargets.resolve(
            scopes = setOf(InstructionsScope.PROJECT, InstructionsScope.GLOBAL),
            repoRoot = repoRoot,
            homeDir = "C:/Users/someone"
        )
        assertEquals(2, paths.size)
        assertEquals(Paths.get("C:/repo/.github/copilot-instructions.md"), paths[0])
        assertEquals(Paths.get("C:/Users/someone/.copilot/instructions/learnings.instructions.md"), paths[1])
    }

    @Test
    fun `resolve throws when PROJECT requested without repoRoot`() {
        assertThrows(IllegalArgumentException::class.java) {
            InstructionsFileTargets.resolve(scopes = setOf(InstructionsScope.PROJECT), repoRoot = null)
        }
    }

    @Test
    fun `resolve returns empty list for empty scopes`() {
        val paths = InstructionsFileTargets.resolve(scopes = emptySet())
        assertEquals(0, paths.size)
    }
}
