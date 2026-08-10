package com.copilotexport.reader

import com.copilotexport.reader.model.ChatSession
import com.copilotexport.reader.model.ContentBlock
import com.copilotexport.reader.model.Turn
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
