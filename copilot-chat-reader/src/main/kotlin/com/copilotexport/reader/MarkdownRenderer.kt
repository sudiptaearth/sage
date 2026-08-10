package com.copilotexport.reader

import com.copilotexport.reader.model.ChatSession
import com.copilotexport.reader.model.ContentBlock
import com.copilotexport.reader.model.SessionKind
import com.copilotexport.reader.model.Turn
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * Toggles controlling how much detail [MarkdownRenderer] includes:
 * "include thinking blocks" / "include raw tool JSON vs
 * summarized".
 *
 * @param includeThinking if false, extended-thinking/reasoning blocks are
 *   omitted entirely rather than rendered.
 * @param includeToolJson if false, tool calls are rendered as a one-line
 *   summary (name, status, and a short output preview) instead of full
 *   fenced input/output JSON blocks.
 */
data class RenderOptions(
    val includeThinking: Boolean,
    val includeToolJson: Boolean
) {
    companion object {
        /** Full detail -- the original, pre-Phase-5 behavior. */
        val DEFAULT = RenderOptions(includeThinking = true, includeToolJson = true)
    }
}

/**
 * Renders a decoded [ChatSession] to a single Markdown document.
 *
 * Deliberately departs from a static "Thinking / Tool
 * calls / Response" section template: a turn's response is actually an *ordered* sequence of
 * blocks -- a Thinking block, then a round's reply text, then that round's
 * tool calls, possibly repeating for multiple rounds -- and collapsing that
 * into three fixed buckets would lose the real chronology. Instead this
 * walks [com.copilotexport.reader.model.TurnSide.blocks] in order and
 * renders each block according to its own type, which reproduces the real
 * flow for multi-round agent turns. Thinking blocks and tool calls are each
 * wrapped in a collapsible `<details><summary>` element, so the file stays skimmable for long agent
 * sessions while still containing full data.
 */
object MarkdownRenderer {

    private val TIMESTAMP_FORMAT: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss 'UTC'").withZone(ZoneOffset.UTC)

    /** Renders with full detail (thinking blocks and raw tool JSON both included). */
    fun render(session: ChatSession): String = render(session, RenderOptions.DEFAULT)

    fun render(session: ChatSession, options: RenderOptions): String {
        val sb = StringBuilder()
        sb.append("# Copilot Chat Export\n\n")
        sb.append("- Session ID: `${session.id}`\n")
        sb.append("- Kind: ${kindLabel(session.kind)}\n")
        sb.append("- Turns: ${session.turns.size}\n")
        sb.append("- Source: `${session.sourceDbPath}`\n")
        sb.append("- Exported: ${TIMESTAMP_FORMAT.format(Instant.now())}\n")
        sb.append("\n---\n")

        session.turns.forEachIndexed { index, turn ->
            renderTurn(sb, turn, index + 1, session.kind, options)
        }

        return sb.toString()
    }

    private fun kindLabel(kind: SessionKind): String = when (kind) {
        SessionKind.CHAT -> "chat (Ask mode)"
        SessionKind.AGENT -> "agent"
        SessionKind.EDIT -> "edit (raw pass-through -- schema not fully mapped, see README)"
    }

    private fun renderTurn(sb: StringBuilder, turn: Turn, index: Int, kind: SessionKind, options: RenderOptions) {
        sb.append("\n## Prompt $index")
        sb.append(" — ${TIMESTAMP_FORMAT.format(Instant.ofEpochMilli(turn.createdAt))}")
        val tags = listOfNotNull(turn.chatMode, turn.model)
        if (tags.isNotEmpty()) {
            sb.append(" _(${tags.joinToString(" · ")})_")
        }
        sb.append("\n\n")

        val promptText = turn.user.rawText.trim()
        if (promptText.isNotEmpty()) {
            sb.append(promptText).append("\n\n")
        }

        val userContext = turn.user.blocks.filterIsInstance<ContentBlock.Context>()
        renderContext(sb, userContext)

        sb.append("### Response\n\n")
        if (turn.assistant.blocks.isEmpty()) {
            val responseText = turn.assistant.rawText.trim()
            when {
                responseText.isEmpty() -> sb.append("_(no response recorded)_\n\n")
                kind == SessionKind.EDIT -> sb.append(fence(responseText, "json")).append("\n\n")
                else -> sb.append(responseText).append("\n\n")
            }
        } else {
            val trailingContext = ArrayList<ContentBlock.Context>()
            for (block in turn.assistant.blocks) {
                when (block) {
                    is ContentBlock.Text -> {
                        if (block.text.isNotBlank()) {
                            sb.append(block.text.trim()).append("\n\n")
                        }
                    }
                    is ContentBlock.Thinking -> if (options.includeThinking) renderThinking(sb, block)
                    is ContentBlock.ToolCall -> renderToolCall(sb, block, options)
                    is ContentBlock.Context -> trailingContext.add(block)
                }
            }
            renderContext(sb, trailingContext)
        }

        sb.append("\n---\n")
    }

    private fun renderContext(sb: StringBuilder, contextBlocks: List<ContentBlock.Context>) {
        if (contextBlocks.isEmpty()) return
        val distinct = contextBlocks.distinctBy { it.uri }
        sb.append("<details>\n<summary>Context (${distinct.size} file(s))</summary>\n\n")
        for (ctx in distinct) {
            if (ctx.snapshot != null) {
                sb.append("- `${ctx.uri}` (snapshot captured at prompt time)\n")
            } else {
                sb.append("- `${ctx.uri}`\n")
            }
        }
        sb.append("\n</details>\n\n")
    }

    private fun renderThinking(sb: StringBuilder, block: ContentBlock.Thinking) {
        sb.append("<details>\n<summary>Thinking</summary>\n\n")
        for (line in block.content.trim().lines()) {
            sb.append("> ").append(line).append("\n")
        }
        sb.append("\n</details>\n\n")
    }

    private fun renderToolCall(sb: StringBuilder, block: ContentBlock.ToolCall, options: RenderOptions) {
        if (!options.includeToolJson) {
            val outputPreview = block.output.trim().replace("\n", " ").let {
                if (it.length > 120) it.take(120) + "…" else it
            }
            sb.append("**Tool:** `${block.name}` (`${block.status}`)")
            if (outputPreview.isNotEmpty()) {
                sb.append(" — $outputPreview")
            }
            sb.append("\n\n")
            return
        }
        sb.append("<details>\n<summary>Tool: `${block.name}` (`${block.status}`)</summary>\n\n")
        sb.append("**Input:**\n\n")
        sb.append(fence(block.inputJson, "json")).append("\n\n")
        sb.append("**Output:**\n\n")
        val output = block.output.ifBlank { "_(no output recorded)_" }
        sb.append(fence(output)).append("\n\n")
        sb.append("</details>\n\n")
    }

    /**
     * Picks a fence long enough that it can't be prematurely closed by
     * backticks already present inside [content] (tool input/output is
     * arbitrary text -- including, sometimes, Markdown-looking text with its
     * own fenced code blocks).
     */
    private fun fence(content: String, lang: String = ""): String {
        val longestRun = Regex("`+").findAll(content).maxOfOrNull { it.value.length } ?: 0
        val fenceLen = maxOf(3, longestRun + 1)
        val fenceStr = "`".repeat(fenceLen)
        return "$fenceStr$lang\n$content\n$fenceStr"
    }
}
