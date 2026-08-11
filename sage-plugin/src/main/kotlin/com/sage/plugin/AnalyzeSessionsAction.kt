package com.sage.plugin

import com.intellij.diff.DiffContentFactory
import com.intellij.diff.DiffDialogHints
import com.intellij.diff.DiffManager
import com.intellij.diff.contents.DocumentContent
import com.intellij.diff.requests.SimpleDiffRequest
import com.intellij.diff.util.DiffUserDataKeys
import com.intellij.icons.AllIcons
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.WindowWrapper
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.LocalFileSystem
import java.lang.reflect.InvocationTargetException
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.CompletableFuture

private val LOG = Logger.getInstance(AnalyzeSessionsAction::class.java)

/**
 * Short, user-facing progress stages for one analysis run -- shown in the
 * background task's status text instead of the `copilot` CLI's raw,
 * unpredictable tool-call chatter. Index also drives the progress fraction.
 */
private val ANALYSIS_STAGES = listOf(
    "Reading chat sessions...",
    "Analysing chat for mistakes...",
    "Reading instructions files...",
    "Drafting updated rules...",
    "Finishing up..."
)

/** Substrings (lowercase) in a CLI output line that suggest it has started reading an instructions file. */
private val ANALYSIS_STAGE_2_KEYWORDS = listOf("instructions.md", "applyto", "copilot-instructions")

/** Substrings (lowercase) in a CLI output line that suggest it has started drafting/writing the new rules. */
private val ANALYSIS_STAGE_3_KEYWORDS = listOf("proposed-", "merge", "rule")

/** Substrings (lowercase) in a CLI output line that suggest the run is wrapping up. */
private val ANALYSIS_STAGE_4_KEYWORDS = listOf("summary", "finished", "complete")

/** Unwraps the real cause when an exception was thrown through reflection's Method.invoke(). */
private fun unwrapLearnException(e: Throwable): Throwable =
    if (e is InvocationTargetException) e.targetException ?: e else e

/** Formats milliseconds as "Ns" while under a minute, or "Mm Ss" once it reaches 60s. */
private fun formatElapsed(elapsedMs: Long): String {
    val totalSec = elapsedMs / 1000
    val minutes = totalSec / 60
    val seconds = totalSec % 60
    return if (minutes > 0) "${minutes}m ${seconds}s" else "${seconds}s"
}

/**
 * A stopwatch that can be paused/resumed -- used so time spent with the diff
 * review window open (waiting on the user) doesn't count toward the
 * "elapsed" work time shown in the progress indicator and final notification.
 */
private class ElapsedClock {
    private var accumulatedMs = 0L
    private var segmentStartMs = System.currentTimeMillis()
    private var paused = false

    @Synchronized
    fun elapsedMs(): Long =
        accumulatedMs + if (!paused) (System.currentTimeMillis() - segmentStartMs) else 0

    @Synchronized
    fun pause() {
        if (!paused) {
            accumulatedMs += System.currentTimeMillis() - segmentStartMs
            paused = true
        }
    }

    @Synchronized
    fun resume() {
        if (paused) {
            segmentStartMs = System.currentTimeMillis()
            paused = false
        }
    }
}

/**
 * "Analyse Copilot Sessions & Update Learnings": lets the user select one or
 * more exported chat sessions, then shells out to the `copilot` CLI (via
 * `com.sage.reader.learn.LearningAnalyzer`, loaded reflectively --
 * matching every other reader-module integration in this plugin) to extract
 * lessons from mistakes in those sessions and merge them into a project
 * and/or global Copilot instructions file.
 */
class AnalyzeSessionsAction : AnAction() {

    override fun actionPerformed(event: AnActionEvent) {
        try {
            val allSessions = SessionDiscovery.discoverIDESessions() + SessionDiscovery.discoverCLISessions()
            if (allSessions.isEmpty()) {
                Messages.showInfoMessage("No Copilot chat sessions found", "Analyse Sessions")
                return
            }

            val pickerDialog = MultiSessionPickerDialog(allSessions)
            if (!pickerDialog.showAndGet()) return
            val selectedSessions = pickerDialog.selectedSessions
            if (selectedSessions.isEmpty()) {
                Messages.showInfoMessage("No sessions selected", "Analyse Sessions")
                return
            }

            val project = event.project
            val settings = ExportSettingsState.getInstance().state
            val repoRoot: Path? = project?.basePath?.let { Paths.get(it) }
            val optionsDialog = LearningOptionsDialog(
                hasProject = project?.basePath != null,
                defaultProjectScope = settings.defaultLearnProjectScope,
                defaultGlobalScope = settings.defaultLearnGlobalScope,
                defaultModel = settings.defaultLearningModel,
                repoRoot = repoRoot
            )
            if (!optionsDialog.showAndGet()) return

            settings.defaultLearnProjectScope = optionsDialog.selectProject
            settings.defaultLearnGlobalScope = optionsDialog.selectGlobal
            settings.defaultLearningModel = optionsDialog.model ?: ""

            runAnalysis(
                project = project,
                sessions = selectedSessions,
                includeProject = optionsDialog.selectProject,
                includeGlobal = optionsDialog.selectGlobal,
                model = optionsDialog.model
            )
        } catch (e: Exception) {
            LOG.error("AnalyzeSessionsAction: actionPerformed failed", e)
            Messages.showErrorDialog(
                "Failed to analyse sessions:\n${e.message}",
                "Analyse Sessions Failed"
            )
        }
    }

    private fun runAnalysis(
        project: Project?,
        sessions: List<SessionInfo>,
        includeProject: Boolean,
        includeGlobal: Boolean,
        model: String?
    ) {
        object : Task.Backgroundable(project, "Analysing Copilot sessions", true) {
            override fun run(indicator: ProgressIndicator) {
                indicator.isIndeterminate = false
                val stageIndex = java.util.concurrent.atomic.AtomicInteger(0)

                // Tracks elapsed *working* time, excluding any time spent paused while the
                // user is reviewing a diff -- so the clock doesn't keep climbing while
                // they're just reading, and the final total reflects only actual work time.
                val clock = ElapsedClock()

                // Both the stage label and elapsed time are folded into indicator.text (and
                // repeated in text2) since the compact/minimized progress popup only reliably
                // shows the primary text field, not always text2.
                fun render() {
                    val elapsed = formatElapsed(clock.elapsedMs())
                    val stage = ANALYSIS_STAGES[stageIndex.get()]
                    indicator.text = "$stage ($elapsed elapsed)"
                    indicator.text2 = "$elapsed elapsed"
                    indicator.fraction = (stageIndex.get() + 1).toDouble() / ANALYSIS_STAGES.size
                }
                fun setStage(index: Int) {
                    if (index > stageIndex.get()) stageIndex.set(index)
                    render()
                }
                setStage(0) // "Reading chat sessions..."

                // Ticks the elapsed-time display once a second so the user can see it's
                // still working, without needing a real (unavailable) completion percentage.
                val ticking = java.util.concurrent.atomic.AtomicBoolean(true)
                val ticker = java.util.concurrent.Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "learn-progress-ticker").apply { isDaemon = true } }
                ticker.scheduleWithFixedDelay({
                    if (ticking.get()) render()
                }, 0, 1, java.util.concurrent.TimeUnit.SECONDS)

                try {
                    val chatSessions = sessions.flatMap { SessionDiscovery.readChatSessions(it) }
                    if (chatSessions.isEmpty()) {
                        notify("No readable session content found in the selected sessions.", NotificationType.WARNING)
                        return
                    }

                    setStage(1) // "Analysing chat for mistakes..."
                    val repoRoot: Path? = project?.basePath?.let { Paths.get(it) }
                    val outcome = invokeLearningAnalyzer(
                        chatSessions, includeProject, includeGlobal, model, repoRoot
                    ) { line ->
                        // Heuristically map the CLI's raw tool-call chatter onto a friendly
                        // stage, instead of showing that chatter verbatim -- never regresses.
                        val lower = line.lowercase()
                        when {
                            ANALYSIS_STAGE_4_KEYWORDS.any { lower.contains(it) } -> setStage(4)
                            ANALYSIS_STAGE_3_KEYWORDS.any { lower.contains(it) } -> setStage(3)
                            ANALYSIS_STAGE_2_KEYWORDS.any { lower.contains(it) } -> setStage(2)
                            else -> Unit
                        }
                    }
                    setStage(4) // "Finishing up..."
                    val realChanges = outcome.changes.filter { !it.isNoop }

                    if (realChanges.isEmpty()) {
                        notify(
                            "Analysis complete -- no rule changes were needed. Total time: ${formatElapsed(clock.elapsedMs())}",
                            NotificationType.INFORMATION
                        )
                        return
                    }

                    // Review each target file's proposed change independently -- accept some,
                    // discard others -- and within an accepted change, the user may also hand-edit
                    // the "Proposed content" pane in the diff to keep only some of the new rules.
                    val accepted = mutableListOf<Pair<Path, String>>()
                    for (change in realChanges) {
                        // Pause the clock while the diff window is up -- reading/editing
                        // time shouldn't count against the reported "elapsed" work time.
                        clock.pause()
                        val finalText = try {
                            reviewChange(project, change)
                        } finally {
                            clock.resume()
                        }
                        if (finalText != null) accepted += change.path to finalText
                    }

                    if (accepted.isEmpty()) {
                        notify(
                            "No changes were applied (all declined). Total time: ${formatElapsed(clock.elapsedMs())}",
                            NotificationType.INFORMATION
                        )
                        return
                    }

                    for ((path, text) in accepted) {
                        path.parent?.let { java.nio.file.Files.createDirectories(it) }
                        java.nio.file.Files.writeString(path, text)
                    }
                    ApplicationManager.getApplication().invokeLater {
                        for ((path, _) in accepted) {
                            LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path)?.let { vFile ->
                                FileEditorManager.getInstance(project ?: return@invokeLater).openFile(vFile, true)
                            }
                        }
                    }
                    val appliedList = accepted.joinToString(", ") { it.first.fileName.toString() }
                    LOG.info("AnalyzeSessionsAction: applied learnings to ${accepted.map { it.first }}. CLI summary:\n${outcome.cliOutput}")
                    notify(
                        "Updated ${accepted.size} file(s): $appliedList. Total time: ${formatElapsed(clock.elapsedMs())}",
                        NotificationType.INFORMATION
                    )
                } catch (e: Exception) {
                    val cause = unwrapLearnException(e)
                    LOG.error("AnalyzeSessionsAction: analysis failed", cause)
                    notify("Failed to analyse sessions:\n${cause.message}", NotificationType.ERROR)
                } finally {
                    ticking.set(false)
                    ticker.shutdownNow()
                }
            }

            /**
             * Shows [change] in a non-modal, freely resizable/scrollable diff
             * *frame* (never a modal dialog and never the shared main editor
             * area, so it can't leave a leftover editor tab), with "Apply
             * changes" / "Discard" buttons added directly to the diff
             * window's own toolbar -- the same mechanism IntelliJ's built-in
             * merge-conflict tool uses for its accept/reject actions -- so
             * they're always visible right next to the diff, never hidden
             * behind the editor or a fading notification.
             *
             * The "Proposed content" pane is editable: the user can delete or
             * tweak individual lines/rules they don't want before clicking
             * Apply, so a single file's change can be partially accepted
             * rather than all-or-nothing. Returns the final text to write if
             * the user applied (whatever the editable pane holds at that
             * moment), or null if they discarded/closed the window. Blocks
             * this background thread (not the EDT) until one happens.
             */
            private fun reviewChange(project: Project?, change: TargetChangeInfo): String? {
                val decision = CompletableFuture<String?>()
                val windowRef = arrayOfNulls<WindowWrapper>(1)
                val editableAfterRef = arrayOfNulls<DocumentContent>(1)

                ApplicationManager.getApplication().invokeLater {
                    val contentFactory = DiffContentFactory.getInstance()
                    val fileType = FileTypeManager.getInstance().getFileTypeByFileName(change.path.fileName.toString())
                    val afterContent = contentFactory.createEditable(project, change.after, fileType)
                    editableAfterRef[0] = afterContent
                    val request = SimpleDiffRequest(
                        "Proposed learnings update: ${change.path}",
                        contentFactory.create(change.before ?: ""),
                        afterContent,
                        "Current content" + if (change.before == null) " (file doesn't exist yet)" else "",
                        "Proposed content (edit to keep only some changes)"
                    )

                    val applyAction = object : AnAction(
                        "Apply Changes",
                        "Write the content shown on the right (edit it first to keep only some changes) to ${change.path}",
                        AllIcons.Actions.Commit
                    ) {
                        override fun actionPerformed(e: AnActionEvent) {
                            decision.complete(editableAfterRef[0]?.document?.text ?: change.after)
                            windowRef[0]?.close()
                        }
                    }
                    val discardAction = object : AnAction(
                        "Discard", "Drop this proposed change entirely", AllIcons.Actions.Cancel
                    ) {
                        override fun actionPerformed(e: AnActionEvent) {
                            decision.complete(null)
                            windowRef[0]?.close()
                        }
                    }
                    request.putUserData(DiffUserDataKeys.CONTEXT_ACTIONS, listOf(applyAction, discardAction))

                    val hints = DiffDialogHints(WindowWrapper.Mode.FRAME, null) { wrapper ->
                        windowRef[0] = wrapper
                        // If the user closes the frame directly (no toolbar action clicked),
                        // treat it as a decline instead of hanging this background thread forever.
                        Disposer.register(wrapper as Disposable) { decision.complete(null) }
                    }
                    DiffManager.getInstance().showDiff(project, request, hints)
                }

                return decision.get()
            }

            private fun notify(content: String, type: NotificationType) {
                NotificationGroupManager.getInstance()
                    .getNotificationGroup("Sage")
                    .createNotification(content, type)
                    .notify(project)
            }
        }.queue()
    }

    /**
     * Reflectively builds and invokes a
     * `com.sage.reader.learn.LearningAnalyzer` -- matching the
     * reflection pattern the rest of this plugin already uses for every
     * `sage-reader` type, to avoid the class-initialization issues
     * that motivated it there.
     *
     * Returns an [AnalysisOutcome] with the CLI's own summary plus the
     * *proposed* before/after content per target -- `analyze()` itself never
     * writes to the real target files. The caller reviews and writes the
     * (possibly user-edited) final content itself, only for changes the
     * user confirmed.
     */
    private fun invokeLearningAnalyzer(
        chatSessions: List<Any>,
        includeProject: Boolean,
        includeGlobal: Boolean,
        model: String?,
        repoRoot: Path?,
        onProgressLine: ((String) -> Unit)? = null
    ): AnalysisOutcome {
        val scopeClass = Class.forName("com.sage.reader.learn.InstructionsScope")
        @Suppress("UNCHECKED_CAST")
        val scopeConstants = scopeClass.enumConstants as Array<Enum<*>>
        val scopes = LinkedHashSet<Any>()
        if (includeProject) scopes += scopeConstants.first { it.name == "PROJECT" }
        if (includeGlobal) scopes += scopeConstants.first { it.name == "GLOBAL" }

        val targetsClass = Class.forName("com.sage.reader.learn.InstructionsFileTargets")
        val targetsInstance = targetsClass.getField("INSTANCE").get(null)
        val resolveMethod = targetsClass.getMethod(
            "resolve", Set::class.java, Path::class.java, String::class.java, String::class.java
        )
        @Suppress("UNCHECKED_CAST")
        val targets = resolveMethod.invoke(
            targetsInstance,
            scopes,
            repoRoot,
            System.getProperty("user.home"),
            "learnings.instructions.md"
        ) as List<Path>

        val renderOptionsClass = Class.forName("com.sage.reader.RenderOptions")
        val renderOptionsCtor = renderOptionsClass.getConstructor(Boolean::class.java, Boolean::class.java)
        val settings = ExportSettingsState.getInstance().state
        val renderOptionsInstance = renderOptionsCtor.newInstance(settings.includeThinkingBlocks, settings.includeRawToolJson)

        val requestClass = Class.forName("com.sage.reader.learn.LearningRequest")
        val requestCtor = requestClass.getConstructor(
            List::class.java, List::class.java, String::class.java, renderOptionsClass
        )
        val requestInstance = requestCtor.newInstance(chatSessions, targets, model, renderOptionsInstance)

        val analyzerClass = Class.forName("com.sage.reader.learn.LearningAnalyzer")
        val analyzerInstance = analyzerClass.getConstructor().newInstance()
        val analyzeMethod = analyzerClass.getMethod("analyze", requestClass, kotlin.jvm.functions.Function1::class.java)
        val resultInstance = analyzeMethod.invoke(analyzerInstance, requestInstance, onProgressLine)

        val cliOutput = resultInstance.javaClass.getMethod("getCliOutput").invoke(resultInstance) as String

        @Suppress("UNCHECKED_CAST")
        val rawChanges = resultInstance.javaClass.getMethod("getChanges").invoke(resultInstance) as List<Any>
        val targetChangeClass = Class.forName("com.sage.reader.learn.TargetChange")
        val getPath = targetChangeClass.getMethod("getPath")
        val getBefore = targetChangeClass.getMethod("getBefore")
        val getAfter = targetChangeClass.getMethod("getAfter")
        val changes = rawChanges.map { raw ->
            TargetChangeInfo(
                path = getPath.invoke(raw) as Path,
                before = getBefore.invoke(raw) as String?,
                after = getAfter.invoke(raw) as String
            )
        }

        return AnalysisOutcome(cliOutput = cliOutput, changes = changes)
    }
}

/** One target instructions file's proposed before/after content (reflection-friendly mirror of `TargetChange`). */
private data class TargetChangeInfo(
    val path: Path,
    val before: String?,
    val after: String
) {
    val isNoop: Boolean get() = after == (before ?: "")
}

/** Result of one `LearningAnalyzer.analyze()` call: the CLI's own summary plus the proposed [changes]. */
private class AnalysisOutcome(
    val cliOutput: String,
    val changes: List<TargetChangeInfo>
)
