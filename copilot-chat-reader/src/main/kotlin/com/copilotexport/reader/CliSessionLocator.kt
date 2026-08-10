package com.copilotexport.reader

import com.copilotexport.reader.model.SessionKind
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * Locates and discovers Copilot CLI sessions stored in ~/.copilot/session-state/
 * CLI sessions use JSONL + YAML format, unlike IDE plugin sessions which use Nitrite .db files.
 */
object CliSessionLocator {

    /** The OS-standard copilot session-state root. */
    fun cliSessionStateRoot(
        homeDir: String = System.getProperty("user.home")
    ): Path {
        return Paths.get(homeDir, ".copilot", "session-state")
    }

    /**
     * Checks if a directory is a valid CLI session (has events.jsonl and workspace.yaml).
     */
    fun isCliSession(dir: Path): Boolean {
        if (!Files.isDirectory(dir)) return false
        val eventsFile = dir.resolve("events.jsonl")
        val workspaceFile = dir.resolve("workspace.yaml")
        return Files.exists(eventsFile) && Files.exists(workspaceFile)
    }

    /**
     * Discovers all CLI sessions under [root]. Returns paths to valid session directories.
     */
    fun discover(root: Path): List<Path> {
        if (!Files.isDirectory(root)) return emptyList()
        val found = ArrayList<Path>()
        try {
            Files.list(root).use { stream ->
                stream.filter { isCliSession(it) }
                    .sorted()
                    .forEach { found.add(it) }
            }
        } catch (e: Exception) {
            // Root might not exist or be inaccessible
        }
        return found.sortedByDescending { lastModifiedOrEpoch(it) }
    }

    private fun lastModifiedOrEpoch(p: Path): Long = try {
        Files.getLastModifiedTime(p).toMillis()
    } catch (e: Exception) {
        0L
    }
}
