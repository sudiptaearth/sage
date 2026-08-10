package com.copilotexport.reader

import com.copilotexport.reader.model.ContentBlock
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * These fixtures are all synthetic -- built programmatically from small raw
 * JSON strings, escaped at test-run time via [jsonEscape] rather than
 * hand-typed as backslash-multiplied literals. Copilot's `contents` field
 * nests JSON-encoded strings inside JSON-encoded strings several levels
 * deep; hand-counting backslashes for that many
 * levels is exactly the kind of thing that's easy to get subtly wrong, so
 * each level here is escaped by code instead.
 */
class ContentDecoderTest {

    private fun jsonEscape(s: String): String {
        val sb = StringBuilder()
        sb.append('"')
        for (c in s) {
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> sb.append(c)
            }
        }
        sb.append('"')
        return sb.toString()
    }

    /** The two-level envelope every content-graph entry uses: `{"type":"Value","value":"<escaped block json>"}`. */
    private fun valueEntry(blockJson: String): String =
        "{\"type\":\"Value\",\"value\":${jsonEscape(blockJson)}}"

    private fun blockJson(type: String, dataJson: String): String =
        "{\"type\":\"$type\",\"data\":${jsonEscape(dataJson)}}"

    private fun contentsOf(vararg entries: Pair<String, String>): String {
        val members = entries.joinToString(",") { (key, entryJson) -> "\"$key\":$entryJson" }
        return "{$members}"
    }

    @Test
    fun decodesMarkdownBlock() {
        val data = "{\"text\":\"Hello world\",\"annotations\":[]}"
        val contents = contentsOf("k1" to valueEntry(blockJson("Markdown", data)))

        val blocks = ContentDecoder.decode(contents)

        assertEquals(1, blocks.size)
        assertEquals(ContentBlock.Text("Hello world"), blocks[0])
    }

    @Test
    fun decodesFixedContextPanelReferences() {
        val fileRef = "{\"type\":\"file\",\"reference\":{\"uri\":\"file:///test.js\"}}"
        val data = "{\"references\":[${jsonEscape(fileRef)}],\"currentFileUri\":\"file:///test.js\"}"
        val contents = contentsOf("k1" to valueEntry(blockJson("FixedContextPanel", data)))

        val blocks = ContentDecoder.decode(contents)

        assertEquals(1, blocks.size)
        assertEquals(ContentBlock.Context("file:///test.js", null), blocks[0])
    }

    @Test
    fun decodesHideWrappedWorkingSetWithFileSnapshot() {
        val workingSetData = "[{\"file\":\"file:///test.js\",\"originalContent\":\"const x = 1;\"}]"
        val hideData = "{\"type\":\"WorkingSet\",\"data\":${jsonEscape(workingSetData)}}"
        val contents = contentsOf("k1" to valueEntry(blockJson("Hide", hideData)))

        val blocks = ContentDecoder.decode(contents)

        assertEquals(1, blocks.size)
        assertEquals(ContentBlock.Context("file:///test.js", "const x = 1;"), blocks[0])
    }

    @Test
    fun decodesAgentRoundWithReplyAndToolCall() {
        val toolCall = "{\"id\":\"call_1\",\"name\":\"grep_search\",\"status\":\"completed\"," +
            "\"input\":{\"query\":\"foo\"},\"result\":[{\"type\":\"text\",\"value\":\"1 match\"}]}"
        val agentRoundData = "{\"roundId\":1,\"reply\":\"Let me search.\",\"toolCalls\":[$toolCall]}"
        val contents = contentsOf("k1" to valueEntry(blockJson("AgentRound", agentRoundData)))

        val blocks = ContentDecoder.decode(contents)

        assertEquals(2, blocks.size)
        assertEquals(ContentBlock.Text("Let me search."), blocks[0])
        val tool = blocks[1] as ContentBlock.ToolCall
        assertEquals("call_1", tool.id)
        assertEquals("grep_search", tool.name)
        assertEquals("completed", tool.status)
        assertEquals("1 match", tool.output)
        assertTrue(tool.inputJson.contains("\"query\""))
        assertTrue(tool.inputJson.contains("foo"))
    }

    @Test
    fun decodesThinkingBlock() {
        val data = "{\"id\":\"thinking_0\",\"content\":\"Reasoning about the problem.\"}"
        val contents = contentsOf("k1" to valueEntry(blockJson("Thinking", data)))

        val blocks = ContentDecoder.decode(contents)

        assertEquals(1, blocks.size)
        assertEquals(ContentBlock.Thinking("thinking_0", "Reasoning about the problem."), blocks[0])
    }

    @Test
    fun unwrapsSubgraphWrapperAndPreservesOrder() {
        val thinkingData = "{\"id\":\"thinking_0\",\"content\":\"Thinking first.\"}"
        val agentRoundData = "{\"roundId\":1,\"reply\":\"Then replying.\",\"toolCalls\":[]}"
        val innerFlatMap = contentsOf(
            "t1" to valueEntry(blockJson("Thinking", thinkingData)),
            "r1" to valueEntry(blockJson("AgentRound", agentRoundData))
        )
        val subgraphEntry = "{\"type\":\"Subgraph\",\"value\":${jsonEscape(innerFlatMap)}}"
        val contents = "{\"__first__\":$subgraphEntry}"

        val blocks = ContentDecoder.decode(contents)

        assertEquals(2, blocks.size)
        assertEquals(ContentBlock.Thinking("thinking_0", "Thinking first."), blocks[0])
        assertEquals(ContentBlock.Text("Then replying."), blocks[1])
    }

    @Test
    fun emptyOrMissingContentsDecodesToEmptyList() {
        assertEquals(emptyList<ContentBlock>(), ContentDecoder.decode(null))
        assertEquals(emptyList<ContentBlock>(), ContentDecoder.decode(""))
        assertEquals(emptyList<ContentBlock>(), ContentDecoder.decode("not json"))
    }

    @Test
    fun unrecognizedBlockTypeDegradesToTextInsteadOfCrashing() {
        val contents = contentsOf("k1" to valueEntry(blockJson("SomeFutureBlockType", "{\"foo\":\"bar\"}")))

        val blocks = ContentDecoder.decode(contents)

        assertEquals(1, blocks.size)
        val text = blocks[0] as ContentBlock.Text
        assertTrue(text.text.contains("SomeFutureBlockType"))
    }
}
