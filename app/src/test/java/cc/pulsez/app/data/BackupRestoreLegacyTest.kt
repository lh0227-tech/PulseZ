package cc.pulsez.app.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import cc.pulsez.app.core.ImportIndexer
import cc.pulsez.app.core.ImportMessage
import cc.pulsez.app.data.db.PulsezDatabase
import cc.pulsez.app.data.repo.BackupRestoreRepository
import cc.pulsez.app.data.repo.MemoryRepository
import cc.pulsez.app.data.repo.RestoreResult
import cc.pulsez.app.importer.BackupInspection
import cc.pulsez.app.importer.BackupIssue
import cc.pulsez.app.importer.BackupParser
import cc.pulsez.app.importer.Source
import cc.pulsez.app.mem.TokenEstimator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
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
 * 旧备份两态 + 兼容默认值 + 归属异常分支（ T2）。
 *
 * 夹具纪律（返修，写死）：**只用于旧格式/异常分支**，新路径必须用真实导出（T1）；
 * E-1～E-6 与 C-3/C-4 的**每条用例各自从基础 JSON 重新派生一份独立副本**，禁止同一条用例
 * 先加 ghost 会话又加 ghost 成员——否则期望计数会互相污染。
 *
 * 用历史导出样本验证向后兼容：
 * - `v0_5_x`（schema 3 老）：有 `attachments`、`character` 无 `styleSamples`、
 * `message` 无 `segmentId/tokens`、`segment` 无时间/tokenSum/batonReason、`memory` 无新字段、
 * 顶层无 `skippedOrphanThreads`；
 * - `v0_2_v0_4`（schema 2）：无 `attachments`；
 * - `v0_1`（schema 1）：`thread` 无 `kind/title/members`、`message` 无 `speakerCharacterId`、
 * `memory` 无 `characterId`。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BackupRestoreLegacyTest {

    private lateinit var targetDb: PulsezDatabase

    private val base = 1_690_000_000_000L

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        targetDb = Room.inMemoryDatabaseBuilder(context, PulsezDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() {
        targetDb.close()
    }

    // ================================================================== 冻结夹具构造器

    private fun characterJson(
        id: String = "c1",
        name: String = "阿淮",
        styleSamples: Boolean = false,
    ): JsonObject = buildJsonObject {
        put("id", id)
        put("name", name)
        put("persona", "人设-$id")
        if (styleSamples) put("styleSamples", "你好呀\u0001嗯")
        put("sourceType", "MANUAL")
        put("createdAt", 1)
        put("updatedAt", 2)
    }

    private fun messageJson(
        id: String,
        role: String = "user",
        content: String = "内容-$id",
        createdAt: Long,
        segmentId: String? = null,
        tokens: Int? = null,
        speakerCharacterId: String? = null,
        includeSpeaker: Boolean = true,
    ): JsonObject = buildJsonObject {
        put("id", id)
        put("role", role)
        put("content", content)
        put("createdAt", createdAt)
        if (segmentId != null) put("segmentId", segmentId)
        if (tokens != null) put("tokens", tokens)
        if (includeSpeaker) {
            if (speakerCharacterId == null) put("speakerCharacterId", "") else put("speakerCharacterId", speakerCharacterId)
        }
    }

    private fun segmentJson(
        id: String,
        ordinal: Int,
        summary: String? = null,
        firstMessageAt: Long? = null,
        lastMessageAt: Long? = null,
        tokenSum: Int? = null,
    ): JsonObject = buildJsonObject {
        put("id", id)
        put("ordinal", ordinal)
        put("summary", summary)
        put("state", "SEALED")
        if (firstMessageAt != null) put("firstMessageAt", firstMessageAt)
        if (lastMessageAt != null) put("lastMessageAt", lastMessageAt)
        if (tokenSum != null) put("tokenSum", tokenSum)
    }

    private fun memoryJson(
        kind: String = "FACT",
        key: String? = "k",
        value: String = "v",
        status: String = "ACTIVE",
        validFrom: Long = 1,
        validTo: Long? = 0,
        characterId: String? = null,
        includeCharacterId: Boolean = true,
        includeNewFields: Boolean = false,
    ): JsonObject = buildJsonObject {
        put("kind", kind)
        if (key != null) put("key", key)
        put("value", value)
        put("status", status)
        put("validFrom", validFrom)
        if (validTo != null) put("validTo", validTo)
        if (includeCharacterId) {
            put("characterId", characterId ?: "")
        }
        if (includeNewFields) {
            put("confidence", 0.8)
            put("importance", 2)
            put("firstSeenAt", validFrom)
            put("lastSeenAt", validFrom)
        }
    }

    private fun memberJson(characterId: String, ordinal: Int, joinedAt: Long = 1): JsonObject = buildJsonObject {
        put("characterId", characterId)
        put("ordinal", ordinal)
        put("joinedAt", joinedAt)
    }

    private fun threadJson(
        id: String = "t1",
        characterId: String? = "c1",
        kind: String? = "SINGLE",
        title: String? = null,
        createdAt: Long = 1,
        lastMessageAt: Long = 2,
        messages: JsonArray = buildJsonArray { },
        segments: JsonArray = buildJsonArray { },
        memories: JsonArray = buildJsonArray { },
        members: JsonArray = buildJsonArray { },
        includeKind: Boolean = true,
        includeTitle: Boolean = true,
        includeMembers: Boolean = true,
    ): JsonObject = buildJsonObject {
        put("id", id)
        if (characterId == null) put("characterId", JsonNull) else put("characterId", characterId)
        if (includeKind) put("kind", kind ?: "SINGLE")
        if (includeTitle) put("title", title)
        put("createdAt", createdAt)
        put("lastMessageAt", lastMessageAt)
        put("messages", messages)
        put("segments", segments)
        put("memories", memories)
        if (includeMembers) put("members", members)
    }

    private fun rootJson(
        characters: JsonArray = buildJsonArray { add(characterJson()) },
        threads: JsonArray = buildJsonArray { add(v05Thread()) },
        schema: Int = 3,
        attachments: JsonArray? = buildJsonArray { },
        skippedOrphanThreads: Int? = null,
    ): String = buildJsonObject {
        put("schema", schema)
        put("app", "pulsez")
        put("exportedAt", base)
        put("character", characters)
        put("thread", threads)
        if (attachments != null) put("attachments", attachments)
        if (skippedOrphanThreads != null) put("skippedOrphanThreads", skippedOrphanThreads)
    }.toString()

    /** .x 老形状的单聊会话：2 条消息都没有 `segmentId`，段只有 id/ordinal/summary/state。 */
    private fun v05Thread(): JsonObject = threadJson(
        messages = buildJsonArray {
            add(messageJson("m1", "user", "第一句", base + 1_000))
            add(messageJson("m2", "assistant", "第二句", base + 2_000))
        },
        segments = buildJsonArray {
            add(segmentJson("s1", 0, "摘要一"))
            add(segmentJson("s2", 1, "摘要二"))
        },
        memories = buildJsonArray { add(memoryJson()) },
    )

    /** 在基础 JSON 上做一份独立副本再改（禁止在同一个对象上二次累加）。 */
    private fun rewrite(baseJson: String, mutate: (JsonObject) -> JsonObject): String =
        mutate(Json.parseToJsonElement(baseJson).jsonObject).toString()

    private fun JsonObject.with(key: String, value: kotlinx.serialization.json.JsonElement): JsonObject =
        JsonObject(toMutableMap().apply { put(key, value) })

    // ================================================================== 恢复设施

    private fun repository(): BackupRestoreRepository = BackupRestoreRepository(
        db = targetDb,
        indexer = ImportIndexer(
            loadMessages = { ids ->
                ids.chunked(500).flatMap { batch ->
                    targetDb.messageDao().byIds(batch).map {
                        ImportMessage(it.id, it.role, it.content, it.createdAt)
                    }
                }
            },
            saveChunks = { chunks, terms -> MemoryRepository(targetDb).saveChunks(chunks, terms) },
        ),
        ioDispatcher = Dispatchers.Unconfined,
    )

    private suspend fun restore(json: String): RestoreResult.Restored {
        val inspection = BackupParser.inspect(json, Source.FILE)
        assertTrue("旧备份必须被识别为 Preview，实际=$inspection（json=${json.take(120)}）", inspection is BackupInspection.Preview)
        val result = repository().restore(BackupParser.parseBackup(json))
        assertTrue("恢复必须成功，实际=$result", result is RestoreResult.Restored)
        return result as RestoreResult.Restored
    }

    private suspend fun singleThreadMessages(threadId: String) = targetDb.messageDao().allForThread(threadId)

    // ================================================================== 三份旧 schema 全通

    @Test
    fun `三种旧 schema 都能识别并恢复`() = runBlocking {
        val v05 = rootJson()
        val v02 = rootJson(schema = 2, attachments = null)
        val v01 = rootJson(
            schema = 1,
            threads = buildJsonArray {
                add(
                    threadJson(
                        createdAt = 1,
                        messages = buildJsonArray {
                            add(messageJson("m1", "user", "第一句", base + 1_000, includeSpeaker = false))
                            add(messageJson("m2", "assistant", "第二句", base + 2_000, includeSpeaker = false))
                        },
                        segments = buildJsonArray { add(segmentJson("s1", 0, "摘要一")) },
                        memories = buildJsonArray { add(memoryJson(includeCharacterId = false)) },
                        includeKind = false,
                        includeTitle = false,
                        includeMembers = false,
                    ),
                )
            },
        )
        listOf("v0_5_x" to v05, "v0_2_v0_4" to v02, "v0_1" to v01).forEach { (name, json) ->
            // 每条用例用独立目标库状态：这里顺序恢复三份，每份都成功且不抛（shape 容错）。
            val result = restore(json)
            assertTrue("$name 恢复后必须有人设", result.characterCount >= 1)
            assertTrue("$name 恢复后必须有会话", result.threadCount >= 1)
            assertEquals("$name 会话消息条数守恒", 2, result.messageCount)
        }
    }

    // ================================================================== 老备份合成段

    @Test
    fun `老备份每会话一个合成 SEALED 段 summary 按 ordinal 拼接`() = runBlocking {
        val result = restore(rootJson())
        assertEquals(1, result.threadCount)
        val thread = targetDb.threadDao().active().single()
        val segments = targetDb.segmentDao().forThread(thread.id)
        assertEquals("老备份：1 个合成 SEALED + 1 个空 OPEN", 2, segments.size)
        val sealed = segments.single { it.state == "SEALED" }
        val open = segments.single { it.state == "OPEN" }
        assertEquals(0, sealed.ordinal)
        assertEquals(1, open.ordinal)
        assertEquals("摘要一\n摘要二", sealed.summary)
        assertEquals("IMPORT", sealed.batonReason)
        val messages = singleThreadMessages(thread.id)
        assertEquals(2, messages.size)
        messages.forEach { assertEquals(sealed.id, it.segmentId) }
        assertEquals(open.id, thread.activeSegmentId)
        assertEquals(1, thread.segmentOrdinal)
    }

    @Test
    fun `全空摘要合成为 null 老备份消息不丢`() = runBlocking {
        val json = rewrite(rootJson()) { root ->
            root.with(
                "thread",
                buildJsonArray {
                    add(
                        threadJson(
                            messages = buildJsonArray {
                                add(messageJson("m1", "user", "一", base + 1_000))
                                add(messageJson("m2", "assistant", "二", base + 2_000))
                            },
                            segments = buildJsonArray { add(segmentJson("s1", 0, null)) },
                            memories = buildJsonArray { add(memoryJson()) },
                        ),
                    )
                },
            )
        }
        restore(json)
        val thread = targetDb.threadDao().active().single()
        assertNull(targetDb.segmentDao().forThread(thread.id).single { it.state == "SEALED" }.summary)
        assertEquals(2, singleThreadMessages(thread.id).size)
    }

    // ================================================================== 兼容默认值

    @Test
    fun `旧备份缺字段回落设计默认值`() = runBlocking {
        val json = rootJson(
            schema = 1,
            threads = buildJsonArray {
                add(
                    threadJson(
                        createdAt = base,
                        messages = buildJsonArray {
                            add(messageJson("m1", "user", "你好世界", base + 1_000, includeSpeaker = false))
                            add(messageJson("m2", "assistant", "收到", base + 2_000, includeSpeaker = false))
                        },
                        segments = buildJsonArray { add(segmentJson("s1", 0, "摘要")) },
                        memories = buildJsonArray {
                            add(memoryJson(kind = "FACT", validFrom = base, includeCharacterId = false))
                            add(memoryJson(kind = "LOOP", key = null, value = "循环", validFrom = base, includeCharacterId = false))
                            add(memoryJson(kind = "BOUNDARY", key = null, value = "边界", validFrom = base, includeCharacterId = false))
                            add(memoryJson(kind = "PREF", key = null, value = "偏好", validFrom = base, includeCharacterId = false))
                        },
                        includeKind = false,
                        includeTitle = false,
                        includeMembers = false,
                    ),
                )
            },
        )
        restore(json)
        val thread = targetDb.threadDao().active().single()
        assertEquals("kind 缺 → SINGLE", "SINGLE", thread.kind)
        assertFalse(thread.archived)
        val messages = singleThreadMessages(thread.id)
        assertEquals(TokenEstimator.estimate("你好世界"), messages[0].tokens)
        assertEquals(TokenEstimator.estimate("收到"), messages[1].tokens)
        val memories = targetDb.memoryDao().allForThread(thread.id).associateBy { it.kind }
        assertEquals(1.0f, memories.getValue("FACT").confidence, 0.0f)
        assertEquals(3, memories.getValue("FACT").importance)
        assertEquals(4, memories.getValue("LOOP").importance)
        assertEquals(5, memories.getValue("BOUNDARY").importance)
        assertEquals(3, memories.getValue("PREF").importance)
        memories.values.forEach { memory ->
            assertEquals(base, memory.validFrom)
            assertEquals(base, memory.firstSeenAt)
            assertEquals(base, memory.lastSeenAt)
            assertNull("validTo 0 哨兵必须还原成 NULL", memory.validTo)
        }
    }

    // ================================================================== E-1 FK 孤儿会话

    @Test
    fun `E-1 孤儿会话跳过并计数 其余照常恢复`() = runBlocking {
        val json = rewrite(rootJson()) { root ->
            root.with(
                "thread",
                buildJsonArray {
                    add(v05Thread())
                    add(
                        threadJson(
                            id = "t-orphan",
                            characterId = "ghost",
                            messages = buildJsonArray { add(messageJson("om1", "user", "孤儿消息", base + 1_000)) },
                            segments = buildJsonArray { add(segmentJson("os1", 0, "孤儿摘要")) },
                            memories = buildJsonArray { add(memoryJson(value = "孤儿记忆")) },
                        ),
                    )
                },
            )
        }
        val result = restore(json)
        assertEquals(1, result.skippedOrphanThreads)
        assertEquals(0, result.ignoredMembers)
        assertEquals(0, result.ignoredSpeakers)
        assertEquals(0, result.ignoredMemories)
        assertEquals(1, result.threadCount)
        assertEquals(2, result.messageCount)
        // 正常会话完整恢复。
        assertEquals(1, targetDb.threadDao().active().size)
        assertEquals(2, singleThreadMessages(targetDb.threadDao().active().single().id).size)
    }

    // ================================================================== E-2 群成员悬空

    @Test
    fun `E-2 群成员指向未导出角色 丢弃并重排 ordinal`() = runBlocking {
        val json = rootJson(
            threads = buildJsonArray {
                add(
                    threadJson(
                        id = "tg",
                        characterId = null,
                        kind = "GROUP",
                        title = "群",
                        messages = buildJsonArray { add(messageJson("g1", "user", "大家好", base + 1_000)) },
                        members = buildJsonArray {
                            add(memberJson("c1", ordinal = 0))
                            add(memberJson("ghost", ordinal = 1))
                        },
                    ),
                )
            },
        )
        val result = restore(json)
        assertEquals(1, result.ignoredMembers)
        assertEquals(0, result.skippedOrphanThreads)
        assertEquals(0, result.ignoredSpeakers)
        assertEquals(0, result.ignoredMemories)
        val thread = targetDb.threadDao().active().single()
        val members = targetDb.threadMemberDao().membersOf(thread.id)
        assertEquals("悬空成员不落行", 1, members.size)
        assertEquals("存活成员 ordinal 必须连续重排", 0, members.single().ordinal)
    }

    // ================================================================== E-3 群发言悬空

    @Test
    fun `E-3 群发言指向未导出角色 置 null 并计数`() = runBlocking {
        val json = rootJson(
            characters = buildJsonArray {
                add(characterJson("c1"))
                add(characterJson("c2"))
            },
            threads = buildJsonArray {
                add(
                    threadJson(
                        id = "tg",
                        characterId = null,
                        kind = "GROUP",
                        messages = buildJsonArray {
                            add(messageJson("g1", "assistant", "我是幽灵", base + 1_000, speakerCharacterId = "ghost"))
                        },
                        members = buildJsonArray {
                            add(memberJson("c1", 0))
                            add(memberJson("c2", 1))
                        },
                    ),
                )
            },
        )
        val result = restore(json)
        assertEquals(1, result.ignoredSpeakers)
        assertEquals(0, result.ignoredMembers)
        assertEquals(0, result.skippedOrphanThreads)
        assertEquals(0, result.ignoredMemories)
        val thread = targetDb.threadDao().active().single()
        assertNull(singleThreadMessages(thread.id).single().speakerCharacterId)
    }

    // ================================================================== E-4 群记忆悬空

    @Test
    fun `E-4 群记忆指向未导出角色 整行丢弃 不置 null`() = runBlocking {
        val json = rootJson(
            threads = buildJsonArray {
                add(
                    threadJson(
                        id = "tg",
                        characterId = null,
                        kind = "GROUP",
                        messages = buildJsonArray { add(messageJson("g1", "user", "大家好", base + 1_000)) },
                        memories = buildJsonArray { add(memoryJson(characterId = "ghost", value = "幽灵记忆")) },
                        members = buildJsonArray { add(memberJson("c1", 0)) },
                    ),
                )
            },
        )
        val result = restore(json)
        assertEquals(1, result.ignoredMemories)
        assertEquals(0, result.ignoredMembers)
        assertEquals(0, result.ignoredSpeakers)
        assertEquals(0, result.skippedOrphanThreads)
        val thread = targetDb.threadDao().active().single()
        assertTrue("该记忆行不得存在", targetDb.memoryDao().allForThread(thread.id).isEmpty())
        // 也不得出现「同值但 characterId IS NULL」的会话级替身行。
        assertEquals(
            0,
            targetDb.openHelper.readableDatabase
                .query("SELECT COUNT(*) FROM memory_item WHERE value = '幽灵记忆'")
                .use { it.moveToFirst(); it.getInt(0) },
        )
    }

    // ================================================================== E-5 成员全丢仍还原

    @Test
    fun `E-5 群成员全丢 会话仍还原`() = runBlocking {
        val json = rootJson(
            threads = buildJsonArray {
                add(
                    threadJson(
                        id = "tg",
                        characterId = null,
                        kind = "GROUP",
                        messages = buildJsonArray {
                            add(messageJson("g1", "user", "大家好", base + 1_000))
                            add(messageJson("g2", "assistant", "在", base + 2_000, speakerCharacterId = "c1"))
                        },
                        members = buildJsonArray {
                            add(memberJson("ghost1", 0))
                            add(memberJson("ghost2", 1))
                        },
                    ),
                )
            },
        )
        val result = restore(json)
        assertEquals(2, result.ignoredMembers)
        assertEquals(1, result.threadCount)
        assertEquals(2, result.messageCount)
        val thread = targetDb.threadDao().active().single()
        assertEquals("对话和记忆保留", 2, singleThreadMessages(thread.id).size)
        assertTrue(targetDb.threadMemberDao().membersOf(thread.id).isEmpty())
    }

    // ================================================================== E-6 空角色 id

    @Test
    fun `E-6 角色 id 为空 不写角色 其会话按孤儿跳过`() = runBlocking {
        val json = rootJson(
            characters = buildJsonArray { add(characterJson(id = "")) },
            threads = buildJsonArray {
                add(
                    threadJson(
                        id = "t1",
                        characterId = "",
                        messages = buildJsonArray { add(messageJson("m1", "user", "消息", base + 1_000)) },
                    ),
                )
            },
        )
        val result = restore(json)
        assertEquals(1, result.ignoredCharacters)
        assertEquals(1, result.skippedOrphanThreads)
        assertEquals(0, result.ignoredMembers)
        assertEquals(0, result.ignoredSpeakers)
        assertEquals(0, result.ignoredMemories)
        assertEquals("不得写任何角色行", 0, targetDb.characterDao().all().size)
        assertEquals(0, targetDb.threadDao().active().size)
    }

    // ================================================================== C-3 混合 segmentId

    @Test
    fun `C-3 混合 segmentId 落到同一个合成段 消息守恒 3`() = runBlocking {
        val json = rootJson(
            threads = buildJsonArray {
                add(
                    threadJson(
                        id = "t1",
                        messages = buildJsonArray {
                            add(messageJson("m1", "user", "一", base + 1_000, segmentId = "seg-a"))
                            add(messageJson("m2", "assistant", "二", base + 2_000))
                            add(messageJson("m3", "user", "三", base + 3_000, segmentId = ""))
                        },
                        segments = buildJsonArray { add(segmentJson("seg-a", 0, "摘A")) },
                    ),
                )
            },
        )
        restore(json)
        val thread = targetDb.threadDao().active().single()
        val segments = targetDb.segmentDao().forThread(thread.id)
        assertEquals("混合态：只有 1 个合成 SEALED + 1 个 OPEN", 2, segments.size)
        val sealed = segments.single { it.state == "SEALED" }
        assertEquals(0, sealed.ordinal)
        assertEquals(1, segments.single { it.state == "OPEN" }.ordinal)
        assertEquals("摘A", sealed.summary)
        val messages = singleThreadMessages(thread.id)
        assertEquals("消息条数守恒", 3, messages.size)
        messages.forEach { assertEquals(sealed.id, it.segmentId) }
    }

    // ================================================================== C-4 新格式悬空 segmentId

    @Test
    fun `C-4 新格式悬空 segmentId 自成一组排最后 消息守恒 4`() = runBlocking {
        val json = rootJson(
            threads = buildJsonArray {
                add(
                    threadJson(
                        id = "t1",
                        messages = buildJsonArray {
                            add(messageJson("a1", "user", "a一", base + 1_000, segmentId = "seg-a", tokens = 3))
                            add(messageJson("a2", "assistant", "a二", base + 2_000, segmentId = "seg-a", tokens = 4))
                            add(messageJson("b1", "user", "b一", base + 3_000, segmentId = "seg-b", tokens = 5))
                            add(messageJson("x1", "user", "悬空", base + 4_000, segmentId = "seg-ghost", tokens = 6))
                        },
                        segments = buildJsonArray {
                            add(segmentJson("seg-a", 0, null))
                            add(segmentJson("seg-b", 1, null))
                        },
                    ),
                )
            },
        )
        restore(json)
        val thread = targetDb.threadDao().active().single()
        val segments = targetDb.segmentDao().forThread(thread.id)
        assertEquals("3 个还原 SEALED + 1 个空 OPEN", 4, segments.size)
        val sealed = segments.filter { it.state == "SEALED" }.sortedBy { it.ordinal }
        assertEquals(listOf(0, 1, 2), sealed.map { it.ordinal })
        val segA = sealed[0]
        val segB = sealed[1]
        val ghost = sealed[2]
        assertEquals("seg-a 组 2 条", 2, targetDb.messageDao().bySegment(segA.id).size)
        assertEquals("seg-b 组 1 条", 1, targetDb.messageDao().bySegment(segB.id).size)
        val ghostMessages = targetDb.messageDao().bySegment(ghost.id)
        assertEquals("悬空组恰 1 条", 1, ghostMessages.size)
        assertEquals("悬空组不能混入其它段消息", setOf("悬空"), ghostMessages.map { it.content }.toSet())
        // 悬空组段头 = 该消息时间/tokens（无导出段，按 C-4 钳制口径）。
        assertEquals(base + 4_000, ghost.firstMessageAt)
        assertEquals(base + 4_000, ghost.lastMessageAt)
        assertEquals(6, ghost.tokenSum)
        // 消息守恒 4。
        assertEquals(4, singleThreadMessages(thread.id).size)
    }

    // ================================================================== 超上限备份不进恢复

    @Test
    fun `超过上限的备份被拒收 不进入恢复`() {
        val tooManyCharacters = buildJsonObject {
            put("schema", 3)
            put("app", "pulsez")
            put("character", buildJsonArray { repeat(BackupParser.MAX_CHARACTERS + 1) { add(characterJson("c$it")) } })
            put("thread", buildJsonArray { })
        }.toString()
        val inspection = BackupParser.inspect(tooManyCharacters, Source.FILE)
        assertTrue("超上限必须拒收，实际=$inspection", inspection is BackupInspection.Rejected)
        assertEquals(
            BackupIssue.TOO_MANY_CHARACTERS,
            (inspection as BackupInspection.Rejected).issue,
        )
    }
}
