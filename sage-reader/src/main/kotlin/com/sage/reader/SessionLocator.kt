package com.sage.reader

import com.sage.reader.model.SessionKind
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/** One discoverable .db file, classified by session kind. */
data class DbFileRef(val path: Path, val kind: SessionKind)

/**
 * Locates GitHub Copilot's chat .db files on disk.
 *
 * Storage layout: `<root>/<ide>/<storeKind>/<storeId>/` containing one or more `*.db` files,
 * where `<root>` is
 * `%LOCALAPPDATA%\github-copilot` on Windows or `~/.config/github-copilot`
 * on macOS/Linux, and `<storeKind>` is one of `chat-sessions`,
 * `chat-agent-sessions`, `chat-edit-sessions`, or `bg-agent-sessions` (the
 * last is the CLI/background agent, not a chat UI session -- skipped). A
 * handful of files live directly under `<root>` (e.g. `auth.db`,
 * `copilot-intellij.db`) and aren't per-session chat stores either.
 */
object SessionLocator {

    /** The OS-standard github-copilot config root, independent of whether it exists. */
    fun githubCopilotRoot(
        env: Map<String, String> = System.getenv(),
        homeDir: String = System.getProperty("user.home"),
        osName: String = System.getProperty("os.name", "")
    ): Path {
        val os = osName.lowercase()
        return if (os.contains("win")) {
            val localAppData = env["LOCALAPPDATA"]?.takeIf { it.isNotBlank() } ?: "$homeDir\\AppData\\Local"
            Paths.get(localAppData, "github-copilot")
        } else {
            Paths.get(homeDir, ".config", "github-copilot")
        }
    }

    /**
     * Classifies a .db file's session kind purely from its path -- the name
     * of the directory two levels up (the `<storeKind>` segment above).
     * Returns null for anything that isn't a per-session chat store
     * (bg-agent-sessions, or loose files directly under the root).
     */
    fun classify(dbFile: Path): SessionKind? {
        val storeKindDir = dbFile.parent?.parent?.fileName?.toString() ?: return null
        return when (storeKindDir) {
            "chat-sessions" -> SessionKind.CHAT
            "chat-agent-sessions" -> SessionKind.AGENT
            "chat-edit-sessions" -> SessionKind.EDIT
            else -> null
        }
    }

    /** Finds every classifiable .db file under [root], newest-first. Returns an empty list if [root] doesn't exist. */
    fun discover(root: Path): List<DbFileRef> {
        if (!Files.isDirectory(root)) return emptyList()
        val found = ArrayList<DbFileRef>()
        val stream = Files.walk(root)
        try {
            stream.filter(Files::isRegularFile)
                .filter { it.fileName.toString().lowercase().endsWith(".db") }
                .forEach { p ->
                    val kind = classify(p)
                    if (kind != null) {
                        found.add(DbFileRef(p, kind))
                    }
                }
        } finally {
            stream.close()
        }
        return found.sortedByDescending { lastModifiedOrEpoch(it.path) }
    }

    private fun lastModifiedOrEpoch(p: Path): Long = try {
        Files.getLastModifiedTime(p).toMillis()
    } catch (e: IOException) {
        0L
    }
}
