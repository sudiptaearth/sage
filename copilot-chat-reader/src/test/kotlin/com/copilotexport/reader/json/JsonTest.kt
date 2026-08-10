package com.copilotexport.reader.json

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class JsonTest {

    @Test
    fun parsesPrimitives() {
        assertEquals("hello", JsonValue.parse("\"hello\"").asStringOrNull())
        assertEquals(42.0, JsonValue.parse("42").asDoubleOrNull())
        assertEquals(true, JsonValue.parse("true").asBooleanOrNull())
        assertEquals(JsonValue.JNull, JsonValue.parse("null"))
    }

    @Test
    fun parsesNestedObjectAndArrayPreservingKeyOrder() {
        val text = "{\"b\": 1, \"a\": {\"x\": [1, 2, 3], \"y\": \"z\"}, \"c\": null}"
        val root = JsonValue.parse(text).asObjectOrNull()
        assertNotNull(root)
        val keys = root!!.members.keys.toList()
        assertEquals(listOf("b", "a", "c"), keys)

        val a = root.field("a")?.asObjectOrNull()
        assertNotNull(a)
        assertEquals(listOf("x", "y"), a!!.members.keys.toList())

        val xs = a.field("x")?.asArrayOrNull()
        assertNotNull(xs)
        val nums = xs!!.items.map { it.asDoubleOrNull() }
        assertEquals(listOf(1.0, 2.0, 3.0), nums)
    }

    @Test
    fun unescapesStandardAndUnicodeEscapes() {
        val text = "\"line1\\nline2\\ttab \\\"quoted\\\" \\u0041\""
        assertEquals("line1\nline2\ttab \"quoted\" A", JsonValue.parse(text).asStringOrNull())
    }

    @Test
    fun handlesDoublyJsonEncodedStrings() {
        // Mirrors the real Copilot shape: a JSON string whose value is itself JSON text.
        val outer = "{\"wrapper\": \"{\\\"inner\\\":42}\"}"
        val wrapperStr = JsonValue.parse(outer).stringField("wrapper")
        assertNotNull(wrapperStr)
        val inner = JsonValue.parse(wrapperStr!!)
        assertEquals(42.0, inner.field("inner")?.asDoubleOrNull())
    }

    @Test
    fun prettyPrintRoundTripsThroughParse() {
        val text = "{\"a\":[1,2,{\"b\":\"c\"}],\"d\":true,\"e\":null}"
        val value = JsonValue.parse(text)
        val reparsed = JsonValue.parse(value.toPrettyString())
        assertEquals(value, reparsed)
    }

    @Test
    fun malformedJsonThrows() {
        assertThrows(JsonParseException::class.java) {
            JsonValue.parse("{not valid")
        }
    }

    @Test
    fun parseOrNullReturnsNullInsteadOfThrowing() {
        assertNull(JsonValue.parseOrNull("not json"))
        assertNull(JsonValue.parseOrNull(null))
    }
}
