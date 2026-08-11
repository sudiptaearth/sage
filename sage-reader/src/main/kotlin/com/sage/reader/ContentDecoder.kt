package com.sage.reader

import com.sage.reader.json.JsonValue
import com.sage.reader.json.field
import com.sage.reader.json.stringField
import com.sage.reader.json.toPrettyString
import com.sage.reader.model.ContentBlock

/**
 * Decodes a turn side's `contents` field -- itself a JSON *string* -- into a
 * flat, ordered list of [ContentBlock]s.
 *
 * The raw shape is a
 * UUID-keyed object where each entry is `{"type":"Value","value":"<json>"}`
 * or `{"type":"Subgraph","value":"<json>"}`; `value` (and every block's
 * `data`) is itself a JSON-encoded *string*, nested several levels deep.
 * This walks that generically -- Subgraph entries are unwrapped and their
 * inner entries spliced in at that position -- rather than assuming a fixed
 * unwrap depth or a fixed position for Thinking/AgentRound blocks. The exact
 * nesting has already changed shape once between plain-Ask and Agent-mode
 * turns and is entirely undocumented, so treating "how deep" as fixed would
 * be fragile.
 *
 * Block order is trusted to follow the source JSON object's key insertion
 * order (see the rationale in json/Json.kt). AgentRound blocks additionally
 * carry a `roundId` in the raw data if that assumption ever needs a fallback
 * sort key, but it isn't used here to keep this a first pass.
 */
object ContentDecoder {

    fun decode(contentsJson: String?): List<ContentBlock> {
        if (contentsJson.isNullOrBlank()) return emptyList()
        val root = JsonValue.parseOrNull(contentsJson)?.asObjectOrNull() ?: return emptyList()
        return decodeEntries(root)
    }

    private fun decodeEntries(obj: JsonValue.JObject): List<ContentBlock> {
        val blocks = ArrayList<ContentBlock>()
        for ((_, entry) in obj.members) {
            val entryObj = entry.asObjectOrNull() ?: continue
            val entryType = entryObj.stringField("type") ?: continue
            val valueStr = entryObj.stringField("value") ?: continue
            when (entryType) {
                "Subgraph" -> {
                    val inner = JsonValue.parseOrNull(valueStr)?.asObjectOrNull()
                    if (inner != null) {
                        blocks.addAll(decodeEntries(inner))
                    }
                }
                "Value" -> {
                    val wrapper = JsonValue.parseOrNull(valueStr)?.asObjectOrNull() ?: continue
                    val blockType = wrapper.stringField("type") ?: continue
                    val dataStr = wrapper.stringField("data")
                    blocks.addAll(decodeBlock(blockType, dataStr))
                }
                else -> {
                    // Unknown wrapper kind (not Subgraph or Value). Skip rather than crash --
                    // this is an undocumented schema that can add new wrapper kinds at any time.
                }
            }
        }
        return blocks
    }

    private fun decodeBlock(blockType: String, dataStr: String?): List<ContentBlock> {
        return when (blockType) {
            "Markdown" -> decodeMarkdown(dataStr)
            "FixedContextPanel" -> decodeFixedContextPanel(dataStr)
            "References" -> decodeReferencesArrayString(dataStr)
            "Hide" -> decodeHide(dataStr)
            "AgentRound" -> decodeAgentRound(dataStr)
            "Thinking" -> decodeThinking(dataStr)
            else -> decodeUnknown(blockType, dataStr)
        }
    }

    private fun decodeMarkdown(dataStr: String?): List<ContentBlock> {
        val data = JsonValue.parseOrNull(dataStr)?.asObjectOrNull() ?: return emptyList()
        val text = data.stringField("text") ?: return emptyList()
        if (text.isBlank()) return emptyList()
        return listOf(ContentBlock.Text(text))
    }

    private fun decodeFixedContextPanel(dataStr: String?): List<ContentBlock> {
        val data = JsonValue.parseOrNull(dataStr)?.asObjectOrNull() ?: return emptyList()
        val refsArray = data.field("references")?.asArrayOrNull() ?: return emptyList()
        return refsArray.items.mapNotNull { refItem -> decodeFileRefString(refItem) }
    }

    /**
     * `references[]` entries are themselves JSON-encoded strings, e.g.
     * `"{\"type\":\"file\",\"reference\":{\"uri\":...}}"` -- one more parse
     * level than the array itself.
     */
    private fun decodeFileRefString(refItem: JsonValue): ContentBlock.Context? {
        val refStr = refItem.asStringOrNull() ?: return null
        val refObj = JsonValue.parseOrNull(refStr)?.asObjectOrNull() ?: return null
        val uri = refObj.field("reference").stringField("uri") ?: return null
        return ContentBlock.Context(uri = uri, snapshot = null)
    }

    /** Top-level "References" block: `data` is a JSON-encoded array string, e.g. "[]" or a list of file-ref strings. */
    private fun decodeReferencesArrayString(dataStr: String?): List<ContentBlock> {
        val arr = JsonValue.parseOrNull(dataStr)?.asArrayOrNull() ?: return emptyList()
        return arr.items.mapNotNull { decodeFileRefString(it) }
    }

    private fun decodeHide(dataStr: String?): List<ContentBlock> {
        val data = JsonValue.parseOrNull(dataStr)?.asObjectOrNull() ?: return emptyList()
        val innerType = data.stringField("type") ?: return emptyList()
        val innerDataStr = data.stringField("data")
        return when (innerType) {
            "References" -> decodeReferencesArrayString(innerDataStr)
            "WorkingSet" -> decodeWorkingSet(innerDataStr)
            else -> emptyList()
        }
    }

    /** WorkingSet entries are real objects (not further string-encoded): `[{"file":..,"originalContent":..}]`. */
    private fun decodeWorkingSet(dataStr: String?): List<ContentBlock> {
        val arr = JsonValue.parseOrNull(dataStr)?.asArrayOrNull() ?: return emptyList()
        return arr.items.mapNotNull { item ->
            val obj = item.asObjectOrNull() ?: return@mapNotNull null
            val file = obj.stringField("file") ?: return@mapNotNull null
            val originalContent = obj.stringField("originalContent")
            ContentBlock.Context(uri = file, snapshot = originalContent)
        }
    }

    private fun decodeAgentRound(dataStr: String?): List<ContentBlock> {
        val data = JsonValue.parseOrNull(dataStr)?.asObjectOrNull() ?: return emptyList()
        val blocks = ArrayList<ContentBlock>()
        val reply = data.stringField("reply")
        if (!reply.isNullOrBlank()) {
            blocks.add(ContentBlock.Text(reply))
        }
        val toolCalls = data.field("toolCalls")?.asArrayOrNull()
        if (toolCalls != null) {
            for (tc in toolCalls.items) {
                val tcObj = tc.asObjectOrNull() ?: continue
                blocks.add(decodeToolCall(tcObj))
            }
        }
        return blocks
    }

    private fun decodeToolCall(tcObj: JsonValue.JObject): ContentBlock.ToolCall {
        val id = tcObj.stringField("id") ?: ""
        val name = tcObj.stringField("name") ?: "unknown_tool"
        val status = tcObj.stringField("status") ?: "unknown"
        val input = tcObj.field("input")
        val inputJson = input?.toPrettyString() ?: "{}"
        val resultArr = tcObj.field("result")?.asArrayOrNull()
        val output = resultArr?.items
            ?.mapNotNull { r ->
                val rObj = r.asObjectOrNull()
                val rType = rObj.stringField("type")
                val rValue = rObj.stringField("value")
                when {
                    rValue == null -> null
                    rType == null || rType == "text" -> rValue
                    else -> "[$rType]\n$rValue"
                }
            }
            ?.joinToString("\n\n")
            ?: ""
        return ContentBlock.ToolCall(id = id, name = name, status = status, inputJson = inputJson, output = output)
    }

    private fun decodeThinking(dataStr: String?): List<ContentBlock> {
        val data = JsonValue.parseOrNull(dataStr)?.asObjectOrNull() ?: return emptyList()
        val id = data.stringField("id") ?: ""
        val content = data.stringField("content") ?: return emptyList()
        if (content.isBlank()) return emptyList()
        return listOf(ContentBlock.Thinking(id = id, content = content))
    }

    private fun decodeUnknown(blockType: String, dataStr: String?): List<ContentBlock> {
        val pretty = JsonValue.parseOrNull(dataStr)?.toPrettyString() ?: dataStr ?: ""
        return listOf(ContentBlock.Text("[unrecognized content block type: $blockType]\n$pretty"))
    }
}
