package com.sage.reader

import com.sage.reader.model.ChatSession
import com.sage.reader.model.ContentBlock
import com.sage.reader.model.Role
import com.sage.reader.model.SessionKind
import com.sage.reader.model.Turn
import com.sage.reader.model.TurnSide
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MarkdownRendererTest {

    private fun session(kind: SessionKind, turns: List<Turn>, id: String = "sess-1"): ChatSession =
        ChatSession(id = id, kind = kind, sourceDbPath = "/fake/path.db", turns = turns)

    private fun turn(
        id: String = "turn-1",
        createdAt: Long = 1000L,
        chatMode: String? = null,
        model: String? = null,
        userText: String = "",
        userBlocks: List<ContentBlock> = emptyList(),
        assistantText: String = "",
        assistantBlocks: List<ContentBlock> = emptyList()
    ): Turn = Turn(
        id = id,
        sessionId = "sess-1",
        createdAt = createdAt,
        chatMode = chatMode,
        model = model,
        user = TurnSide(Role.USER, userText, userBlocks),
        assistant = TurnSide(Role.ASSISTANT, assistantText, assistantBlocks)
    )

    @Test
    fun rendersPlainChatTurn() {
        val s = session(
            SessionKind.CHAT,
            listOf(turn(userText = "How do I center a div?", assistantText = "Use flexbox."))
        )

        val md = MarkdownRenderer.render(s)

        assertTrue(md.contains("How do I center a div?"))
        assertTrue(md.contains("Use flexbox."))
        assertFalse(md.contains("_(no response recorded)_"))
        // No chatMode/model on plain chat turns -> no tag suffix.
        assertFalse(md.contains("_("))
    }

    @Test
    fun rendersAgentTurnWithThinkingAndToolCall() {
        val s = session(
            SessionKind.AGENT,
            listOf(
                turn(
                    chatMode = "Agent",
                    model = "gpt-4.1",
                    userText = "Fix the bug",
                    assistantBlocks = listOf(
                        ContentBlock.Thinking("thinking_0", "Let me look at the code first."),
                        ContentBlock.Text("I found the issue."),
                        ContentBlock.ToolCall(
                            id = "call_1",
                            name = "grep_search",
                            status = "completed",
                            inputJson = "{\n  \"query\": \"foo\"\n}",
                            output = "1 match found"
                        )
                    )
                )
            )
        )

        val md = MarkdownRenderer.render(s)

        assertTrue(md.contains("_(Agent · gpt-4.1)_"))
        assertTrue(md.contains("<summary>Thinking</summary>"))
        assertTrue(md.contains("> Let me look at the code first."))
        assertTrue(md.contains("I found the issue."))
        assertTrue(md.contains("Tool: `grep_search` (`completed`)"))
        assertTrue(md.contains("\"query\": \"foo\""))
        assertTrue(md.contains("1 match found"))
    }

    @Test
    fun rendersMultiRoundToolCallsInOrder() {
        val s = session(
            SessionKind.AGENT,
            listOf(
                turn(
                    assistantBlocks = listOf(
                        ContentBlock.Text("Round one reply."),
                        ContentBlock.ToolCall("c1", "tool_one", "completed", "{}", "out1"),
                        ContentBlock.Text("Round two reply."),
                        ContentBlock.ToolCall("c2", "tool_two", "completed", "{}", "out2")
                    )
                )
            )
        )

        val md = MarkdownRenderer.render(s)

        val iRound1 = md.indexOf("Round one reply.")
        val iTool1 = md.indexOf("tool_one")
        val iRound2 = md.indexOf("Round two reply.")
        val iTool2 = md.indexOf("tool_two")

        assertTrue(iRound1 in 0 until iTool1)
        assertTrue(iTool1 in 0 until iRound2)
        assertTrue(iRound2 in 0 until iTool2)
    }

    @Test
    fun escapesBackticksInToolPayloadsWithLongerFence() {
        val trickyOutput = "Here is some code:\n```\nconsole.log('hi');\n```\ndone."
        val s = session(
            SessionKind.AGENT,
            listOf(
                turn(
                    assistantBlocks = listOf(
                        ContentBlock.ToolCall("c1", "run_code", "completed", "{}", trickyOutput)
                    )
                )
            )
        )

        val md = MarkdownRenderer.render(s)

        // The output's own triple-backtick fence must survive intact...
        assertTrue(md.contains(trickyOutput))
        // ...which means the fence wrapping it must be longer than three backticks.
        assertTrue(md.contains("````\n$trickyOutput\n````"))
    }

    @Test
    fun rendersContextBlocksDeduplicatedByUri() {
        val s = session(
            SessionKind.AGENT,
            listOf(
                turn(
                    userText = "Fix eventNew.js",
                    userBlocks = listOf(
                        ContentBlock.Context("file:///eventNew.js", null),
                        ContentBlock.Context("file:///eventNew.js", "const x = 1;"),
                        ContentBlock.Context("file:///other.js", null)
                    )
                )
            )
        )

        val md = MarkdownRenderer.render(s)

        assertTrue(md.contains("Context (2 file(s))"))
        assertTrue(md.contains("`file:///eventNew.js`"))
        assertTrue(md.contains("`file:///other.js`"))
    }

    @Test
    fun handlesMissingOrEmptyResponseGracefully() {
        val s = session(
            SessionKind.CHAT,
            listOf(turn(userText = "Ping?", assistantText = ""))
        )

        val md = MarkdownRenderer.render(s)

        assertTrue(md.contains("_(no response recorded)_"))
    }

    @Test
    fun rendersMultiTurnSessionInOrder() {
        val s = session(
            SessionKind.CHAT,
            listOf(
                turn(id = "t1", userText = "First question", assistantText = "First answer"),
                turn(id = "t2", userText = "Second question", assistantText = "Second answer")
            )
        )

        val md = MarkdownRenderer.render(s)

        assertTrue(md.contains("## Prompt 1"))
        assertTrue(md.contains("## Prompt 2"))
        assertTrue(md.indexOf("## Prompt 1") < md.indexOf("First question"))
        assertTrue(md.indexOf("First question") < md.indexOf("## Prompt 2"))
        assertTrue(md.indexOf("## Prompt 2") < md.indexOf("Second question"))
    }

    @Test
    fun rendersEditKindRawJsonInFencedBlock() {
        val s = session(
            SessionKind.EDIT,
            listOf(turn(assistantText = "{\n  \"id\": \"edit-1\"\n}"))
        )

        val md = MarkdownRenderer.render(s)

        assertTrue(md.contains("```json"))
        assertTrue(md.contains("\"id\": \"edit-1\""))
    }

    @Test
    fun rendersEmptySessionWithHeaderOnly() {
        val s = session(SessionKind.CHAT, emptyList())

        val md = MarkdownRenderer.render(s)

        assertTrue(md.contains("Session ID: `sess-1`"))
        assertTrue(md.contains("Turns: 0"))
        assertFalse(md.contains("## Prompt"))
    }

    @Test
    fun equalsIsStructuralForToolCallBlocks() {
        // Sanity check relied on by other tests: ContentBlock is a data class hierarchy,
        // so two separately-constructed ToolCall blocks with the same fields are equal.
        val a = ContentBlock.ToolCall("id", "name", "status", "{}", "out")
        val b = ContentBlock.ToolCall("id", "name", "status", "{}", "out")
        assertEquals(a, b)
    }

    @Test
    fun omitsThinkingBlockWhenDisabled() {
        val s = session(
            SessionKind.AGENT,
            listOf(turn(assistantBlocks = listOf(
                ContentBlock.Thinking("t1", "The user wants X, so I should look at Y."),
                ContentBlock.Text("Here's the answer.")
            )))
        )

        val md = MarkdownRenderer.render(s, RenderOptions(includeThinking = false, includeToolJson = true))

        assertFalse(md.contains("Thinking"))
        assertFalse(md.contains("The user wants X"))
        assertTrue(md.contains("Here's the answer."))
    }

    @Test
    fun includesThinkingBlockByDefault() {
        val s = session(
            SessionKind.AGENT,
            listOf(turn(assistantBlocks = listOf(ContentBlock.Thinking("t1", "reasoning here"))))
        )

        val md = MarkdownRenderer.render(s)

        assertTrue(md.contains("Thinking"))
        assertTrue(md.contains("reasoning here"))
    }

    @Test
    fun summarizesToolCallWhenRawJsonDisabled() {
        val s = session(
            SessionKind.AGENT,
            listOf(turn(assistantBlocks = listOf(
                ContentBlock.ToolCall("id1", "grep_search", "success", "{\"pattern\":\"foo\"}", "3 matches found")
            )))
        )

        val md = MarkdownRenderer.render(s, RenderOptions(includeThinking = true, includeToolJson = false))

        assertTrue(md.contains("grep_search"))
        assertTrue(md.contains("3 matches found"))
        assertFalse(md.contains("```json"))
        assertFalse(md.contains("\"pattern\":\"foo\""))
    }

    @Test
    fun includesFullToolJsonByDefault() {
        val s = session(
            SessionKind.AGENT,
            listOf(turn(assistantBlocks = listOf(
                ContentBlock.ToolCall("id1", "grep_search", "success", "{\"pattern\":\"foo\"}", "3 matches found")
            )))
        )

        val md = MarkdownRenderer.render(s)

        assertTrue(md.contains("```json"))
        assertTrue(md.contains("\"pattern\":\"foo\""))
    }
}
