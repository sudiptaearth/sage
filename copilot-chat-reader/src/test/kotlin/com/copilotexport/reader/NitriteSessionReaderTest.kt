package com.copilotexport.reader

import com.copilotexport.reader.model.ContentBlock
import com.copilotexport.reader.model.Role
import com.copilotexport.reader.model.SessionKind
import org.dizitart.no2.Nitrite
import org.dizitart.no2.collection.Document
import org.dizitart.no2.mvstore.MVStoreModule
import org.dizitart.no2.repository.EntityDecorator
import org.dizitart.no2.repository.EntityId
import org.dizitart.no2.repository.EntityIndex
import org.dizitart.no2.repository.ObjectRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

/**
 * Builds tiny synthetic Nitrite databases -- fabricated, non-sensitive
 * documents shaped like the real on-disk schema -- rather than checking in any
 * real captured .db file. This avoids ever needing to "scrub" real user
 * data and keeps these tests fully deterministic and safe to commit.
 *
 * Each test writes through the *same* Nitrite EntityDecorator technique
 * NitriteSessionReader reads with, closes the database, then reopens it
 * fresh read-only via NitriteSessionReader.read() -- a real round-trip
 * through MVStore's on-disk encoding, not just in-memory object reuse.
 */
class NitriteSessionReaderTest {

    private class TestEntityDecorator(private val name: String) : EntityDecorator<Document> {
        override fun getEntityType(): Class<Document> = Document::class.java
        override fun getIdField(): EntityId? = null
        override fun getIndexFields(): List<EntityIndex> = emptyList()
        override fun getEntityName(): String = name
    }

    private fun writeDb(dbFile: Path, docsByEntity: Map<String, List<Document>>) {
        val storeModule = MVStoreModule.withConfig()
            .filePath(dbFile.toFile())
            .build()
        val db = Nitrite.builder().loadModule(storeModule).openOrCreate()
        try {
            for ((entityName, docs) in docsByEntity) {
                val repo: ObjectRepository<Document> = db.getRepository(TestEntityDecorator(entityName))
                val collection = repo.getDocumentCollection()
                for (doc in docs) {
                    collection.insert(doc)
                }
            }
        } finally {
            db.close()
        }
    }

    private fun jsonEscape(s: String): String {
        val sb = StringBuilder()
        sb.append('"')
        for (c in s) {
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                else -> sb.append(c)
            }
        }
        sb.append('"')
        return sb.toString()
    }

    @Test
    fun readsPlainChatSessionTurns(@TempDir tempDir: Path) {
        val dbFile = tempDir.resolve("copilot-chat-nitrite.db")

        val sessionDoc = Document.createDocument().put("id", "sess-1")
        val turnDoc = Document.createDocument()
            .put("id", "turn-1")
            .put("sessionId", "sess-1")
            .put("createdAt", 1000L)
            .put(
                "request",
                Document.createDocument()
                    .put("content", "How do I center a div?")
                    .put("references", emptyList<Any>())
            )
            .put(
                "response",
                Document.createDocument()
                    .put("content", "Use flexbox.")
                    .put("annotations", emptyList<Any>())
            )
            .put("rating", 0)

        writeDb(
            dbFile,
            mapOf(
                "com.github.copilot.chat.session.persistence.nitrite.entity.NtChatSession" to listOf(sessionDoc),
                "com.github.copilot.chat.session.persistence.nitrite.entity.NtTurn" to listOf(turnDoc)
            )
        )

        val sessions = NitriteSessionReader.read(dbFile, SessionKind.CHAT)

        assertEquals(1, sessions.size)
        val session = sessions[0]
        assertEquals("sess-1", session.id)
        assertEquals(SessionKind.CHAT, session.kind)
        assertEquals(1, session.turns.size)
        val turn = session.turns[0]
        assertEquals("turn-1", turn.id)
        assertEquals(null, turn.chatMode)
        assertEquals(Role.USER, turn.user.role)
        assertEquals("How do I center a div?", turn.user.rawText)
        assertTrue(turn.user.blocks.isEmpty())
        assertEquals("Use flexbox.", turn.assistant.rawText)
    }

    @Test
    fun readsAgentSessionTurnsWithDecodedContent(@TempDir tempDir: Path) {
        val dbFile = tempDir.resolve("copilot-agent-sessions-nitrite.db")

        val markdownBlock = "{\"type\":\"Markdown\",\"data\":" +
            jsonEscape("{\"text\":\"Fix the bug\",\"annotations\":[]}") + "}"
        val requestContents = "{\"k1\":{\"type\":\"Value\",\"value\":" + jsonEscape(markdownBlock) + "}}"

        val agentRoundBlock = "{\"type\":\"AgentRound\",\"data\":" +
            jsonEscape("{\"roundId\":1,\"reply\":\"Found it.\",\"toolCalls\":[]}") + "}"
        val responseContents = "{\"k1\":{\"type\":\"Value\",\"value\":" + jsonEscape(agentRoundBlock) + "}}"

        val sessionDoc = Document.createDocument().put("id", "sess-agent-1")
        val turnDoc = Document.createDocument()
            .put("id", "turn-agent-1")
            .put("sessionId", "sess-agent-1")
            .put("createdAt", 2000L)
            .put(
                "request",
                Document.createDocument()
                    .put("stringContent", "Fix the bug")
                    .put("contents", requestContents)
                    .put("chatMode", "Agent")
                    .put("model", "gpt-4.1")
            )
            .put(
                "response",
                Document.createDocument()
                    .put("stringContent", "")
                    .put("contents", responseContents)
                    .put("chatMode", "Agent")
            )
            .put("rating", 0)

        writeDb(
            dbFile,
            mapOf(
                "com.github.copilot.agent.session.persistence.nitrite.entity.NtAgentSession" to listOf(sessionDoc),
                "com.github.copilot.agent.session.persistence.nitrite.entity.NtAgentTurn" to listOf(turnDoc)
            )
        )

        val sessions = NitriteSessionReader.read(dbFile, SessionKind.AGENT)

        assertEquals(1, sessions.size)
        val session = sessions[0]
        assertEquals("sess-agent-1", session.id)
        assertEquals(SessionKind.AGENT, session.kind)
        val turn = session.turns[0]
        assertEquals("Agent", turn.chatMode)
        assertEquals("gpt-4.1", turn.model)
        assertEquals("Fix the bug", turn.user.rawText)
        assertEquals(1, turn.user.blocks.size)
        assertEquals(ContentBlock.Text("Fix the bug"), turn.user.blocks[0])
        assertEquals(1, turn.assistant.blocks.size)
        assertEquals(ContentBlock.Text("Found it."), turn.assistant.blocks[0])
    }

    @Test
    fun handlesLockedDatabaseGracefully(@TempDir tempDir: Path) {
        val dbFile = tempDir.resolve("copilot-chat-nitrite.db")

        val sessionDoc = Document.createDocument().put("id", "sess-lock-1")
        val turnDoc = Document.createDocument()
            .put("id", "turn-lock-1")
            .put("sessionId", "sess-lock-1")
            .put("createdAt", 1000L)
            .put(
                "request",
                Document.createDocument().put("content", "Hello").put("references", emptyList<Any>())
            )
            .put(
                "response",
                Document.createDocument().put("content", "Hi").put("annotations", emptyList<Any>())
            )
            .put("rating", 0)

        writeDb(
            dbFile,
            mapOf(
                "com.github.copilot.chat.session.persistence.nitrite.entity.NtChatSession" to listOf(sessionDoc),
                "com.github.copilot.chat.session.persistence.nitrite.entity.NtTurn" to listOf(turnDoc)
            )
        )

        // Open the same file read-write and keep it open, to simulate the IDE's own live
        // handle on the store -- this is what actually produces the "already opened in other
        // process" error in practice. Confirmed empirically: on Windows this is a whole-file
        // OS lock that blocks not just re-opening the store directly but even a plain file
        // copy of it (see the doc comments on NitriteSessionReader.read()/copyWithRetries()).
        // So both outcomes below are acceptable here: a successful read via the temp-copy
        // fallback (if the platform/timing allows the copy through), or a clear, actionable
        // failure mentioning the lock (if it doesn't). What's NOT acceptable is a raw,
        // unexplained OS exception leaking through to the caller.
        val lockingStoreModule = MVStoreModule.withConfig().filePath(dbFile.toFile()).build()
        val lockingDb = Nitrite.builder().loadModule(lockingStoreModule).openOrCreate()
        try {
            try {
                val sessions = NitriteSessionReader.read(dbFile, SessionKind.CHAT)
                assertEquals(1, sessions.size)
                assertEquals("sess-lock-1", sessions[0].id)
                assertEquals("Hello", sessions[0].turns[0].user.rawText)
                // sourceDbPath must reflect the ORIGINAL path, never the temp copy used internally.
                assertEquals(dbFile.toAbsolutePath().toString(), sessions[0].sourceDbPath)
            } catch (e: Exception) {
                val message = (e.message ?: "") + (e.cause?.message ?: "")
                assertTrue(
                    message.contains("lock", ignoreCase = true),
                    "Expected a clear message mentioning the lock, got: $message"
                )
            }
        } finally {
            lockingDb.close()
        }
    }

    @Test
    fun throwsSchemaMismatchWhenExpectedEntitiesAreAbsent(@TempDir tempDir: Path) {
        val dbFile = tempDir.resolve("copilot-chat-nitrite.db")

        // Write a store containing some *other* entity (simulating a future/unknown
        // Copilot plugin version whose internal schema doesn't match anything this
        // reader looks for), but none of NtChatSession/NtTurn.
        writeDb(
            dbFile,
            mapOf("com.github.copilot.some.future.Entity" to listOf(Document.createDocument().put("x", 1)))
        )

        val exception = org.junit.jupiter.api.Assertions.assertThrows(SchemaMismatchException::class.java) {
            NitriteSessionReader.read(dbFile, SessionKind.CHAT)
        }
        assertTrue(exception.message?.contains("NtChatSession") == true)
    }

    @Test
    fun returnsEmptyListForLegitimatelyEmptyStore(@TempDir tempDir: Path) {
        val dbFile = tempDir.resolve("copilot-chat-nitrite.db")

        // The expected entities exist (so this is NOT a schema mismatch), they just
        // have zero documents in them -- e.g. right after Copilot creates the store
        // but before any chat happens.
        writeDb(
            dbFile,
            mapOf(
                "com.github.copilot.chat.session.persistence.nitrite.entity.NtChatSession" to emptyList(),
                "com.github.copilot.chat.session.persistence.nitrite.entity.NtTurn" to emptyList()
            )
        )

        val sessions = NitriteSessionReader.read(dbFile, SessionKind.CHAT)

        assertEquals(0, sessions.size)
    }
}
