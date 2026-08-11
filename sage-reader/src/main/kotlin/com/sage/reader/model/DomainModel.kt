package com.sage.reader.model

/**
 * Which on-disk store kind a session came from.
 */
enum class SessionKind {
    /** Plain "Ask" mode chat-sessions store (NtChatSession / NtTurn). */
    CHAT,

    /**
     * Agent-mode chat-agent-sessions store (NtAgentSession / NtAgentTurn).
     * Individual turns may still be "Ask" mode -- see [Turn.chatMode] --
     * since a single store can mix both.
     */
    AGENT,

    /**
     * chat-edit-sessions store (NtEditSession). This schema has not been fully
     * explored (only confirmed to exist); turns from this kind
     * are read as a best-effort raw pass-through -- see
     * [com.sage.reader.NitriteSessionReader].
     */
    EDIT
}

enum class Role { USER, ASSISTANT }

/**
 * A single chat session (one conversation), backed by one NtChatSession /
 * NtAgentSession / NtEditSession document plus its associated turns.
 */
data class ChatSession(
    val id: String,
    val kind: SessionKind,
    val sourceDbPath: String,
    val turns: List<Turn>
)

/**
 * One prompt/response exchange. Mirrors NtTurn / NtAgentTurn: a `request`
 * side (the user's message) and a `response` side (Copilot's reply), each
 * independently modeled as a [TurnSide].
 */
data class Turn(
    val id: String,
    val sessionId: String,
    val createdAt: Long,
    /**
     * "Ask" or "Agent", from the per-turn `chatMode` field. Null for plain
     * [SessionKind.CHAT] turns, which don't have this field at all.
     */
    val chatMode: String?,
    val model: String?,
    val user: TurnSide,
    val assistant: TurnSide
)

/**
 * One side (request or response) of a [Turn].
 *
 * @param rawText the plain-text fallback: `request.content`/`response.content`
 *   for [SessionKind.CHAT] turns, or `stringContent` for [SessionKind.AGENT]
 *   turns. Always safe to render even if [blocks] decoding found nothing.
 * @param blocks the richer decoded content, if any (empty for plain CHAT
 *   turns, which have no `contents` field to decode).
 */
data class TurnSide(
    val role: Role,
    val rawText: String,
    val blocks: List<ContentBlock>
)

/**
 * One decoded unit of a turn side's rich `contents` graph. See
 * SCHEMA_FINDINGS.md for the block-type table this maps to.
 */
sealed class ContentBlock {
    /** A Markdown block (prompt text) or an AgentRound's `reply` text. */
    data class Text(val text: String) : ContentBlock()

    /** A confirmed real extended-thinking / reasoning block. */
    data class Thinking(val id: String, val content: String) : ContentBlock()

    /**
     * One tool invocation from an AgentRound's `toolCalls[]`.
     *
     * @param inputJson the tool's input, already pretty-printed as JSON text
     *   (see [com.sage.reader.json.toPrettyString]) so the renderer
     *   can drop it straight into a fenced code block.
     * @param output the joined text of the tool's `result[]` entries.
     */
    data class ToolCall(
        val id: String,
        val name: String,
        val status: String,
        val inputJson: String,
        val output: String
    ) : ContentBlock()

    /**
     * A file reference attached to a prompt (FixedContextPanel / References),
     * optionally with a full file-content snapshot captured at prompt time
     * (Hide -> WorkingSet).
     */
    data class Context(val uri: String, val snapshot: String?) : ContentBlock()
}
