package com.copilotexport.reader

import com.copilotexport.reader.json.JsonValue
import com.copilotexport.reader.json.field
import com.copilotexport.reader.json.stringField
import com.copilotexport.reader.json.toPrettyString
import com.copilotexport.reader.model.ChatSession
import com.copilotexport.reader.model.ContentBlock
import com.copilotexport.reader.model.Role
import com.copilotexport.reader.model.SessionKind
import com.copilotexport.reader.model.Turn
import com.copilotexport.reader.model.TurnSide
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.time.format.DateTimeParseException

/**
 * Reads GitHub Copilot CLI sessions from ~/.copilot/session-state/{sessionId}/ directories.
 * CLI sessions store data as JSONL (events.jsonl) + YAML (workspace.yaml), unlike IDE plugin
 * sessions which use Nitrite .db files.
 *
 * Extracts rich content including:
 * - User messages and assistant responses
 * - Extended thinking/reasoning blocks
 * - Tool calls and their results
 * - Markdown content blocks
 */
object CliSessionReader {

    fun read(sessionDir: Path): ChatSession? {
        if (!CliSessionLocator.isCliSession(sessionDir)) {
            return null
        }

        val sessionId = sessionDir.fileName.toString()
        val eventsFile = sessionDir.resolve("events.jsonl")

        // Parse events.jsonl to extract turns
        val turns = parseEvents(eventsFile, sessionId)

        return ChatSession(
            id = sessionId,
            kind = SessionKind.AGENT,
            sourceDbPath = sessionDir.toAbsolutePath().toString(),
            turns = turns
        )
    }

    /**
     * Returns the first "user.message" event's plain-text content, or null if
     * there isn't one / the file can't be read. Unlike [read], this stops at
     * the first match instead of parsing the whole events.jsonl file, so it's
     * cheap enough to call for every session in a picker list (e.g. the
     * plugin's session picker preview).
     */
    fun firstUserMessage(sessionDir: Path): String? {
        val eventsFile = sessionDir.resolve("events.jsonl")
        return try {
            Files.newBufferedReader(eventsFile).use { reader ->
                reader.lineSequence().forEach { line ->
                    try {
                        val json = JsonValue.parse(line)
                        if (json is JsonValue.JObject && json.stringField("type") == "user.message") {
                            val content = json.field("data").stringField("content")?.trim()
                            if (!content.isNullOrEmpty()) return content
                        }
                    } catch (e: Exception) {
                        // Ignore malformed JSON lines
                    }
                }
                null
            }
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Parses events.jsonl into one [Turn] per *user interaction* (one Copilot
     * CLI `interactionId`), not one per internal `turnId`.
     *
     * A single user prompt can trigger several internal agent rounds --
     * distinct `assistant.turn_start`/`turn_end` pairs with their own
     * `turnId` (0, 1, 2, ...) -- for example one round that just thinks,
     * another that calls a tool, and a final one with the reply. All of
     * these rounds share the same `interactionId`. Previously each round
     * became its own [Turn], which re-rendered the identical user prompt
     * once per round in the exported Markdown. Rounds are now merged into a
     * single [Turn] per `interactionId`, concatenating each round's blocks
     * in chronological order -- which is exactly the "ordered sequence of
     * blocks" shape [com.copilotexport.reader.MarkdownRenderer] already
     * expects for multi-round agent turns.
     */
    /**
     * Parses an event's top-level `timestamp` field (an ISO-8601 instant,
     * e.g. `"2026-08-05T11:22:33.583Z"`) into epoch millis. Falls back to
     * the current wall-clock time if the field is missing or malformed, so
     * a single bad line doesn't blow up parsing -- but this should be rare;
     * relying on wall-clock time for every event (the previous behavior)
     * made every exported turn show the *export* time instead of the actual
     * conversation time.
     */
    private fun parseEventTimestampMillis(json: JsonValue.JObject): Long {
        val raw = json.stringField("timestamp") ?: return System.currentTimeMillis()
        return try {
            Instant.parse(raw).toEpochMilli()
        } catch (e: DateTimeParseException) {
            System.currentTimeMillis()
        }
    }

    /**
     * Extracts the human-readable text from a `tool.execution_complete`
     * event's `result` field.
     *
     * `result` is *not* a plain string -- it's an object shaped like
     * `{"content": "...", "detailedContent": "..."}` (the CLI's
     * summarized-vs-full-detail pair). The previous code called
     * `data.stringField("result")`, which only succeeds when a field is a
     * JSON string; since `result` is always an object here, that silently
     * returned null/"" for every tool call, which the renderer then showed
     * as "_(no output recorded)_" even though the tool actually produced
     * output. Prefers `detailedContent` (the fuller text) and falls back to
     * `content`, or -- for older/other shapes -- a plain string `result`.
     */
    private fun extractToolResultText(result: JsonValue?): String {
        if (result is JsonValue.JString) return result.value
        return result.stringField("detailedContent")
            ?: result.stringField("content")
            ?: ""
    }

    private fun parseEvents(eventsFile: Path, sessionId: String): List<Turn> {
        val turns = mutableListOf<Turn>()
        val turnsByTurnId = mutableMapOf<String, MutableTurn>()
        val toolResultsByTurnId = mutableMapOf<String, MutableList<ToolResult>>()
        val interactionIdByTurnId = mutableMapOf<String, String>()
        val lastUserContent = mutableMapOf<String, String>()
        // Preserves first-seen order so interactions are emitted in the order they started.
        val interactions = LinkedHashMap<String, MutableInteraction>()

        fun finalizeInteraction(interactionId: String) {
            val interaction = interactions.remove(interactionId) ?: return
            if (interaction.blocks.isEmpty() && interaction.userContent.isBlank()) return
            val finalText = interaction.blocks.filterIsInstance<ContentBlock.Text>()
                .lastOrNull()?.text ?: ""
            turns.add(
                Turn(
                    id = interactionId,
                    sessionId = sessionId,
                    createdAt = interaction.createdAt,
                    chatMode = "Agent",
                    model = interaction.model,
                    user = TurnSide(Role.USER, interaction.userContent, emptyList()),
                    assistant = TurnSide(Role.ASSISTANT, finalText, interaction.blocks)
                )
            )
        }

        try {
            Files.readAllLines(eventsFile).forEach { line ->
                try {
                    val json = JsonValue.parse(line)
                    if (json !is JsonValue.JObject) return@forEach

                    val eventType = json.stringField("type") ?: return@forEach
                    val data = json.field("data")
                    // Real event time (e.g. "2026-08-05T11:22:33.583Z"), not export/parse
                    // wall-clock time -- see parseEventTimestampMillis() doc.
                    val eventTimeMillis = parseEventTimestampMillis(json)

                    when (eventType) {
                        "user.message" -> {
                            val content = data.stringField("content") ?: ""
                            val interactionId = data.stringField("interactionId") ?: ""
                            lastUserContent[interactionId] = content
                            // A new user prompt means any other still-open interaction is done.
                            interactions.keys.filter { it != interactionId }.toList()
                                .forEach { finalizeInteraction(it) }
                            interactions.getOrPut(interactionId) {
                                MutableInteraction(content, eventTimeMillis)
                            }.userContent = content
                        }

                        "assistant.turn_start" -> {
                            val turnId = data.stringField("turnId") ?: ""
                            val interactionId = data.stringField("interactionId") ?: ""
                            interactionIdByTurnId[turnId] = interactionId

                            turnsByTurnId[turnId] = MutableTurn(
                                id = turnId,
                                userContent = lastUserContent[interactionId] ?: "",
                                assistantContent = "",
                                model = null,
                                reasoningText = null,
                                toolCalls = mutableListOf(),
                                createdAt = eventTimeMillis
                            )
                            toolResultsByTurnId[turnId] = mutableListOf()
                            interactions.getOrPut(interactionId) {
                                MutableInteraction(lastUserContent[interactionId] ?: "", eventTimeMillis)
                            }
                        }

                        "assistant.message" -> {
                            val content = data.stringField("content") ?: ""
                            val model = data.stringField("model") ?: ""
                            val turnId = data.stringField("turnId") ?: ""
                            val reasoningText = data.stringField("reasoningText")

                            turnsByTurnId[turnId]?.let { mutableTurn ->
                                mutableTurn.assistantContent = content
                                mutableTurn.model = model
                                mutableTurn.reasoningText = reasoningText
                                
                                // Extract tool requests
                                val toolRequests = data.field("toolRequests")
                                if (toolRequests is JsonValue.JArray) {
                                    toolRequests.items.forEach { toolReq ->
                                        if (toolReq is JsonValue.JObject) {
                                            // The CLI's toolRequests[] entries key the tool
                                            // call as "toolCallId", not "id" -- reading "id"
                                            // silently produced an empty string, which then
                                            // never matched the real id on the corresponding
                                            // tool.execution_complete event, so every tool
                                            // call's output was rendered as
                                            // "_(no output recorded)_" even when the tool
                                            // actually returned output.
                                            val toolId = toolReq.stringField("toolCallId") ?: ""
                                            val toolName = toolReq.stringField("name") ?: ""
                                            val toolArgs = toolReq.field("arguments")
                                            mutableTurn.toolCalls.add(ToolCall(
                                                id = toolId,
                                                name = toolName,
                                                arguments = toolArgs,
                                                result = null
                                            ))
                                        }
                                    }
                                }
                            }
                        }

                        "tool.execution_complete" -> {
                            val toolCallId = data.stringField("toolCallId") ?: ""
                            val turnId = data.stringField("turnId") ?: ""
                            val result = extractToolResultText(data.field("result"))
                            val success = data.field("success")?.asBooleanOrNull() ?: true

                            toolResultsByTurnId[turnId]?.add(ToolResult(
                                toolCallId = toolCallId,
                                result = result,
                                success = success
                            ))
                        }

                        "assistant.turn_end" -> {
                            val turnId = data.stringField("turnId") ?: ""
                            turnsByTurnId[turnId]?.let { mutableTurn ->
                                mutableTurn.toolResults = toolResultsByTurnId[turnId] ?: mutableListOf()
                                val interactionId = interactionIdByTurnId[turnId] ?: turnId
                                val interaction = interactions.getOrPut(interactionId) {
                                    MutableInteraction(mutableTurn.userContent, mutableTurn.createdAt)
                                }

                                // The CLI resends the same cumulative reasoning text on every
                                // round of a single interaction; only append it when it has
                                // actually grown/changed since the last round, to avoid
                                // rendering identical "Thinking" blocks multiple times.
                                val skipDuplicateReasoning = mutableTurn.reasoningText != null &&
                                    mutableTurn.reasoningText == interaction.lastReasoningText
                                interaction.blocks.addAll(mutableTurn.toBlocks(skipReasoning = skipDuplicateReasoning))
                                if (!mutableTurn.reasoningText.isNullOrBlank()) {
                                    interaction.lastReasoningText = mutableTurn.reasoningText
                                }
                                if (!mutableTurn.model.isNullOrBlank()) {
                                    interaction.model = mutableTurn.model
                                }

                                turnsByTurnId.remove(turnId)
                                toolResultsByTurnId.remove(turnId)
                                interactionIdByTurnId.remove(turnId)
                            }
                        }
                    }
                } catch (e: Exception) {
                    // Ignore malformed JSON lines
                }
            }
        } catch (e: Exception) {
            // Ignore file read errors
        }

        // Finalize any interactions still open at end-of-file (e.g. the last prompt).
        interactions.keys.toList().forEach { finalizeInteraction(it) }

        return turns.sortedBy { it.createdAt }
    }

    private data class ToolCall(
        val id: String,
        val name: String,
        val arguments: JsonValue?,
        var result: String?
    )

    private data class ToolResult(
        val toolCallId: String,
        val result: String,
        val success: Boolean
    )

    /**
     * Accumulates blocks across all internal agent rounds (`turnId`s) that
     * share one `interactionId`, so they render as a single [Turn] -- see
     * [parseEvents].
     */
    private data class MutableInteraction(
        var userContent: String,
        val createdAt: Long,
        var model: String? = null,
        var lastReasoningText: String? = null,
        val blocks: MutableList<ContentBlock> = mutableListOf()
    )

    private data class MutableTurn(
        val id: String,
        var userContent: String,
        var assistantContent: String,
        var model: String?,
        var reasoningText: String?,
        val toolCalls: MutableList<ToolCall>,
        var toolResults: MutableList<ToolResult> = mutableListOf(),
        val createdAt: Long
    ) {
        /** Builds this round's ordered content blocks (reasoning, text, then tool calls). */
        fun toBlocks(skipReasoning: Boolean): List<ContentBlock> {
            val assistantBlocks = mutableListOf<ContentBlock>()

            // Add reasoning block if present and not a duplicate of the previous round's.
            if (!skipReasoning && !reasoningText.isNullOrBlank()) {
                assistantBlocks.add(ContentBlock.Thinking(
                    id = "reasoning-$id",
                    content = reasoningText!!
                ))
            }

            // Add assistant response text
            if (assistantContent.isNotBlank()) {
                assistantBlocks.add(ContentBlock.Text(assistantContent))
            }

            // Add tool calls with their results
            toolCalls.forEach { toolCall ->
                val toolResult = toolResults.find { it.toolCallId == toolCall.id }
                val inputJson = if (toolCall.arguments != null) {
                    toolCall.arguments.toPrettyString()
                } else {
                    ""
                }
                
                assistantBlocks.add(ContentBlock.ToolCall(
                    id = toolCall.id,
                    name = toolCall.name,
                    status = if (toolResult?.success == true) "success" else "error",
                    inputJson = inputJson,
                    output = toolResult?.result ?: ""
                ))
            }

            return assistantBlocks
        }
    }
}
