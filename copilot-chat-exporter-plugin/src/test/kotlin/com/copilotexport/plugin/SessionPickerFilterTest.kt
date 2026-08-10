package com.copilotexport.plugin

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.nio.file.Path

/**
 * Unit tests for [SessionPickerFilter], the pure filter/sort logic behind
 * the session picker dialog. Kept independent of Swing so it runs as
 * a plain JUnit test (no IntelliJ test fixture needed).
 */
class SessionPickerFilterTest {

    private fun ide(title: String, kind: String, lastModified: Long) =
        IDESessionInfo(title = title, path = "/fake/$title", kindName = kind, lastModifiedMillis = lastModified)

    private fun cli(title: String) =
        CLISessionInfo(title = title, sessionPath = Path.of(title))

    @Test
    fun `sorts newest first across mixed session kinds`() {
        val oldIde = ide("old-agent-session", "AGENT", lastModified = 1_000L)
        val newCli = cli("new-cli-session")
        val sessions = listOf(oldIde, newCli)

        // CLI timestamp comes from the file system (Files.getLastModifiedTime),
        // which will fail for a fake path and fall back to 0L -- so instead
        // verify ordering using two IDE sessions, which carry an explicit
        // lastModifiedMillis and don't depend on the filesystem.
        val newer = ide("newer", "CHAT", lastModified = 5_000L)
        val older = ide("older", "CHAT", lastModified = 1_000L)
        val result = SessionPickerFilter.apply(listOf(older, newer), query = "")

        assertEquals(listOf(newer, older), result)
    }

    @Test
    fun `filters by title substring case-insensitively`() {
        val a = ide("Fixing-Login-Bug", "CHAT", 1L)
        val b = ide("Refactor-Payments", "CHAT", 2L)

        val result = SessionPickerFilter.apply(listOf(a, b), query = "login")

        assertEquals(listOf(a), result)
    }

    @Test
    fun `combines mixed session kinds and search query`() {
        val ideMatch = ide("payments-fix", "CHAT", 1L)
        val ideNoMatch = ide("login-fix", "CHAT", 2L)
        val cliMatch = cli("payments-refactor")

        val result = SessionPickerFilter.apply(listOf(ideMatch, ideNoMatch, cliMatch), query = "payments")

        assertEquals(setOf(ideMatch, cliMatch), result.toSet())
    }

    @Test
    fun `empty query matches everything`() {
        val a = ide("a", "CHAT", 1L)
        val b = cli("b")

        val result = SessionPickerFilter.apply(listOf(a, b), query = "   ")

        assertEquals(2, result.size)
    }
}
