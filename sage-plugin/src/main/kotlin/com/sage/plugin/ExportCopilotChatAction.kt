package com.sage.plugin

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.fileChooser.FileChooserFactory
import com.intellij.openapi.fileChooser.FileSaverDescriptor
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.ui.CollectionListModel
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextField
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.lang.reflect.InvocationTargetException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.event.DocumentEvent

private val LOG = Logger.getInstance(ExportCopilotChatAction::class.java)

/** Unwraps the real cause when an exception was thrown through reflection's Method.invoke(). */
private fun unwrap(e: Throwable): Throwable = if (e is InvocationTargetException) e.targetException ?: e else e

private fun isLockedError(e: Throwable): Boolean =
    e.message?.contains("locked", ignoreCase = true) == true

/** Matches com.sage.reader.SchemaMismatchException by name/message rather than by
 *  type, since the plugin never imports reader classes directly (they're loaded reflectively). */
private fun isSchemaMismatchError(e: Throwable): Boolean =
    e.javaClass.name == "com.sage.reader.SchemaMismatchException"

/**
 * Plain, plugin-module copy of the reader's RenderOptions fields -- kept
 * separate (rather than importing the reader's class directly) so this
 * compile-time dependency stays reflection-only, matching how every other
 * reader type is accessed here.
 */
private data class RenderOptionsData(val includeThinking: Boolean, val includeToolJson: Boolean)

/**
 * Exports Copilot chat sessions to Markdown.
 * Lazily loads reader classes to avoid class initialization errors.
 */
class ExportCopilotChatAction : AnAction() {
    
    override fun actionPerformed(event: AnActionEvent) {
        try {
            LOG.info("ExportCopilotChatAction: actionPerformed started")

            // Lazy-load the reader classes
            val readerClass = Class.forName("com.sage.reader.CliSessionLocator")
            LOG.info("ExportCopilotChatAction: loaded reader class ${readerClass.name} from classloader ${readerClass.classLoader}")
            
            // Discover available sessions
            val ideeSessions = SessionDiscovery.discoverIDESessions()
            val cliSessions = SessionDiscovery.discoverCLISessions()
            LOG.info("ExportCopilotChatAction: discovered ideSessions=${ideeSessions.size} cliSessions=${cliSessions.size}")
            
            val allSessions = ideeSessions + cliSessions
            
            if (allSessions.isEmpty()) {
                LOG.warn("ExportCopilotChatAction: no sessions found, showing info message")
                Messages.showInfoMessage("No Copilot chat sessions found", "Export Chat")
                return
            }
            
            // If single session, use it; otherwise show picker
            val selectedSession = if (allSessions.size == 1) {
                allSessions[0]
            } else {
                showSessionPicker(allSessions) ?: return
            }
            
            // Show file save dialog
            val fileName = generateFileName(selectedSession)
            val descriptor = FileSaverDescriptor("Save Copilot Chat Export", "Choose a location to save the chat", "md")
            
            val settings = ExportSettingsState.getInstance().state
            val defaultFolder = settings.defaultExportFolder.trim()
            val initialDir = LocalFileSystem.getInstance()
                .findFileByPath(defaultFolder.ifEmpty { System.getProperty("user.home") })
                ?: LocalFileSystem.getInstance().findFileByPath(System.getProperty("user.home"))
            
            val saveFileDialog = FileChooserFactory.getInstance()
                .createSaveFileDialog(descriptor, event.project)
            
            val saveFileWrapper = saveFileDialog.save(initialDir, fileName)
            if (saveFileWrapper == null) {
                return
            }
            
            // Export session
            val markdown = try {
                val options = RenderOptionsData(
                    includeThinking = settings.includeThinkingBlocks,
                    includeToolJson = settings.includeRawToolJson
                )
                exportSession(selectedSession, options)
            } catch (e: Exception) {
                handleExportException(e, selectedSession)
                return
            }
            
            // Write to file
            val outputPath = Paths.get(saveFileWrapper.file.path)
            Files.write(outputPath, markdown.toByteArray())
            
            // Show success message
            val message = "✅ Chat exported to:\n${outputPath}"
            Messages.showInfoMessage(message, "Export Complete")
            
            // Try to reveal in explorer
            revealInExplorer(outputPath.toString())
            
        } catch (e: Exception) {
            LOG.error("ExportCopilotChatAction: actionPerformed failed", e)
            Messages.showErrorDialog(
                "Failed to export chat:\n${e.message}\n\nThis may be due to missing sessions or data format issues.",
                "Export Failed"
            )
        }
    }
    
    /** Renders a single session, dispatching to the IDE or CLI export path. */
    private fun exportSession(session: SessionInfo, options: RenderOptionsData): String = when (session) {
        is IDESessionInfo -> exportIdeSession(session, options)
        is CLISessionInfo -> exportCliSession(session, options)
    }
    
    /** Shows the appropriate error/warning dialog for a failed session export. */
    private fun handleExportException(e: Exception, session: SessionInfo) {
        val cause = unwrap(e)
        LOG.error("ExportCopilotChatAction: export failed for $session", cause)
        if (isSchemaMismatchError(cause)) {
            Messages.showWarningDialog(
                cause.message ?: "This session's data doesn't match any known Copilot schema version.",
                "Unsupported Copilot Version"
            )
            return
        }
        if (isLockedError(cause)) {
            Messages.showWarningDialog(
                cause.message ?: "This session's file is currently locked by another process " +
                    "(most likely an IDE with GitHub Copilot actively running). Close that IDE " +
                    "window/project and try again.",
                "Session Locked"
            )
        } else {
            Messages.showErrorDialog(
                "Failed to export chat:\n${cause.message}\n\nThis may be due to missing sessions or data format issues.",
                "Export Failed"
            )
        }
    }
    
    
    /**
     * Reads and renders an IDE (Nitrite .db) session via NitriteSessionReader +
     * MarkdownRenderer. Uses reflection for the same reason as the other
     * discover/export methods (lazy classloading of the reader module).
     *
     * Deliberately does NOT catch its own exceptions (unlike exportCliSession):
     * NitriteSessionReader.read() throws a clear, user-facing IOException when
     * the .db is exclusively locked by another process (e.g. the IDE that owns
     * this session is still open), or a SchemaMismatchException when the
     * store's schema doesn't match any known Copilot version -- and
     * actionPerformed's caller needs those real exceptions -- not a string
     * embedded in the exported file -- so it can show a dedicated dialog for
     * each case instead of a generic error.
     */
    private fun exportIdeSession(session: IDESessionInfo, options: RenderOptionsData): String {
        val readerClass = Class.forName("com.sage.reader.NitriteSessionReader")
        val readerInstance = readerClass.getField("INSTANCE").get(null)
        val sessionKindClass = Class.forName("com.sage.reader.model.SessionKind")
        @Suppress("UNCHECKED_CAST")
        val kindEnum = (sessionKindClass.enumConstants as Array<Enum<*>>)
            .firstOrNull { it.name == session.kindName }
            ?: throw IllegalStateException("Unknown session kind '${session.kindName}'")
        val readMethod = readerClass.getMethod("read", Path::class.java, sessionKindClass)

        val rendererClass = Class.forName("com.sage.reader.MarkdownRenderer")
        val rendererInstance = rendererClass.getField("INSTANCE").get(null)
        val chatSessionClass = Class.forName("com.sage.reader.model.ChatSession")

        val chatSessions = readMethod.invoke(readerInstance, Paths.get(session.path), kindEnum) as List<*>
        if (chatSessions.isEmpty()) {
            return "No sessions found in `${session.path}`."
        }
        // A single .db file can (rarely) contain more than one session; render
        // all of them into one document rather than silently dropping data.
        return chatSessions.joinToString("\n\n---\n\n") { chatSession ->
            renderChatSession(rendererClass, rendererInstance, chatSessionClass, chatSession, options)
        }
    }

    private fun exportCliSession(session: CLISessionInfo, options: RenderOptionsData): String {
        return try {
            // CliSessionReader/MarkdownRenderer are Kotlin `object`s: methods
            // are instance methods on INSTANCE, and must be looked up with
            // their real parameter types (not Any/null).
            val readerClass = Class.forName("com.sage.reader.CliSessionReader")
            val readerInstance = readerClass.getField("INSTANCE").get(null)
            val readMethod = readerClass.getMethod("read", Path::class.java)

            val rendererClass = Class.forName("com.sage.reader.MarkdownRenderer")
            val rendererInstance = rendererClass.getField("INSTANCE").get(null)
            val chatSessionClass = Class.forName("com.sage.reader.model.ChatSession")
            
            val chatSession = readMethod.invoke(readerInstance, session.sessionPath)
            if (chatSession != null) {
                renderChatSession(rendererClass, rendererInstance, chatSessionClass, chatSession, options)
            } else {
                LOG.warn("ExportCopilotChatAction: CliSessionReader.read returned null for ${session.sessionPath}")
                "Failed to read CLI session"
            }
        } catch (e: Exception) {
            LOG.error("ExportCopilotChatAction: exportCliSession failed for ${session.sessionPath}", e)
            "Error exporting session: ${e.message}"
        }
    }

    /**
     * Looks up MarkdownRenderer.render(ChatSession, RenderOptions) and invokes
     * it with a reflectively-constructed RenderOptions built from [options],
     * so the settings toggles (thinking blocks / raw tool JSON) apply
     * to both IDE and CLI exports through the exact same renderer path.
     */
    private fun renderChatSession(
        rendererClass: Class<*>,
        rendererInstance: Any,
        chatSessionClass: Class<*>,
        chatSession: Any?,
        options: RenderOptionsData
    ): String {
        val renderOptionsClass = Class.forName("com.sage.reader.RenderOptions")
        val renderOptionsCtor = renderOptionsClass.getConstructor(Boolean::class.java, Boolean::class.java)
        val renderOptionsInstance = renderOptionsCtor.newInstance(options.includeThinking, options.includeToolJson)
        val renderMethod = rendererClass.getMethod("render", chatSessionClass, renderOptionsClass)
        return renderMethod.invoke(rendererInstance, chatSession, renderOptionsInstance) as String
    }
    
    private fun showSessionPicker(sessions: List<SessionInfo>): SessionInfo? {
        val dialog = SessionPickerDialog(sessions)
        return if (dialog.showAndGet()) dialog.selectedSession else null
    }
    
    /**
     * Builds the default export file name, incorporating the session's
     * title and a slug of its first user prompt so the file name hints at
     * the chat's content, not just its opaque session id.
     */
    private fun generateFileName(session: SessionInfo): String {
        val timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))
        val safeName = session.title.replace(Regex("[^a-zA-Z0-9-]"), "-")
        val promptSlug = slugifyPrompt(session.firstPrompt)
        val namePart = if (promptSlug.isNotBlank()) "$safeName-$promptSlug" else safeName
        return "copilot-chat-$namePart-$timestamp.md"
    }

    /**
     * Builds a filename-safe slug from a session's first user prompt.
     */
    private fun slugifyPrompt(prompt: String, maxLength: Int = 40): String {
        val singleLine = prompt.trim().lineSequence().firstOrNull { it.isNotBlank() } ?: return ""
        return singleLine.replace(Regex("[^a-zA-Z0-9]+"), "-").trim('-').take(maxLength)
    }
    
    private fun revealInExplorer(filePath: String) {
        try {
            val file = Paths.get(filePath).toFile()
            when {
                System.getProperty("os.name").lowercase().contains("win") -> {
                    Runtime.getRuntime().exec(arrayOf("explorer.exe", "/select,", file.absolutePath))
                }
                System.getProperty("os.name").lowercase().contains("mac") -> {
                    Runtime.getRuntime().exec(arrayOf("open", "-R", file.absolutePath))
                }
                else -> {
                    Runtime.getRuntime().exec(arrayOf("xdg-open", file.parent))
                }
            }
        } catch (e: Exception) {
            // Silently fail if reveal not supported
        }
    }
}

/**
 * Session picker: a searchable, sortable list dialog, replacing
 * the plain Messages.showChooseDialog. Sessions are always shown
 * newest-first; the search field filters by title substring. Filtering/
 * sorting itself lives in [SessionPickerFilter] so it's covered by a plain
 * unit test.
 */
private class SessionPickerDialog(
    private val allSessions: List<SessionInfo>
) : DialogWrapper(true) {

    private val listModel = CollectionListModel<SessionInfo>()
    private val list = JBList(listModel)
    private val searchField = JBTextField()

    var selectedSession: SessionInfo? = null
        private set

    init {
        title = "Select Copilot Chat Session"
        setOKButtonText("Export")
        // Only one session can ever be exported per action; disable Ctrl/Shift
        // multi-selection so the list can't end up with more than one row highlighted.
        list.selectionMode = javax.swing.ListSelectionModel.SINGLE_SELECTION
        list.cellRenderer = SessionListCellRenderer()
        list.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (e.clickCount == 2 && list.selectedValue != null) {
                    doOKAction()
                }
            }
        })
        refreshList()
        init()
    }

    override fun createCenterPanel(): JComponent {
        val panel = JPanel(BorderLayout(0, 8))
        panel.preferredSize = Dimension(560, 420)

        val topPanel = JPanel(BorderLayout(8, 0))
        topPanel.add(JLabel("Search:"), BorderLayout.WEST)
        topPanel.add(searchField, BorderLayout.CENTER)
        panel.add(topPanel, BorderLayout.NORTH)
        panel.add(JBScrollPane(list), BorderLayout.CENTER)

        searchField.document.addDocumentListener(object : DocumentAdapter() {
            override fun textChanged(e: DocumentEvent) = refreshList()
        })

        return panel
    }

    override fun getPreferredFocusedComponent(): JComponent = searchField

    private fun refreshList() {
        val query = searchField.text.orEmpty()
        val filtered = SessionPickerFilter.apply(allSessions, query)
        listModel.replaceAll(filtered)
        if (filtered.isNotEmpty()) {
            list.selectedIndex = 0
        }
    }

    override fun doOKAction() {
        selectedSession = list.selectedValue
        super.doOKAction()
    }
}

internal class SessionListCellRenderer : javax.swing.ListCellRenderer<SessionInfo> {
    private val label = JLabel()

    override fun getListCellRendererComponent(
        list: javax.swing.JList<out SessionInfo>,
        value: SessionInfo,
        index: Int,
        isSelected: Boolean,
        cellHasFocus: Boolean
    ): java.awt.Component {
        val timestamp = sessionTimestamp(value).let {
            if (it > 0) PICKER_TIMESTAMP_FORMAT.format(Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC)) else "unknown time"
        }
        label.text = "${sessionDisplayLabel(value)}  —  $timestamp"
        label.isOpaque = true
        label.background = if (isSelected) list.selectionBackground else list.background
        label.foreground = if (isSelected) list.selectionForeground else list.foreground
        label.border = javax.swing.BorderFactory.createEmptyBorder(4, 6, 4, 6)
        return label
    }
}
