package com.sage.plugin

import com.intellij.openapi.diagnostic.Logger
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

private val LOG = Logger.getInstance("com.sage.plugin.SessionDiscovery")

sealed class SessionInfo {
    abstract val title: String
    abstract val firstPrompt: String
}

data class IDESessionInfo(
    override val title: String,
    val path: String,
    val kindName: String,
    val lastModifiedMillis: Long,
    override val firstPrompt: String = ""
) : SessionInfo()

data class CLISessionInfo(
    override val title: String,
    val sessionPath: Path,
    override val firstPrompt: String = ""
) : SessionInfo()

/** Last-modified time, used to sort the picker newest-first regardless of session kind. */
internal fun sessionTimestamp(session: SessionInfo): Long = when (session) {
    is IDESessionInfo -> session.lastModifiedMillis
    is CLISessionInfo -> try {
        Files.getLastModifiedTime(session.sessionPath).toMillis()
    } catch (e: Exception) {
        0L
    }
}

/**
 * The text shown in the session picker dropdown: the session's first user
 * prompt if one could be read, falling back to its file name otherwise (e.g.
 * for a locked/unreadable session).
 */
internal fun sessionDisplayLabel(session: SessionInfo): String {
    val preview = session.firstPrompt.trim().takeIf { it.isNotEmpty() } ?: return session.title
    val singleLine = preview.lineSequence().firstOrNull { it.isNotBlank() } ?: preview
    return if (singleLine.length > 100) singleLine.take(100) + "…" else singleLine
}

internal val PICKER_TIMESTAMP_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

/**
 * Pure filtering/sorting logic for the session picker, extracted
 * out of the picker dialogs so it can be unit-tested without a Swing/IDE
 * test harness -- see SessionPickerFilterTest.
 */
internal object SessionPickerFilter {
    /**
     * Filters [sessions] by title substring (case-insensitive), then sorts
     * the result newest-first by [sessionTimestamp].
     */
    fun apply(sessions: List<SessionInfo>, query: String): List<SessionInfo> {
        val normalizedQuery = query.trim().lowercase()
        return sessions
            .filter { session -> session.title.lowercase().contains(normalizedQuery) }
            .sortedByDescending { sessionTimestamp(it) }
    }
}

/**
 * Discovers IDE-plugin and CLI Copilot chat sessions, and reads them into raw
 * `ChatSession` objects for either export-to-Markdown or "analyse and learn"
 * use. Uses reflection to talk to the `sage-reader` module -- shared
 * across [ExportCopilotChatAction] and the "analyse sessions" action -- since
 * this plugin loads reader classes reflectively everywhere else too (avoids
 * class-initialization issues across the plugin's classloader).
 */
internal object SessionDiscovery {

    fun discoverIDESessions(): List<IDESessionInfo> {
        return try {
            val locatorClass = Class.forName("com.sage.reader.SessionLocator")
            val instance = locatorClass.getField("INSTANCE").get(null)
            val githubRootMethod = locatorClass.getMethod(
                "githubCopilotRoot", Map::class.java, String::class.java, String::class.java
            )
            val discoverMethod = locatorClass.getMethod("discover", Path::class.java)

            @Suppress("UNCHECKED_CAST")
            val root = githubRootMethod.invoke(
                instance,
                System.getenv(),
                System.getProperty("user.home"),
                System.getProperty("os.name", "")
            ) as Path
            LOG.info("SessionDiscovery: IDE root=$root exists=${Files.exists(root)}")
            @Suppress("UNCHECKED_CAST")
            val results = discoverMethod.invoke(instance, root) as List<*>
            LOG.info("SessionDiscovery: SessionLocator.discover returned ${results.size} raw entries")

            val promptCache = ExportSettingsState.getInstance().state.firstPromptCache
            results.mapNotNull { dbRef ->
                try {
                    val pathField = dbRef?.javaClass?.getField("path")?.get(dbRef) as? Path
                    val kindValue = dbRef?.javaClass?.getField("kind")?.get(dbRef)
                    if (pathField != null && kindValue != null) {
                        val lastModified = try {
                            Files.getLastModifiedTime(pathField).toMillis()
                        } catch (e: Exception) {
                            0L
                        }
                        val fileName = pathField.fileName.toString()
                        val firstPrompt = promptCache.getOrPut(fileName) {
                            ideFirstPrompt(pathField, kindValue.toString()) ?: ""
                        }
                        IDESessionInfo(
                            title = fileName,
                            path = pathField.toString(),
                            kindName = kindValue.toString(),
                            lastModifiedMillis = lastModified,
                            firstPrompt = firstPrompt
                        )
                    } else null
                } catch (e: Exception) {
                    LOG.warn("SessionDiscovery: failed to map IDE session entry $dbRef", e)
                    null
                }
            }
        } catch (e: Exception) {
            LOG.error("SessionDiscovery: discoverIDESessions failed", e)
            emptyList()
        }
    }

    private fun ideFirstPrompt(dbFile: Path, kindName: String): String? {
        return try {
            val readerClass = Class.forName("com.sage.reader.NitriteSessionReader")
            val readerInstance = readerClass.getField("INSTANCE").get(null)
            val sessionKindClass = Class.forName("com.sage.reader.model.SessionKind")
            @Suppress("UNCHECKED_CAST")
            val kindEnum = (sessionKindClass.enumConstants as Array<Enum<*>>)
                .firstOrNull { it.name == kindName } ?: return null
            val method = readerClass.getMethod("firstPrompt", Path::class.java, sessionKindClass)
            method.invoke(readerInstance, dbFile, kindEnum) as? String
        } catch (e: Exception) {
            LOG.warn("SessionDiscovery: ideFirstPrompt failed for $dbFile", e)
            null
        }
    }

    fun discoverCLISessions(): List<CLISessionInfo> {
        return try {
            val locatorClass = Class.forName("com.sage.reader.CliSessionLocator")
            val instance = locatorClass.getField("INSTANCE").get(null)
            val rootMethod = locatorClass.getMethod("cliSessionStateRoot", String::class.java)
            val discoverMethod = locatorClass.getMethod("discover", Path::class.java)

            @Suppress("UNCHECKED_CAST")
            val root = rootMethod.invoke(instance, System.getProperty("user.home")) as Path
            LOG.info("SessionDiscovery: CLI root=$root exists=${Files.exists(root)}")
            @Suppress("UNCHECKED_CAST")
            val results = discoverMethod.invoke(instance, root) as List<*>
            LOG.info("SessionDiscovery: CliSessionLocator.discover returned ${results.size} raw entries")

            val promptCache = ExportSettingsState.getInstance().state.firstPromptCache
            results.mapNotNull { sessionPath ->
                try {
                    if (sessionPath is Path) {
                        val fileName = sessionPath.fileName.toString()
                        val firstPrompt = promptCache.getOrPut(fileName) {
                            cliFirstPrompt(sessionPath) ?: ""
                        }
                        CLISessionInfo(
                            title = fileName,
                            sessionPath = sessionPath,
                            firstPrompt = firstPrompt
                        )
                    } else null
                } catch (e: Exception) {
                    LOG.warn("SessionDiscovery: failed to map CLI session entry $sessionPath", e)
                    null
                }
            }
        } catch (e: Exception) {
            LOG.error("SessionDiscovery: discoverCLISessions failed", e)
            emptyList()
        }
    }

    private fun cliFirstPrompt(sessionDir: Path): String? {
        return try {
            val readerClass = Class.forName("com.sage.reader.CliSessionReader")
            val readerInstance = readerClass.getField("INSTANCE").get(null)
            val method = readerClass.getMethod("firstUserMessage", Path::class.java)
            method.invoke(readerInstance, sessionDir) as? String
        } catch (e: Exception) {
            LOG.warn("SessionDiscovery: cliFirstPrompt failed for $sessionDir", e)
            null
        }
    }

    /**
     * Reads a session down to its raw `ChatSession` object(s) (reader-module
     * types, accessed reflectively) -- the shared step both Markdown export
     * and "analyse and learn" need before diverging (one renders + saves,
     * the other feeds them to [com.sage.reader.learn.LearningAnalyzer]).
     * An IDE `.db` can rarely contain more than one session; a CLI session
     * directory maps to exactly one (or none, if unreadable).
     */
    fun readChatSessions(session: SessionInfo): List<Any> = when (session) {
        is IDESessionInfo -> readIdeChatSessions(session)
        is CLISessionInfo -> readCliChatSession(session)?.let { listOf(it) } ?: emptyList()
    }

    private fun readIdeChatSessions(session: IDESessionInfo): List<Any> {
        val readerClass = Class.forName("com.sage.reader.NitriteSessionReader")
        val readerInstance = readerClass.getField("INSTANCE").get(null)
        val sessionKindClass = Class.forName("com.sage.reader.model.SessionKind")
        @Suppress("UNCHECKED_CAST")
        val kindEnum = (sessionKindClass.enumConstants as Array<Enum<*>>)
            .firstOrNull { it.name == session.kindName }
            ?: throw IllegalStateException("Unknown session kind '${session.kindName}'")
        val readMethod = readerClass.getMethod("read", Path::class.java, sessionKindClass)
        @Suppress("UNCHECKED_CAST")
        return readMethod.invoke(readerInstance, java.nio.file.Paths.get(session.path), kindEnum) as List<Any>
    }

    private fun readCliChatSession(session: CLISessionInfo): Any? {
        val readerClass = Class.forName("com.sage.reader.CliSessionReader")
        val readerInstance = readerClass.getField("INSTANCE").get(null)
        val readMethod = readerClass.getMethod("read", Path::class.java)
        return readMethod.invoke(readerInstance, session.sessionPath)
    }
}
