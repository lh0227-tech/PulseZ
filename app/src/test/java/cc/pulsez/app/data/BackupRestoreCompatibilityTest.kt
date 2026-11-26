package cc.pulsez.app.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import cc.pulsez.app.core.EmbedPort
import cc.pulsez.app.core.ImportIndexer
import cc.pulsez.app.core.ImportMessage
import cc.pulsez.app.data.db.PulsezDatabase
import cc.pulsez.app.data.repo.BackupRestoreRepository
import cc.pulsez.app.data.repo.CharacterRepository
import cc.pulsez.app.data.repo.ChatRepository
import cc.pulsez.app.data.repo.RestoreResult
import cc.pulsez.app.importer.BackupInspection
import cc.pulsez.app.importer.BackupParser
import cc.pulsez.app.importer.Source
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * v8 备份导出/解析/恢复兼容性测试：
 * - schema 保持 3，导出新增可选 `citationsJson`、`latestReminder`；
 * - 往返恢复保留上述可选字段，缺省时保持 null；
 * - 不含新字段的旧 schema=3 备份仍可正常预览与恢复。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BackupRestoreCompatibilityTest {

    private lateinit var sourceDb: PulsezDatabase
    private lateinit var targetDb: PulsezDatabase

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        sourceDb = Room.inMemoryDatabaseBuilder(context, PulsezDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        targetDb = Room.inMemoryDatabaseBuilder(context, PulsezDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() {
        sourceDb.close()
        targetDb.close()
    }

    private fun offlineIndexer(db: PulsezDatabase): ImportIndexer = ImportIndexer(
        loadMessages = { ids ->
            ids.chunked(500).flatMap { batch ->
                db.messageDao().byIds(batch).map { m ->
                    ImportMessage(id = m.id, role = m.role, text = m.content, at = m.createdAt)
                }
            }
        },
        saveChunks = { chunks, terms ->
            db.chunkDao().insertAll(chunks)
            db.chunkDao().insertTerms(terms)
        },
        embed = EmbedPort { null },
        embedModel = { "" },
        embedConfigured = { false },
    )

    @Test
    fun `export and restore round-trip preserves citationsJson and latestReminder under schema 3`() = runBlocking {
        assertEquals(3, ExportService.SCHEMA)
        assertEquals(3, BackupParser.SUPPORTED_SCHEMA)

        val (_, thread) = CharacterRepository(sourceDb).createWithThread("阿淮", "温和")
        val chat = ChatRepository(sourceDb)
        val segId = thread.activeSegmentId!!
        val citations = "[{\"url\":\"https://api-docs.deepseek.com/\",\"title\":\"DeepSeek\",\"startIndex\":0,\"endIndex\":6}]"
        val reminder = "2026-11-25,周三,,App,中文"
        chat.append(
            threadId = thread.id,
            segmentId = segId,
            role = "user",
            text = "查一下文档",
            tokens = 4,
            createdAt = 1_000L,
            latestReminder = reminder,
        )
        chat.append(
            threadId = thread.id,
            segmentId = segId,
            role = "assistant",
            text = "已查到说明",
            tokens = 5,
            createdAt = 2_000L,
            reasoningContent = "内部思考不导出",
            serverToolCallsJson = "[{\"id\":\"call-1\"}]",
            citationsJson = citations,
        )

        val json = ExportService.buildJson(sourceDb)
        val root = Json.parseToJsonElement(json).jsonObject
        val exportedMsgs = root.getValue("thread").jsonArray.single().jsonObject
            .getValue("messages").jsonArray.map { it.jsonObject }
        assertEquals(reminder, exportedMsgs[0]["latestReminder"]?.let { it.toString().trim('"') })
        assertFalse(exportedMsgs[0].containsKey("citationsJson"))
        assertEquals(citations, exportedMsgs[1]["citationsJson"]?.let {
            Json.decodeFromString(kotlinx.serialization.serializer<String>(), it.toString())
        })
        assertFalse(exportedMsgs[1].containsKey("reasoningContent"))
        assertFalse(exportedMsgs[1].containsKey("serverToolCallsJson"))

        val inspection = BackupParser.inspect(json, Source.PASTE)
        assertTrue(inspection is BackupInspection.Preview)
        val doc = BackupParser.parseBackup(json)
        val parsedMsgs = doc.threads.single().messages
        assertEquals(reminder, parsedMsgs[0].latestReminder)
        assertNull(parsedMsgs[0].citationsJson)
        assertEquals(citations, parsedMsgs[1].citationsJson)
        assertNull(parsedMsgs[1].latestReminder)

        val result = BackupRestoreRepository(
            db = targetDb,
            indexer = offlineIndexer(targetDb),
            ioDispatcher = Dispatchers.Unconfined,
        ).restore(doc)
        assertTrue(result is RestoreResult.Restored)

        val restoredThread = targetDb.threadDao().active().single()
        val restoredMsgs = targetDb.messageDao().allForThread(restoredThread.id)
        assertEquals(2, restoredMsgs.size)
        assertEquals(reminder, restoredMsgs[0].latestReminder)
        assertNull(restoredMsgs[0].citationsJson)
        assertEquals(citations, restoredMsgs[1].citationsJson)
        assertNull(restoredMsgs[1].latestReminder)
        assertNull(restoredMsgs[1].reasoningContent)
        assertNull(restoredMsgs[1].serverToolCallsJson)
    }

    @Test
    fun `legacy backup without citationsJson and latestReminder restores with nulls`() = runBlocking {
        val legacyJson = buildJsonObject {
            put("schema", 3)
            put("app", "pulsez")
            put("exportedAt", 1_690_000_000_000L)
            put(
                "character",
                buildJsonArray {
                    add(
                        buildJsonObject {
                            put("id", "c1")
                            put("name", "阿淮")
                            put("persona", "温和")
                            put("sourceType", "MANUAL")
                            put("createdAt", 1_000L)
                            put("updatedAt", 1_000L)
                        },
                    )
                },
            )
            put(
                "thread",
                buildJsonArray {
                    add(
                        buildJsonObject {
                            put("id", "t1")
                            put("characterId", "c1")
                            put("kind", "SINGLE")
                            put("title", "")
                            put("createdAt", 1_000L)
                            put("lastMessageAt", 2_000L)
                            put(
                                "messages",
                                buildJsonArray {
                                    add(
                                        buildJsonObject {
                                            put("id", "m1")
                                            put("threadId", "t1")
                                            put("role", "user")
                                            put("content", "在吗")
                                            put("createdAt", 1_000L)
                                            put("speakerCharacterId", "")
                                            put("segmentId", "s1")
                                            put("tokens", 2)
                                        },
                                    )
                                },
                            )
                            put(
                                "segments",
                                buildJsonArray {
                                    add(
                                        buildJsonObject {
                                            put("id", "s1")
                                            put("ordinal", 0)
                                            put("summary", "")
                                            put("state", "OPEN")
                                        },
                                    )
                                },
                            )
                            put("memories", buildJsonArray { })
                            put("members", buildJsonArray { })
                        },
                    )
                },
            )
            put("attachments", buildJsonArray { })
        }.toString()

        assertTrue(BackupParser.inspect(legacyJson, Source.PASTE) is BackupInspection.Preview)
        val doc = BackupParser.parseBackup(legacyJson)
        val parsedMsg = doc.threads.single().messages.single()
        assertNull(parsedMsg.citationsJson)
        assertNull(parsedMsg.latestReminder)

        val result = BackupRestoreRepository(
            db = targetDb,
            indexer = offlineIndexer(targetDb),
            ioDispatcher = Dispatchers.Unconfined,
        ).restore(doc)
        assertTrue(result is RestoreResult.Restored)

        val restoredThread = targetDb.threadDao().active().single()
        val restoredMsg = targetDb.messageDao().allForThread(restoredThread.id).single()
        assertEquals("在吗", restoredMsg.content)
        assertNull(restoredMsg.citationsJson)
        assertNull(restoredMsg.latestReminder)
    }
}
