package com.copilotexport.reader

import com.copilotexport.reader.model.SessionKind
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

class SessionLocatorTest {

    @Test
    fun classifiesChatSessionsStore() {
        val p = Paths.get("/home/user/.config/github-copilot/ic/chat-sessions/abc123/copilot-chat-nitrite.db")
        assertEquals(SessionKind.CHAT, SessionLocator.classify(p))
    }

    @Test
    fun classifiesAgentSessionsStore() {
        val p = Paths.get("/home/user/.config/github-copilot/iu/chat-agent-sessions/abc123/copilot-agent-sessions-nitrite.db")
        assertEquals(SessionKind.AGENT, SessionLocator.classify(p))
    }

    @Test
    fun classifiesEditSessionsStore() {
        val p = Paths.get("/home/user/.config/github-copilot/ic/chat-edit-sessions/abc123/copilot-edit-sessions-nitrite.db")
        assertEquals(SessionKind.EDIT, SessionLocator.classify(p))
    }

    @Test
    fun ignoresBackgroundAgentAndLooseRootFiles() {
        val bg = Paths.get("/home/user/.config/github-copilot/iu/bg-agent-sessions/abc123/copilot-session-metadata.db")
        assertNull(SessionLocator.classify(bg))

        val loose = Paths.get("/home/user/.config/github-copilot/auth.db")
        assertNull(SessionLocator.classify(loose))
    }

    @Test
    fun resolvesWindowsRootFromLocalAppData() {
        val root = SessionLocator.githubCopilotRoot(
            env = mapOf("LOCALAPPDATA" to "C:\\Users\\test\\AppData\\Local"),
            homeDir = "C:\\Users\\test",
            osName = "Windows 11"
        )
        assertEquals(Paths.get("C:\\Users\\test\\AppData\\Local", "github-copilot"), root)
    }

    @Test
    fun resolvesWindowsRootFallbackWhenLocalAppDataUnset() {
        val root = SessionLocator.githubCopilotRoot(
            env = emptyMap(),
            homeDir = "C:\\Users\\test",
            osName = "Windows 11"
        )
        assertEquals(Paths.get("C:\\Users\\test\\AppData\\Local", "github-copilot"), root)
    }

    @Test
    fun resolvesUnixRootUnderDotConfig() {
        val root = SessionLocator.githubCopilotRoot(
            env = emptyMap(),
            homeDir = "/home/test",
            osName = "Linux"
        )
        assertEquals(Paths.get("/home/test", ".config", "github-copilot"), root)
    }

    @Test
    fun discoverReturnsEmptyListForMissingRoot(@TempDir tempDir: Path) {
        val missing = tempDir.resolve("does-not-exist")
        assertEquals(emptyList<DbFileRef>(), SessionLocator.discover(missing))
    }

    @Test
    fun discoverFindsAndClassifiesRealFilesOnDisk(@TempDir tempDir: Path) {
        val chatDb = tempDir.resolve("ic/chat-sessions/store1/copilot-chat-nitrite.db")
        val agentDb = tempDir.resolve("ic/chat-agent-sessions/store2/copilot-agent-sessions-nitrite.db")
        val bgDb = tempDir.resolve("iu/bg-agent-sessions/store3/copilot-session-metadata.db")
        for (p in listOf(chatDb, agentDb, bgDb)) {
            Files.createDirectories(p.parent)
            Files.createFile(p)
        }

        val found = SessionLocator.discover(tempDir)

        val kinds = found.map { it.kind }.toSet()
        assertEquals(setOf(SessionKind.CHAT, SessionKind.AGENT), kinds)
        assertEquals(2, found.size)
    }
}
