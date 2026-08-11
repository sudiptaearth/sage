package com.sage.reader.learn

import com.sage.reader.MarkdownRenderer
import com.sage.reader.RenderOptions
import com.sage.reader.model.ChatSession
import java.nio.file.Files
import java.nio.file.Path
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Comparator

/** One "analyse selected sessions and update learnings" request. */
data class LearningRequest @JvmOverloads constructor(
    val sessions: List<ChatSession>,
    /** Resolved instructions file path(s) to create/update -- see [InstructionsFileTargets]. */
    val targets: List<Path>,
    /** Passed straight through as `--model`; null lets Copilot pick its own default. */
    val model: String? = null,
    val renderOptions: RenderOptions = RenderOptions.DEFAULT
)

/**
 * The proposed before/after content for a single target instructions file.
 * [before] is null if the file did not exist yet. Nothing is written to
 * [path] until a caller explicitly calls [LearningAnalyzer.applyChanges]
 * with this change included -- `analyze()` itself never touches [path].
 */
data class TargetChange(
    val path: Path,
    val before: String?,
    val after: String
) {
    /** True if [after] would actually change what's on disk at [path]. */
    val isNoop: Boolean get() = after == (before ?: "")
}

/** Outcome of a [LearningAnalyzer.analyze] run. */
data class LearningResult(
    val targets: List<Path>,
    /** The `copilot` CLI's own captured stdout (its summary of what it changed). */
    val cliOutput: String,
    /** Proposed before/after content per target -- nothing has been written yet. */
    val changes: List<TargetChange>
)

/**
 * Orchestrates one "analyse sessions, learn from mistakes, update the
 * instructions file(s)" run:
 *
 * 1. Renders each selected [ChatSession] to a Markdown file in a fresh temp
 *    directory (reusing the existing [MarkdownRenderer] -- no new rendering
 *    logic).
 * 2. Builds a single prompt telling Copilot to read those transcripts,
 *    extract concrete lessons, then read the target instructions file(s)
 *    and write its *proposed* merged content to sibling temp files (never
 *    the real target paths directly -- see [TargetChange]).
 * 3. Delegates the actual work to [CopilotCliInvoker], which shells out to
 *    the `copilot` CLI in non-interactive mode -- Copilot does the reading,
 *    reasoning, and de-duplicating via its own file tools; this class never
 *    parses or diffs the instructions file content itself.
 * 4. Reads back the proposed content and returns it (paired with each
 *    target's current content, if any) as [LearningResult.changes] --
 *    nothing on disk at the real target paths is touched by [analyze]
 *    itself. Callers review the before/after and only call [applyChanges]
 *    for the ones the user confirms.
 * 5. Cleans up the temp directory afterward, regardless of outcome.
 */
class LearningAnalyzer @JvmOverloads constructor(
    private val invoker: CopilotCliInvoker = CopilotCliInvoker(),
    private val tempDirFactory: () -> Path = { Files.createTempDirectory("copilot-chat-learn-") },
    /** Injectable for deterministic tests; defaults to the real current time. */
    private val clock: () -> ZonedDateTime = { ZonedDateTime.now() }
) {

    fun analyze(request: LearningRequest, onProgressLine: ((String) -> Unit)? = null): LearningResult {
        require(request.sessions.isNotEmpty()) { "At least one session must be selected" }
        require(request.targets.isNotEmpty()) { "At least one target instructions file must be selected" }

        val tempDir = tempDirFactory()
        try {
            val sessionFiles = writeSessionFiles(tempDir, request.sessions, request.renderOptions)
            val before = request.targets.associateWith { if (Files.exists(it)) Files.readString(it) else null }
            val proposedFiles = request.targets.mapIndexed { index, target ->
                tempDir.resolve("proposed-$index-${target.fileName}")
            }
            val timestamp = formatTimestamp(clock())
            val instructionsFile = writeInstructionsPromptFile(
                tempDir, sessionFiles, request.targets, proposedFiles, timestamp
            )
            val prompt = "Read and fully follow the non-interactive task instructions in the file " +
                "'$instructionsFile', including reading and writing whatever other files it references. " +
                "Do not ask clarifying questions -- carry out every step yourself."
            val addDirs = (sessionFiles.map { it.parent } + request.targets.mapNotNull { it.parent } + listOf(tempDir))
                .distinct()

            val result = invoker.runPrompt(
                prompt = prompt,
                addDirs = addDirs,
                model = request.model,
                onOutputLine = onProgressLine
            )

            val changes = request.targets.mapIndexed { index, target ->
                val proposedFile = proposedFiles[index]
                val beforeContent = before.getValue(target)
                val afterContent = if (Files.exists(proposedFile)) Files.readString(proposedFile) else (beforeContent ?: "")
                TargetChange(path = target, before = beforeContent, after = afterContent)
            }
            return LearningResult(targets = request.targets, cliOutput = result.stdout, changes = changes)
        } finally {
            deleteRecursively(tempDir)
        }
    }

    /**
     * Writes [change].after to [change].path for every change in [changes],
     * creating parent directories as needed. Callers should only pass the
     * subset of a [LearningResult.changes] the user actually confirmed --
     * this is the sole place any real target instructions file is written.
     */
    fun applyChanges(changes: List<TargetChange>) {
        for (change in changes) {
            change.path.parent?.let { Files.createDirectories(it) }
            Files.writeString(change.path, change.after)
        }
    }

    /**
     * Writes the full multi-line task instructions to a file instead of
     * passing them as the `-p` CLI argument. On Windows, the `copilot`
     * executable is a `.cmd` shim invoked through `cmd.exe`, and a long,
     * multi-line argument does not reliably survive `cmd.exe`'s command-line
     * quoting via [ProcessBuilder] (embedded newlines can truncate/corrupt
     * the argument, observed in practice: Copilot only ever saw the first
     * line and then asked a clarifying question instead of reading the
     * session files). Routing the actual instructions through a file sent
     * over `--add-dir` sidesteps that entirely -- the `-p` argument itself
     * stays a single short line.
     */
    private fun writeInstructionsPromptFile(
        tempDir: Path,
        sessionFiles: List<Path>,
        targets: List<Path>,
        proposedFiles: List<Path>,
        timestamp: String
    ): Path {
        val path = tempDir.resolve("task-instructions.md")
        Files.writeString(path, buildPrompt(sessionFiles, targets, proposedFiles, timestamp))
        return path
    }

    /** Renders each session to its own Markdown file under [tempDir]. Visible for testing. */
    internal fun writeSessionFiles(
        tempDir: Path,
        sessions: List<ChatSession>,
        options: RenderOptions
    ): List<Path> {
        return sessions.mapIndexed { index, session ->
            val safeId = session.id.replace(Regex("[^A-Za-z0-9._-]"), "_")
            val path = tempDir.resolve("session-${index + 1}-$safeId.md")
            Files.writeString(path, MarkdownRenderer.render(session, options))
            path
        }
    }

    /** Builds the full non-interactive prompt text. Visible for testing. */
    internal fun buildPrompt(
        sessionFiles: List<Path>,
        targets: List<Path>,
        proposedFiles: List<Path> = targets,
        timestamp: String = ""
    ): String {
        val sessionList = sessionFiles.joinToString("\n") { "- $it" }
        val targetPairs = targets.zip(proposedFiles).joinToString("\n") { (target, proposed) ->
            "- target: $target\n  write proposed result to: $proposed"
        }
        return """
            |You are analysing past Copilot chat session transcripts to learn from mistakes
            |made in them, so future sessions can avoid repeating them.
            |
            |Session transcript files (Markdown, one per session):
            |$sessionList
            |
            |For each transcript file:
            |1. Read it.
            |2. Identify concrete mistakes the assistant made (wrong assumptions, incorrect
            |   code or commands, missed or misread instructions, unnecessary or risky
            |   actions, etc.) and how the user corrected them, if visible in the transcript.
            |3. Turn each real mistake into a short, general, actionable rule that would help
            |   prevent a similar mistake in a future, unrelated session. Skip anything that's
            |   too specific to that one session to generalize usefully.
            |
            |Then, for EACH of the following target/proposed-output path pair(s):
            |$targetPairs
            |
            |- If the "target" file already exists, read its current full content first.
            |- Merge the new rules you derived into it: avoid duplicating rules that already
            |  exist (in meaning, not just exact wording), keep the file's existing format and
            |  any existing frontmatter/sections, and produce a single well-organized,
            |  de-duplicated final version -- not a blind append and not a wholesale rewrite of
            |  unrelated existing content.
            |- If the "target" file does not exist yet, start a brand-new file's content from
            |  scratch. If its path lives under a directory literally named "instructions" (i.e.
            |  it is a `~/.copilot/instructions/*.instructions.md` global file), start it with
            |  YAML frontmatter exactly like:
            |  ---
            |  applyTo: '**'
            |  ---
            |  followed by the rules as a Markdown bullet list. Otherwise (a
            |  `.github/copilot-instructions.md` project file) just write the rules as
            |  Markdown, matching the style GitHub's own `copilot init` command produces.
            |- Additionally, in the merged content, find any existing section/line that starts
            |  with the 🧠 emoji (a "learnings status indicator"). If one exists, update ONLY
            |  its timestamp; otherwise add a new short section near the top of the file, right
            |  after any YAML frontmatter, containing exactly this instruction (keep the
            |  surrounding heading/wording but the emoji line's text must match exactly,
            |  including the timestamp below):
            |
            |  ## Learnings status indicator
            |  At the end of every response, append this line verbatim so the user can see
            |  these instructions are active and when they were last updated:
            |
            |  🧠 Sage's wisdom last updated: $timestamp
            |
            |- IMPORTANT: Do NOT write anything to the "target" path itself. Instead write the
            |  complete final merged content -- the full file content the "target" path should
            |  end up with -- to the corresponding "write proposed result to" path given above,
            |  creating that file (and its parent directory, if needed) using your file-editing
            |  tools. A separate step outside your control decides whether to actually copy it
            |  over the real "target" file.
            |
            |Work through this fully non-interactively: make reasonable judgment calls
            |yourself instead of asking clarifying questions, and do not stop until every
            |proposed-output path listed above has been written. When finished, print a
            |short bullet-point summary of the new or changed rules for each target file.
        """.trimMargin()
    }

    private fun deleteRecursively(dir: Path) {
        if (!Files.exists(dir)) return
        try {
            Files.walk(dir)
                .sorted(Comparator.reverseOrder())
                .forEach { Files.deleteIfExists(it) }
        } catch (e: Exception) {
            // Best-effort cleanup; leftover temp files aren't fatal.
        }
    }

    companion object {
        /** Formats e.g. `ZonedDateTime` 2026-08-08 18:40 as `"6:40 PM, 8th Aug, 2026"`. */
        internal fun formatTimestamp(dateTime: ZonedDateTime): String {
            val time = dateTime.format(DateTimeFormatter.ofPattern("h:mm a"))
            val day = dateTime.dayOfMonth
            val month = dateTime.format(DateTimeFormatter.ofPattern("MMM"))
            return "$time, $day${ordinalSuffix(day)} $month, ${dateTime.year}"
        }

        private fun ordinalSuffix(day: Int): String = when {
            day in 11..13 -> "th"
            day % 10 == 1 -> "st"
            day % 10 == 2 -> "nd"
            day % 10 == 3 -> "rd"
            else -> "th"
        }
    }
}
