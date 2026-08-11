package com.sage.reader

import com.sage.reader.learn.InstructionsFileTargets
import com.sage.reader.learn.InstructionsScope
import com.sage.reader.learn.LearningAnalyzer
import com.sage.reader.learn.LearningMode
import com.sage.reader.learn.LearningRequest
import com.sage.reader.model.ChatSession
import com.sage.reader.model.ContentBlock
import com.sage.reader.model.Turn
import java.nio.file.Path
import java.nio.file.Paths

/**
 * Manual smoke-test harness for the reader and renderer. Prints a
 * human-readable summary of every session/turn/block found -- lets you
 * sanity-check the decode pipeline against your own real Copilot data
 * without writing any file -- or, with `--render`, prints the actual
 * Markdown output for eyeballing against real data.
 *
 * Usage:
 *   ./gradlew run                                              # auto-discover all IDE plugin .db and CLI sessions, print a summary
 *   ./gradlew run --args="<session-uuid>"                     # auto-detect & render (CLI or IDE session)
 *   ./gradlew run --args="C:\path\to\some.db"                 # summarize one specific IDE plugin .db file
 *   ./gradlew run --args="--render C:\path\to\some.db"        # print full Markdown for every session in that IDE plugin .db file
 *   ./gradlew run --args="--learn <uuid> [<uuid>...] [--project] [--global] [--model <name>] [--mode conservative|aggressive]"
 *                                                              # analyse selected sessions and update learnings (see [learnMode])
 */
fun main(args: Array<String>) {
    when {
        args.isNotEmpty() && args[0] == "--render" -> {
            if (args.size < 2) {
                System.err.println("Usage: --render <path-to-db>")
                return
            }
            renderMode(Paths.get(args[1]))
        }
        args.isNotEmpty() && args[0] == "--learn" -> {
            learnMode(args.drop(1))
        }
        args.isNotEmpty() && looksLikeSessionId(args[0]) -> {
            // Auto-detect: try CLI session first, then IDE plugin
            autoDetectAndRender(args[0])
        }
        else -> {
            discoverAndPrintAll(args)
        }
    }
}

/**
 * Finds a single session (CLI or IDE plugin) by UUID without printing
 * anything, for reuse by both [autoDetectAndRender] and [learnMode].
 */
private fun findSessionById(sessionId: String): ChatSession? {
    val cliSessionDir = CliSessionLocator.cliSessionStateRoot().resolve(sessionId)
    if (CliSessionLocator.isCliSession(cliSessionDir)) {
        val session = CliSessionReader.read(cliSessionDir)
        if (session != null) return session
    }

    val ideRoot = SessionLocator.githubCopilotRoot()
    for (dbRef in SessionLocator.discover(ideRoot)) {
        try {
            val sessions = NitriteSessionReader.read(dbRef.path, dbRef.kind)
            val matching = sessions.firstOrNull { it.id == sessionId }
            if (matching != null) return matching
        } catch (e: Exception) {
            // Continue searching other .db files
        }
    }
    return null
}

/**
 * `--learn <uuid> [<uuid>...] [--project] [--global] [--model <name>] [--mode conservative|aggressive]`
 *
 * Looks up each given session UUID (CLI or IDE plugin), then hands them to
 * [LearningAnalyzer], which renders them to Markdown and delegates the
 * actual "analyse mistakes and update instructions" work to the `copilot`
 * CLI itself (non-interactive mode). Prints the CLI's own summary output.
 *
 * `--project` targets `<repo-root>/.github/copilot-instructions.md`
 * (repo root = current working directory); `--global` targets
 * `~/.copilot/instructions/learnings.instructions.md`. At least one of
 * `--project`/`--global` must be given; both may be given together.
 *
 * `--mode` selects how aggressively to propose changes -- `conservative`
 * (default; minimal/no changes unless clearly proven useful) or
 * `aggressive` (freely add, update, or remove rules whenever it thinks it
 * will help). See [LearningMode].
 */
private fun learnMode(rest: List<String>) {
    val sessionIds = mutableListOf<String>()
    var wantProject = false
    var wantGlobal = false
    var model: String? = null
    var mode = LearningMode.CONSERVATIVE

    var i = 0
    while (i < rest.size) {
        when (val arg = rest[i]) {
            "--project" -> wantProject = true
            "--global" -> wantGlobal = true
            "--model" -> {
                i++
                if (i >= rest.size) {
                    System.err.println("Usage: --learn <uuid...> [--project] [--global] [--model <name>] [--mode conservative|aggressive]")
                    return
                }
                model = rest[i]
            }
            "--mode" -> {
                i++
                if (i >= rest.size) {
                    System.err.println("Usage: --learn <uuid...> [--project] [--global] [--model <name>] [--mode conservative|aggressive]")
                    return
                }
                mode = when (rest[i].lowercase()) {
                    "conservative" -> LearningMode.CONSERVATIVE
                    "aggressive" -> LearningMode.AGGRESSIVE
                    else -> {
                        System.err.println("Unknown --mode value '${rest[i]}'. Expected 'conservative' or 'aggressive'.")
                        return
                    }
                }
            }
            else -> sessionIds += arg
        }
        i++
    }

    if (sessionIds.isEmpty()) {
        System.err.println("Usage: --learn <uuid> [<uuid>...] [--project] [--global] [--model <name>] [--mode conservative|aggressive]")
        return
    }
    if (!wantProject && !wantGlobal) {
        System.err.println("Specify at least one target: --project and/or --global")
        return
    }

    val sessions = mutableListOf<ChatSession>()
    for (id in sessionIds) {
        val session = findSessionById(id)
        if (session == null) {
            System.err.println("Session not found, skipping: $id")
            continue
        }
        sessions += session
    }
    if (sessions.isEmpty()) {
        System.err.println("No sessions found for the given ID(s). Nothing to analyse.")
        return
    }

    val scopes = buildSet {
        if (wantProject) add(InstructionsScope.PROJECT)
        if (wantGlobal) add(InstructionsScope.GLOBAL)
    }
    val targets = InstructionsFileTargets.resolve(scopes, repoRoot = Paths.get("").toAbsolutePath())

    println("Analysing ${sessions.size} session(s) in ${mode.name.lowercase()} mode, updating ${targets.size} target file(s):")
    targets.forEach { println("  - $it") }
    println()

    try {
        val result = LearningAnalyzer().analyze(
            LearningRequest(sessions = sessions, targets = targets, model = model, mode = mode)
        )
        println(result.cliOutput)
        println()
        println("Done. Updated: ${result.targets.joinToString(", ")}")
    } catch (e: Exception) {
        System.err.println("Learning analysis failed: ${e.message}")
    }
}

/**
 * Heuristic: session IDs are typically UUIDs or alphanumeric strings without path separators or file extensions.
 */
private fun looksLikeSessionId(input: String): Boolean {
    return !input.contains("\\") && !input.contains("/") && !input.endsWith(".db")
}

/**
 * Auto-detect whether the session ID is from CLI or IDE plugin, then render it.
 */
private fun autoDetectAndRender(sessionId: String) {
    // Try CLI session first
    val cliSessionDir = CliSessionLocator.cliSessionStateRoot().resolve(sessionId)
    if (CliSessionLocator.isCliSession(cliSessionDir)) {
        val session = CliSessionReader.read(cliSessionDir)
        if (session != null) {
            println(MarkdownRenderer.render(session))
            return
        }
    }

    // Try IDE plugin sessions
    val ideRoot = SessionLocator.githubCopilotRoot()
    val allDbFiles = SessionLocator.discover(ideRoot)
    for (dbRef in allDbFiles) {
        try {
            val sessions = NitriteSessionReader.read(dbRef.path, dbRef.kind)
            val matching = sessions.filter { it.id == sessionId }
            if (matching.isNotEmpty()) {
                for (session in matching) {
                    println(MarkdownRenderer.render(session))
                }
                return
            }
        } catch (e: Exception) {
            // Continue searching other .db files
        }
    }

    System.err.println("Session not found: $sessionId")
    System.err.println("Searched:")
    System.err.println("  - CLI: $cliSessionDir")
    System.err.println("  - IDE plugin: $ideRoot")
}

private fun discoverAndPrintAll(args: Array<String>) {
    val targets: List<DbFileRef> = if (args.isNotEmpty()) {
        val path = Paths.get(args[0])
        val kind = SessionLocator.classify(path)
        if (kind == null) {
            System.err.println(
                "Could not classify '$path' as a chat-sessions / chat-agent-sessions / " +
                    "chat-edit-sessions store (unexpected parent folder name). Skipping."
            )
            emptyList()
        } else {
            listOf(DbFileRef(path, kind))
        }
    } else {
        val root = SessionLocator.githubCopilotRoot()
        val found = SessionLocator.discover(root)
        if (found.isEmpty()) {
            println("No classifiable .db files found under: $root")
            println("Pass a session ID or path explicitly: ./gradlew run --args=\"e7563911-05f2-4459-aac1-feb460c882f8\"")
        }
        found
    }

    var totalSessions = 0
    var totalTurns = 0
    var failed = 0

    for (target in targets) {
        println("=".repeat(80))
        println("${target.kind}  ${target.path}")
        val sessions: List<ChatSession> = try {
            NitriteSessionReader.read(target.path, target.kind)
        } catch (e: Exception) {
            failed++
            println("  FAILED: $e")
            continue
        }
        if (sessions.isEmpty()) {
            println("  (no sessions found)")
            continue
        }
        for (session in sessions) {
            totalSessions++
            println("  Session ${session.id}  (${session.turns.size} turn(s))")
            for (turn in session.turns) {
                totalTurns++
                printTurn(turn)
            }
        }
    }

    println("=".repeat(80))
    println("Done. $totalSessions session(s), $totalTurns turn(s) read, $failed store(s) failed.")
}

private fun renderMode(path: Path) {
    val kind = SessionLocator.classify(path)
    if (kind == null) {
        System.err.println(
            "Could not classify '$path' as a chat-sessions / chat-agent-sessions / " +
                "chat-edit-sessions store (unexpected parent folder name)."
        )
        return
    }
    val sessions = try {
        NitriteSessionReader.read(path, kind)
    } catch (e: Exception) {
        System.err.println("FAILED to read '$path': $e")
        return
    }
    if (sessions.isEmpty()) {
        println("(no sessions found in $path)")
        return
    }
    for (session in sessions) {
        println(MarkdownRenderer.render(session))
        println()
    }
}

private fun printTurn(turn: Turn) {
    val modeLabel = turn.chatMode?.let { " [$it]" } ?: ""
    println("    Turn ${turn.id}$modeLabel  model=${turn.model ?: "?"}")
    println("      user:      ${preview(turn.user.rawText)}")
    if (turn.user.blocks.isNotEmpty()) {
        println("        blocks: ${blockSummary(turn.user.blocks)}")
    }
    println("      assistant: ${preview(turn.assistant.rawText)}")
    if (turn.assistant.blocks.isNotEmpty()) {
        println("        blocks: ${blockSummary(turn.assistant.blocks)}")
    }
}

private fun blockSummary(blocks: List<ContentBlock>): String {
    return blocks.joinToString(", ") { block ->
        when (block) {
            is ContentBlock.Text -> "Text(${preview(block.text, 40)})"
            is ContentBlock.Thinking -> "Thinking(${preview(block.content, 40)})"
            is ContentBlock.ToolCall -> "ToolCall(${block.name}, ${block.status})"
            is ContentBlock.Context -> "Context(${block.uri})"
        }
    }
}

private fun preview(text: String, maxLen: Int = 80): String {
    val singleLine = text.replace("\n", " ").replace("\r", "")
    return if (singleLine.length > maxLen) singleLine.take(maxLen) + "..." else singleLine
}
