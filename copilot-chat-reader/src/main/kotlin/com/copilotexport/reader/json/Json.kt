package com.copilotexport.reader.json

import kotlin.math.abs
import kotlin.math.floor

/**
 * Minimal, dependency-free JSON value tree and parser.
 *
 * Written by hand (rather than pulling in Gson/Jackson/kotlinx.serialization)
 * for two reasons:
 *
 *  1. Object key order must be preserved exactly as written in the source
 *     JSON. GitHub Copilot's `contents` field serializes a UUID-keyed graph
 *     of content blocks, and their relative order appears to reflect the
 *     original chronological order the blocks were produced in (JS
 *     objects/Maps are insertion-ordered, and that ordering survives
 *     `JSON.stringify`). Relying on a library whose JsonObject
 *     implementation doesn't guarantee insertion order (some sort by key)
 *     would silently scramble that order.
 *  2. This module may eventually be embedded in an IntelliJ plugin. IntelliJ bundles its own Gson version;
 *     plugins that bundle a different one are a known source of classloader
 *     conflicts. Not depending on any JSON library at all sidesteps that
 *     entirely.
 */
sealed class JsonValue {
    data class JObject(val members: LinkedHashMap<String, JsonValue>) : JsonValue() {
        operator fun get(key: String): JsonValue? = members[key]
        fun has(key: String): Boolean = members.containsKey(key)
    }

    data class JArray(val items: List<JsonValue>) : JsonValue()
    data class JString(val value: String) : JsonValue()
    data class JNumber(val value: Double) : JsonValue()
    data class JBool(val value: Boolean) : JsonValue()
    object JNull : JsonValue()

    fun asStringOrNull(): String? = (this as? JString)?.value
    fun asStringOr(default: String): String = asStringOrNull() ?: default
    fun asObjectOrNull(): JObject? = this as? JObject
    fun asArrayOrNull(): JArray? = this as? JArray
    fun asDoubleOrNull(): Double? = (this as? JNumber)?.value
    fun asLongOrNull(): Long? = (this as? JNumber)?.value?.toLong()
    fun asBooleanOrNull(): Boolean? = (this as? JBool)?.value

    companion object {
        /** Parses [text] as a single JSON document. Throws [JsonParseException] on malformed input. */
        fun parse(text: String): JsonValue = JsonParser(text).parseDocument()

        /** Like [parse], but returns null instead of throwing (useful for "might not be JSON" fields). */
        fun parseOrNull(text: String?): JsonValue? {
            if (text == null) return null
            return try {
                parse(text)
            } catch (e: JsonParseException) {
                null
            }
        }
    }
}

/** Looks up [key] on a JObject; returns null for any other JsonValue type (or a null receiver). */
fun JsonValue?.field(key: String): JsonValue? = (this as? JsonValue.JObject)?.get(key)

/** Shorthand for `field(key)?.asStringOrNull()`. */
fun JsonValue?.stringField(key: String): String? = field(key)?.asStringOrNull()

class JsonParseException(message: String) : RuntimeException(message)

private class JsonParser(private val text: String) {
    private var pos = 0

    fun parseDocument(): JsonValue {
        skipWhitespace()
        val value = parseValue()
        skipWhitespace()
        return value
    }

    private fun parseValue(): JsonValue {
        skipWhitespace()
        if (pos >= text.length) {
            throw JsonParseException("Unexpected end of input at position $pos")
        }
        return when (text[pos]) {
            '{' -> parseObject()
            '[' -> parseArray()
            '"' -> JsonValue.JString(parseString())
            't' -> {
                expectLiteral("true"); JsonValue.JBool(true)
            }
            'f' -> {
                expectLiteral("false"); JsonValue.JBool(false)
            }
            'n' -> {
                expectLiteral("null"); JsonValue.JNull
            }
            else -> parseNumber()
        }
    }

    private fun parseObject(): JsonValue.JObject {
        expect('{')
        val members = LinkedHashMap<String, JsonValue>()
        skipWhitespace()
        if (peek() == '}') {
            pos++
            return JsonValue.JObject(members)
        }
        while (true) {
            skipWhitespace()
            val key = parseString()
            skipWhitespace()
            expect(':')
            val value = parseValue()
            members[key] = value
            skipWhitespace()
            when (peek()) {
                ',' -> {
                    pos++
                }
                '}' -> {
                    pos++
                    break
                }
                else -> throw JsonParseException("Expected ',' or '}' at position $pos")
            }
        }
        return JsonValue.JObject(members)
    }

    private fun parseArray(): JsonValue.JArray {
        expect('[')
        val items = ArrayList<JsonValue>()
        skipWhitespace()
        if (peek() == ']') {
            pos++
            return JsonValue.JArray(items)
        }
        while (true) {
            items.add(parseValue())
            skipWhitespace()
            when (peek()) {
                ',' -> {
                    pos++
                }
                ']' -> {
                    pos++
                    break
                }
                else -> throw JsonParseException("Expected ',' or ']' at position $pos")
            }
        }
        return JsonValue.JArray(items)
    }

    private fun parseString(): String {
        expect('"')
        val sb = StringBuilder()
        while (true) {
            if (pos >= text.length) {
                throw JsonParseException("Unterminated string starting before position $pos")
            }
            val c = text[pos++]
            when {
                c == '"' -> return sb.toString()
                c == '\\' -> {
                    if (pos >= text.length) {
                        throw JsonParseException("Unterminated escape at position $pos")
                    }
                    when (val esc = text[pos++]) {
                        '"' -> sb.append('"')
                        '\\' -> sb.append('\\')
                        '/' -> sb.append('/')
                        'b' -> sb.append('\b')
                        'f' -> sb.append('\u000C')
                        'n' -> sb.append('\n')
                        'r' -> sb.append('\r')
                        't' -> sb.append('\t')
                        'u' -> {
                            if (pos + 4 > text.length) {
                                throw JsonParseException("Truncated unicode escape at position $pos")
                            }
                            val hex = text.substring(pos, pos + 4)
                            pos += 4
                            sb.append(hex.toInt(16).toChar())
                        }
                        else -> throw JsonParseException("Invalid escape '\\$esc' at position $pos")
                    }
                }
                else -> sb.append(c)
            }
        }
    }

    private fun parseNumber(): JsonValue.JNumber {
        val start = pos
        if (peek() == '-') pos++
        while (pos < text.length && text[pos].isDigit()) pos++
        if (pos < text.length && text[pos] == '.') {
            pos++
            while (pos < text.length && text[pos].isDigit()) pos++
        }
        if (pos < text.length && (text[pos] == 'e' || text[pos] == 'E')) {
            pos++
            if (pos < text.length && (text[pos] == '+' || text[pos] == '-')) pos++
            while (pos < text.length && text[pos].isDigit()) pos++
        }
        if (pos == start) {
            throw JsonParseException("Expected a value at position $pos, found '${peek()}'")
        }
        return JsonValue.JNumber(text.substring(start, pos).toDouble())
    }

    private fun expectLiteral(literal: String) {
        if (pos + literal.length > text.length || text.substring(pos, pos + literal.length) != literal) {
            throw JsonParseException("Expected '$literal' at position $pos")
        }
        pos += literal.length
    }

    private fun expect(c: Char) {
        if (pos >= text.length || text[pos] != c) {
            val found = if (pos < text.length) text[pos].toString() else "<eof>"
            throw JsonParseException("Expected '$c' at position $pos, found '$found'")
        }
        pos++
    }

    private fun peek(): Char = if (pos < text.length) text[pos] else '\u0000'

    private fun skipWhitespace() {
        while (pos < text.length && text[pos].isWhitespace()) pos++
    }
}

/** Pretty-prints a JsonValue back to indented JSON text (used by the renderer for tool input/output). */
fun JsonValue.toPrettyString(indent: Int = 0): String {
    val sb = StringBuilder()
    writePretty(this, sb, indent)
    return sb.toString()
}

private fun writePretty(value: JsonValue, sb: StringBuilder, indent: Int) {
    when (value) {
        is JsonValue.JObject -> {
            if (value.members.isEmpty()) {
                sb.append("{}")
                return
            }
            sb.append("{\n")
            val entries = value.members.entries.toList()
            entries.forEachIndexed { i, entry ->
                appendIndent(sb, indent + 1)
                writeJsonStringLiteral(entry.key, sb)
                sb.append(": ")
                writePretty(entry.value, sb, indent + 1)
                if (i < entries.size - 1) sb.append(",")
                sb.append("\n")
            }
            appendIndent(sb, indent)
            sb.append("}")
        }
        is JsonValue.JArray -> {
            if (value.items.isEmpty()) {
                sb.append("[]")
                return
            }
            sb.append("[\n")
            value.items.forEachIndexed { i, v ->
                appendIndent(sb, indent + 1)
                writePretty(v, sb, indent + 1)
                if (i < value.items.size - 1) sb.append(",")
                sb.append("\n")
            }
            appendIndent(sb, indent)
            sb.append("]")
        }
        is JsonValue.JString -> writeJsonStringLiteral(value.value, sb)
        is JsonValue.JNumber -> {
            val d = value.value
            if (d == floor(d) && !d.isInfinite() && abs(d) < 1e15) {
                sb.append(d.toLong())
            } else {
                sb.append(d)
            }
        }
        is JsonValue.JBool -> sb.append(value.value)
        JsonValue.JNull -> sb.append("null")
    }
}

private fun appendIndent(sb: StringBuilder, level: Int) {
    repeat(level) { sb.append("  ") }
}

private fun writeJsonStringLiteral(s: String, sb: StringBuilder) {
    sb.append('"')
    for (c in s) {
        when (c) {
            '"' -> sb.append("\\\"")
            '\\' -> sb.append("\\\\")
            '\n' -> sb.append("\\n")
            '\r' -> sb.append("\\r")
            '\t' -> sb.append("\\t")
            else -> if (c.code < 0x20) sb.append("\\u%04x".format(c.code)) else sb.append(c)
        }
    }
    sb.append('"')
}
