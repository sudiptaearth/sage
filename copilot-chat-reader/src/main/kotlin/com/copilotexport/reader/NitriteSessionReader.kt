package com.copilotexport.reader

import com.copilotexport.reader.json.JsonValue
import com.copilotexport.reader.json.field
import com.copilotexport.reader.json.stringField
import com.copilotexport.reader.json.toPrettyString
import com.copilotexport.reader.model.ChatSession
import com.copilotexport.reader.model.Role
import com.copilotexport.reader.model.SessionKind
import com.copilotexport.reader.model.Turn
import com.copilotexport.reader.model.TurnSide
import org.dizitart.no2.Nitrite
import org.dizitart.no2.collection.Document
import org.dizitart.no2.collection.NitriteCollection
import org.dizitart.no2.exceptions.NitriteIOException
import org.dizitart.no2.mvstore.MVStoreModule
import org.dizitart.no2.repository.EntityDecorator
import org.dizitart.no2.repository.EntityId
import org.dizitart.no2.repository.EntityIndex
import org.dizitart.no2.repository.ObjectRepository
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/**
 * Opens a GitHub Copilot .db file read-only and maps its documents into the
 * domain model in model/DomainModel.kt.
 *
 * Uses the same Nitrite EntityDecorator technique used elsewhere in this
 * project: Copilot stores its data via Nitrite's typed ObjectRepository API (not
 * plain named collections), and the real GitHub-internal entity classes
 * aren't available to a third-party reader. EntityDecorator lets us open a
 * repository by name only and fall back to raw Document access via
 * getDocumentCollection() -- see that file's comments for the full
 * "A repository with same name already exists" / "No such field '_id'"
 * story that led here. getIdField() must return null for the same reason it
 * does there: declaring one makes Nitrite reflectively look for a matching
 * Java *field* on getEntityType(), which is Document.class (an interface),
 * and that lookup throws.
 */
/**
 * Thrown by [NitriteSessionReader] when a .db file's schema doesn't match
 * any entity shape this reader knows about (Phase 5: detect schema
 * shape/version drift at read time instead of silently returning an empty
 * session list, which would be indistinguishable from a legitimately empty
 * store). A dedicated exception type lets callers (e.g. the IntelliJ
 * plugin action) show a specific "unsupported Copilot version" message
 * rather than a generic I/O error.
 */
class SchemaMismatchException(message: String) : IOException(message)

object NitriteSessionReader {

    private const val NT_CHAT_SESSION = "com.github.copilot.chat.session.persistence.nitrite.entity.NtChatSession"
    private const val NT_TURN = "com.github.copilot.chat.session.persistence.nitrite.entity.NtTurn"
    private const val NT_AGENT_SESSION = "com.github.copilot.agent.session.persistence.nitrite.entity.NtAgentSession"
    private const val NT_AGENT_TURN = "com.github.copilot.agent.session.persistence.nitrite.entity.NtAgentTurn"
    private const val NT_EDIT_SESSION = "com.github.copilot.agent.edit.session.persistence.nitrite.entity.NtEditSession"
    private const val COPY_RETRY_ATTEMPTS = 3
    private const val COPY_RETRY_DELAY_MS = 150L

    /**
     * Reads every session found in [dbFile], classified by [kind]. Opens
     * read-only; never writes.
     *
     * GitHub Copilot's own plugin (in whichever IDE process wrote this file)
     * usually has it open read-write, and H2's MVStore takes an OS-level
     * exclusive lock the moment a process opens a store that way -- a lock
     * that blocks *any* other process from opening the same file at all,
     * even read-only. If that happens (`NitriteIOException` mentioning
     * "already opened"), this falls back to copying the file to a temp
     * location and reading the copy instead. The returned
     * [ChatSession.sourceDbPath] always reflects the *original* path, never
     * the temp copy, so callers don't need to know this happened. The temp
     * copy is deleted again as soon as the read finishes.
     *
     * IMPORTANT, learned the hard way (see [copyWithRetries]): on Windows
     * this exclusive lock is enforced by the OS for the *whole file*, not
     * just for `openOrCreate()` calls -- it also blocks a plain file copy of
     * the locked file, for as long as the other process keeps it open. So
     * this fallback only actually helps for brief/transient locks; for a
     * continuously-open session (e.g. the IDE has the project open right
     * now) it cannot succeed, no matter how the copy is attempted, and
     * `read()` will throw a clear `IOException` explaining that instead of
     * silently pretending to have worked. There is no way to bypass this
     * from user-space Java without something like a Windows Volume Shadow
     * Copy snapshot, which needs elevated privileges and isn't implemented
     * here.
     */
    fun read(dbFile: Path, kind: SessionKind): List<ChatSession> {
        return try {
            openAndRead(openPath = dbFile, displayPath = dbFile, kind = kind)
        } catch (e: NitriteIOException) {
            if (!isLockedByOtherProcess(e)) {
                throw e
            }
            System.err.println(
                "Note: '$dbFile' is locked by another process (likely an IDE with GitHub Copilot " +
                    "open) -- reading from a temporary copy instead."
            )
            readViaTempCopy(dbFile, kind)
        }
    }

    private fun isLockedByOtherProcess(e: NitriteIOException): Boolean =
        e.message?.contains("already opened", ignoreCase = true) == true

    /**
     * Returns the first user prompt's plain text for [dbFile], or null if the
     * session has no turns / can't be read. Used by callers (e.g. the plugin's
     * session picker) that want a human-readable preview without rendering
     * the whole session -- note this still does the same full [read] under
     * the hood, so callers should cache the result rather than call this
     * repeatedly for the same file.
     */
    fun firstPrompt(dbFile: Path, kind: SessionKind): String? =
        try {
            read(dbFile, kind).firstOrNull()?.turns?.firstOrNull()?.user?.rawText
                ?.trim()?.takeIf { it.isNotEmpty() }
        } catch (e: Exception) {
            null
        }

    private fun readViaTempCopy(dbFile: Path, kind: SessionKind): List<ChatSession> {
        val tempDir = Files.createTempDirectory("copilot-chat-reader-")
        val tempCopy = tempDir.resolve(dbFile.fileName)
        try {
            copyWithRetries(dbFile, tempCopy)
            return openAndRead(openPath = tempCopy, displayPath = dbFile, kind = kind)
        } finally {
            deleteQuietly(tempCopy)
            deleteQuietly(tempDir)
        }
    }

    /**
     * Retries the copy a few times with a short delay -- a cheap safety net
     * in case the lock is only held briefly -- then gives up with a clear,
     * actionable message. Confirmed empirically: on Windows, `Files.copy()`
     * fails with `FileSystemException: ... another process has locked a
     * portion of the file` when the source is held open read-write
     * elsewhere, exactly like [NitriteIOException] does for a direct open.
     * Retries won't rescue a *continuously*-open session (the lock is held
     * for the other process's entire lifetime, not released between
     * operations) -- they only help if the lock happens to be transient.
     */
    private fun copyWithRetries(source: Path, target: Path) {
        var lastError: IOException? = null
        repeat(COPY_RETRY_ATTEMPTS) { attempt ->
            try {
                Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING)
                return
            } catch (e: IOException) {
                lastError = e
                if (attempt < COPY_RETRY_ATTEMPTS - 1) {
                    Thread.sleep(COPY_RETRY_DELAY_MS)
                }
            }
        }
        throw IOException(
            "Could not read '$source': it is exclusively locked by another process (most likely " +
                "an IDE with GitHub Copilot actively running), and that lock also blocks copying " +
                "the file, not just opening it directly. This can't be bypassed from here -- close " +
                "the IDE/project using this session (or wait until it's no longer active) and try " +
                "again.",
            lastError
        )
    }

    private fun deleteQuietly(path: Path) {
        try {
            Files.deleteIfExists(path)
        } catch (e: IOException) {
            // Best-effort cleanup only -- a leftover temp file/dir isn't worth failing the read over.
        }
    }

    /**
     * Builds the exception thrown when none of a session kind's expected
     * entity types exist in the store at all -- i.e., this .db was written
     * by a GitHub Copilot plugin version whose internal schema doesn't
     * match any shape this reader knows about (see SchemaMismatchException
     * doc). Distinct from a store that legitimately has zero sessions,
     * which returns an empty list instead of throwing.
     */
    private fun schemaMismatch(dbFile: Path, vararg expectedEntityNames: String): SchemaMismatchException =
        SchemaMismatchException(
            "'$dbFile' doesn't contain any of the expected entity types " +
                "(${expectedEntityNames.joinToString(", ")}). This usually means the installed " +
                "GitHub Copilot plugin version uses a different internal schema than this reader " +
                "supports yet -- inspect the raw .db file to find the new shape."
        )

    /** Opens Nitrite against [openPath] but labels the resulting sessions with [displayPath]. */
    private fun openAndRead(openPath: Path, displayPath: Path, kind: SessionKind): List<ChatSession> {
        val storeModule = MVStoreModule.withConfig()
            .filePath(openPath.toFile())
            .readOnly(true)
            .build()

        val db = Nitrite.builder().loadModule(storeModule).openOrCreate()
        val result = try {
            when (kind) {
                SessionKind.CHAT -> readChatSessions(db, displayPath)
                SessionKind.AGENT -> readAgentSessions(db, displayPath)
                SessionKind.EDIT -> readEditSessionsRaw(db, displayPath)
            }
        } finally {
            // Confirmed in practice: MVStore's close() path can itself throw
            // ("Error occurred while committing the database"), apparently
            // trying to commit/flush even though the store was opened
            // readOnly(true). If db.close() threw directly from a `finally`
            // block here, that exception would *replace* a return value the
            // try block already produced -- silently discarding a read that
            // had actually succeeded. Swallowing close() failures (with a
            // note, not silence) instead of letting them propagate avoids
            // that: we already have everything we need out of `db` by the
            // time we're closing it.
            closeQuietly(db, displayPath)
        }
        return result
    }

    private fun closeQuietly(db: Nitrite, displayPath: Path) {
        try {
            db.close()
        } catch (e: Exception) {
            System.err.println(
                "Note: '$displayPath' read successfully, but closing the database handle " +
                    "afterwards threw (harmless for a read-only open): $e"
            )
        }
    }

    private fun openDocs(db: Nitrite, entityName: String): NitriteCollection? {
        return try {
            val repo: ObjectRepository<Document> = db.getRepository(NamedEntityDecorator(entityName))
            repo.getDocumentCollection()
        } catch (e: Exception) {
            null
        }
    }

    /**
     * True if [db] has *any* repository/collection matching one of
     * [entityNames] -- used to distinguish "this store is legitimately
     * empty" from "this store's schema doesn't match any entity name we
     * know about" (Phase 5: detect schema shape / version drift at read
     * time, rather than silently returning an empty session list either
     * way). [Nitrite.hasRepository] only checks the repository was ever
     * created with data in it; entities that exist but have zero documents
     * still count here, which is the right call -- a real (if empty)
     * matching schema is not a version mismatch.
     */
    private fun anyKnownEntityExists(db: Nitrite, vararg entityNames: String): Boolean =
        entityNames.any { name -> db.hasRepository(NamedEntityDecorator(name)) }

    private fun readChatSessions(db: Nitrite, dbFile: Path): List<ChatSession> {
        if (!anyKnownEntityExists(db, NT_CHAT_SESSION, NT_TURN)) {
            throw schemaMismatch(dbFile, NT_CHAT_SESSION, NT_TURN)
        }
        val sessionIds = LinkedHashSet<String>()
        openDocs(db, NT_CHAT_SESSION)?.find()?.forEach { doc ->
            documentToJsonValue(doc).stringField("id")?.let { sessionIds.add(it) }
        }

        val turnsBySession = LinkedHashMap<String, MutableList<Turn>>()
        openDocs(db, NT_TURN)?.find()?.forEach { doc ->
            val json = documentToJsonValue(doc)
            val turn = mapPlainTurn(json) ?: return@forEach
            turnsBySession.getOrPut(turn.sessionId) { ArrayList() }.add(turn)
        }
        sessionIds.addAll(turnsBySession.keys)

        return sessionIds.map { sid ->
            ChatSession(
                id = sid,
                kind = SessionKind.CHAT,
                sourceDbPath = dbFile.toAbsolutePath().toString(),
                turns = (turnsBySession[sid] ?: emptyList()).sortedBy { it.createdAt }
            )
        }
    }

    private fun mapPlainTurn(json: JsonValue): Turn? {
        val id = json.stringField("id") ?: return null
        val sessionId = json.stringField("sessionId") ?: ""
        val createdAt = json.field("createdAt")?.asLongOrNull() ?: 0L
        val request = json.field("request")
        val response = json.field("response")
        val userText = request.stringField("content") ?: ""
        val assistantText = response.stringField("content") ?: ""
        return Turn(
            id = id,
            sessionId = sessionId,
            createdAt = createdAt,
            chatMode = null,
            model = null,
            user = TurnSide(Role.USER, userText, emptyList()),
            assistant = TurnSide(Role.ASSISTANT, assistantText, emptyList())
        )
    }

    private fun readAgentSessions(db: Nitrite, dbFile: Path): List<ChatSession> {
        if (!anyKnownEntityExists(db, NT_AGENT_SESSION, NT_AGENT_TURN)) {
            throw schemaMismatch(dbFile, NT_AGENT_SESSION, NT_AGENT_TURN)
        }
        val sessionIds = LinkedHashSet<String>()
        openDocs(db, NT_AGENT_SESSION)?.find()?.forEach { doc ->
            documentToJsonValue(doc).stringField("id")?.let { sessionIds.add(it) }
        }

        val turnsBySession = LinkedHashMap<String, MutableList<Turn>>()
        openDocs(db, NT_AGENT_TURN)?.find()?.forEach { doc ->
            val json = documentToJsonValue(doc)
            val turn = mapAgentTurn(json) ?: return@forEach
            turnsBySession.getOrPut(turn.sessionId) { ArrayList() }.add(turn)
        }
        sessionIds.addAll(turnsBySession.keys)

        return sessionIds.map { sid ->
            ChatSession(
                id = sid,
                kind = SessionKind.AGENT,
                sourceDbPath = dbFile.toAbsolutePath().toString(),
                turns = (turnsBySession[sid] ?: emptyList()).sortedBy { it.createdAt }
            )
        }
    }

    private fun mapAgentTurn(json: JsonValue): Turn? {
        val id = json.stringField("id") ?: return null
        val sessionId = json.stringField("sessionId") ?: ""
        val createdAt = json.field("createdAt")?.asLongOrNull() ?: 0L
        val request = json.field("request")
        val response = json.field("response")

        // chatMode lives on both sides in practice; either is fine as the turn-level value.
        val chatMode = request.stringField("chatMode") ?: response.stringField("chatMode")
        val model = request.stringField("model")
            ?: response.field("modelInformation").stringField("modelName")

        val userRawText = request.stringField("stringContent") ?: ""
        val userBlocks = ContentDecoder.decode(request.stringField("contents"))
        val assistantRawText = response.stringField("stringContent") ?: ""
        val assistantBlocks = ContentDecoder.decode(response.stringField("contents"))

        return Turn(
            id = id,
            sessionId = sessionId,
            createdAt = createdAt,
            chatMode = chatMode,
            model = model,
            user = TurnSide(Role.USER, userRawText, userBlocks),
            assistant = TurnSide(Role.ASSISTANT, assistantRawText, assistantBlocks)
        )
    }

    /**
     * chat-edit-sessions (NtEditSession) hasn't been explored beyond
     * confirming the store exists -- this reads it as a single best-effort
     * raw pass-through turn per session (the whole document, pretty-printed
     * as JSON) so nothing throws, rather than guessing at field names that
     * haven't actually been verified. A proper schema discovery pass
     * on this store is still open work before edit sessions can render as
     * richly as chat/agent sessions do.
     */
    private fun readEditSessionsRaw(db: Nitrite, dbFile: Path): List<ChatSession> {
        val docs = openDocs(db, NT_EDIT_SESSION) ?: return emptyList()
        var index = 0
        val sessions = ArrayList<ChatSession>()
        docs.find().forEach { doc ->
            val json = documentToJsonValue(doc)
            val id = json.stringField("id") ?: "edit-session-$index"
            index++
            val raw = json.toPrettyString()
            sessions.add(
                ChatSession(
                    id = id,
                    kind = SessionKind.EDIT,
                    sourceDbPath = dbFile.toAbsolutePath().toString(),
                    turns = listOf(
                        Turn(
                            id = id,
                            sessionId = id,
                            createdAt = json.field("createdAt")?.asLongOrNull() ?: 0L,
                            chatMode = null,
                            model = null,
                            user = TurnSide(Role.USER, "", emptyList()),
                            assistant = TurnSide(Role.ASSISTANT, raw, emptyList())
                        )
                    )
                )
            )
        }
        return sessions
    }

    // ------------------------------------------------------------------
    // Document -> JsonValue. Mirrors the same field-by-field conversion approach
    // used elsewhere in this project, just
    // targeting our own JsonValue tree instead of a plain Java Map/List tree
    // -- that way every part of this module reads fields through the same
    // JsonValue.field()/stringField() accessors, whether the data came from
    // a live Document or from decoding a nested `contents` string.
    // ------------------------------------------------------------------

    private fun documentToJsonValue(doc: Document): JsonValue.JObject {
        val members = LinkedHashMap<String, JsonValue>()
        for (pair in doc) {
            members[pair.getFirst()] = rawToJsonValue(pair.getSecond())
        }
        return JsonValue.JObject(members)
    }

    private fun rawToJsonValue(value: Any?): JsonValue {
        return when (value) {
            null -> JsonValue.JNull
            is Document -> documentToJsonValue(value)
            is Map<*, *> -> {
                val members = LinkedHashMap<String, JsonValue>()
                for (entry in value.entries) {
                    members[entry.key.toString()] = rawToJsonValue(entry.value)
                }
                JsonValue.JObject(members)
            }
            is ByteArray -> JsonValue.JString("<binary ${value.size} bytes>")
            is String -> JsonValue.JString(value)
            is Boolean -> JsonValue.JBool(value)
            is Number -> JsonValue.JNumber(value.toDouble())
            is Iterable<*> -> JsonValue.JArray(value.map { rawToJsonValue(it) })
            is Array<*> -> JsonValue.JArray(value.map { rawToJsonValue(it) })
            else -> JsonValue.JString(value.toString())
        }
    }

    /**
     * Minimal EntityDecorator that only pins down the entity/collection
     * name. We never insert or read typed objects through this -- we always
     * fall back to raw Document access via getDocumentCollection() -- so the
     * entity type itself is a throwaway placeholder and no id/index fields
     * are declared.
     */
    private class NamedEntityDecorator(private val name: String) : EntityDecorator<Document> {
        override fun getEntityType(): Class<Document> = Document::class.java
        override fun getIdField(): EntityId? = null
        override fun getIndexFields(): List<EntityIndex> = emptyList()
        override fun getEntityName(): String = name
    }
}
