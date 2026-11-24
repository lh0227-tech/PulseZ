package cc.pulsez.app.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import cc.pulsez.app.data.db.ChunkEntity
import cc.pulsez.app.data.db.PulsezDatabase
import cc.pulsez.app.data.repo.CharacterRepository
import cc.pulsez.app.data.repo.ChatRepository
import cc.pulsez.app.data.repo.MemoryRepository
import cc.pulsez.app.mem.Boundary
import cc.pulsez.app.mem.Event
import cc.pulsez.app.mem.Fact
import cc.pulsez.app.mem.Loop
import cc.pulsez.app.mem.MemoryStatus
import cc.pulsez.app.mem.MemoryTable
import cc.pulsez.app.mem.Pref
import cc.pulsez.app.mem.Relation
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Room 持久层的 JVM 集成测试（Robolectric + 内存数据库）。
 *
 * 覆盖 2026-11 修复的真实回归：`CharacterRepository.createWithThread` 必须先落角色
 * （thread 对 character 有外键），且 `ChatRepository.ensureThread` 只能插入一条
 * threadId 正确的 segment。同时覆盖记忆双时态与删除传播这两条数据不变量。
 *
 * 只使用公开 API；Room 的 SQLite 行为由 Robolectric 在 JVM 上模拟。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RoomIntegrationTest {

    private lateinit var db: PulsezDatabase
    private lateinit var characters: CharacterRepository
    private lateinit var chat: ChatRepository
    private lateinit var memory: MemoryRepository

    private val now = 1_700_000_000_000L

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, PulsezDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        characters = CharacterRepository(db)
        chat = ChatRepository(db)
        memory = MemoryRepository(db)
    }

    @After
    fun tearDown() {
        db.close()
    }

    // ------------------------------------------------------------------
    // 1. 建角色：外键顺序 + thread/segment 一致性（回归 bug）
    // ------------------------------------------------------------------

    @Test
    fun createWithThreadPersistsCharacterThenThreadWithConsistentSegment() = runBlocking {
        val (character, thread) = characters.createWithThread(
            name = "阿淮",
            persona = "温和、话不多的插画师",
            greeting = "你来啦。",
        )

        // 角色确实落库（旧实现先建 thread 会在这里的外键约束上失败）。
        assertEquals("阿淮", character.name)
        assertEquals(character.id, db.characterDao().byId(character.id)?.id)

        // thread 指向正确的 character。
        val persistedThread = db.threadDao().byId(thread.id)
        assertNotNull("thread 应已落库", persistedThread)
        assertEquals(character.id, persistedThread!!.characterId)
        assertEquals(1, db.threadDao().active().size)

        // activeSegmentId 指向的 segment 属于这条 thread，且只有一条。
        val segmentId = thread.activeSegmentId
        assertNotNull("thread 应有 activeSegmentId", segmentId)
        val segments = db.segmentDao().forThread(thread.id)
        assertEquals("ensureThread 只应插入一条 segment", 1, segments.size)
        val segment = segments.single()
        assertEquals(segmentId, segment.id)
        assertEquals(thread.id, segment.threadId)
        assertEquals(0, segment.ordinal)

        // 不存在 threadId 为空的脏 segment（旧实现会先插一条再补）。
        assertTrue(
            "不应存在 threadId 为空的 segment",
            db.segmentDao().forThread("").isEmpty(),
        )
    }

    @Test
    fun ensureThreadIsIdempotentAndDoesNotDuplicateSegments() = runBlocking {
        val (character, first) = characters.createWithThread("阿淮", "persona")
        val segmentCountAfterCreate = db.segmentDao().forThread(first.id).size

        val second = chat.ensureThread(character.id)
        val third = chat.ensureThread(character.id)

        assertEquals("重复 ensureThread 应返回同一 thread", first.id, second.id)
        assertEquals(first.id, third.id)
        assertEquals("重复 ensureThread 不应新增 thread", 1, db.threadDao().active().size)
        assertEquals(
            "重复 ensureThread 不应新增 segment",
            segmentCountAfterCreate,
            db.segmentDao().forThread(first.id).size,
        )
    }

    // ------------------------------------------------------------------
    // 2. 记忆双时态往返
    // ------------------------------------------------------------------

    @Test
    fun memoryTableRoundTripsThroughDatabase() = runBlocking {
        val threadId = "thread-roundtrip"
        val table = MemoryTable(
            facts = listOf(
                Fact(
                    key = "称呼",
                    value = "她喜欢被叫阿淮",
                    conf = 0.9,
                    importance = 4,
                    validFrom = now,
                    firstSeenAt = now,
                    lastSeenAt = now,
                    sourceMessageIds = listOf("m1"),
                ),
            ),
            events = listOf(
                Event(
                    value = "周五有一场面试",
                    happenedAt = now - 1_000,
                    conf = 0.8,
                    importance = 4,
                    validFrom = now - 1_000,
                    firstSeenAt = now,
                    lastSeenAt = now,
                ),
            ),
            loops = listOf(
                Loop(
                    value = "面试结果还没告诉她",
                    dueAt = now + 86_400_000,
                    conf = 0.7,
                    importance = 5,
                    validFrom = now,
                    firstSeenAt = now,
                    lastSeenAt = now,
                ),
            ),
            prefs = listOf(
                Pref(
                    value = "喜欢雨天，讨厌被叫全名",
                    conf = 0.9,
                    importance = 3,
                    validFrom = now,
                    firstSeenAt = now,
                    lastSeenAt = now,
                ),
            ),
            boundaries = listOf(
                Boundary(
                    value = "不要主动提前男友",
                    conf = 1.0,
                    importance = 5,
                    validFrom = now,
                    firstSeenAt = now,
                    lastSeenAt = now,
                ),
            ),
            relation = Relation(
                stage = "暧昧",
                nicknameByMe = "阿淮",
                nicknameForMe = "小鹿",
                mood = "轻松",
                updatedAt = now,
            ),
        )

        memory.save(threadId, table, segmentId = null, now = now)
        val loaded = memory.load(threadId)

        assertEquals(1, loaded.facts.size)
        assertEquals("称呼", loaded.facts.single().key)
        assertEquals("她喜欢被叫阿淮", loaded.facts.single().value)
        assertEquals(0.9, loaded.facts.single().conf, 1e-6)
        assertEquals(4, loaded.facts.single().importance)
        assertEquals(listOf("m1"), loaded.facts.single().sourceMessageIds)

        assertEquals("周五有一场面试", loaded.events.single().value)
        assertEquals(now - 1_000, loaded.events.single().happenedAt)

        assertEquals("面试结果还没告诉她", loaded.loops.single().value)
        assertEquals(now + 86_400_000, loaded.loops.single().dueAt)

        assertEquals("喜欢雨天，讨厌被叫全名", loaded.prefs.single().value)
        assertEquals("不要主动提前男友", loaded.boundaries.single().value)

        assertEquals("暧昧", loaded.relation?.stage)
        assertEquals("阿淮", loaded.relation?.nicknameByMe)
        assertEquals("小鹿", loaded.relation?.nicknameForMe)
        assertEquals("轻松", loaded.relation?.mood)
    }

    @Test
    fun sameKeyFactSupersedesOldEntryInsteadOfDeletingIt() = runBlocking {
        val threadId = "thread-supersede"
        memory.applyPatch(
            threadId,
            """{"facts":[{"k":"称呼","v":"她喜欢被叫阿淮","conf":0.9}]}""",
            segmentId = null,
            now = now,
        )
        memory.applyPatch(
            threadId,
            """{"facts":[{"k":"称呼","v":"她现在喜欢被叫小鹿","conf":0.95}]}""",
            segmentId = null,
            now = now + 1_000,
        )

        val loaded = memory.load(threadId)
        assertEquals("双时态全集应保留新旧两条", 2, loaded.facts.size)

        val old = loaded.facts.single { it.value == "她喜欢被叫阿淮" }
        assertNotNull("旧条目的 validTo 应被写上", old.validTo)
        assertEquals(MemoryStatus.SUPERSEDED, old.status)
        assertFalse(old.isActive())

        val fresh = loaded.facts.single { it.value == "她现在喜欢被叫小鹿" }
        assertNull("新条目的 validTo 应为空", fresh.validTo)
        assertEquals(MemoryStatus.ACTIVE, fresh.status)
        assertTrue(fresh.isActive())

        assertEquals(1, loaded.activeFacts().size)
        assertEquals("她现在喜欢被叫小鹿", loaded.activeFacts().single().value)
    }

    @Test
    fun deleteEntryMarksFactDeletedAndRemovesItFromActiveSet() = runBlocking {
        val threadId = "thread-delete"
        memory.applyPatch(
            threadId,
            """{"facts":[
                {"k":"称呼","v":"她喜欢被叫阿淮","conf":0.9},
                {"k":"城市","v":"在杭州做设计","conf":0.9}
            ]}""",
            segmentId = null,
            now = now,
        )

        memory.deleteEntry(threadId, "FACT", "她喜欢被叫阿淮")

        val loaded = memory.load(threadId)
        assertFalse(
            "被删条目不应再出现在 active 集合",
            loaded.activeFacts().any { it.value == "她喜欢被叫阿淮" },
        )
        assertEquals(1, loaded.activeFacts().size)
        assertEquals("在杭州做设计", loaded.activeFacts().single().value)

        // append-only：物理行仍在，只是状态变为 DELETED。
        val deleted = loaded.facts.single { it.value == "她喜欢被叫阿淮" }
        assertEquals(MemoryStatus.DELETED, deleted.status)
    }

    // ------------------------------------------------------------------
    // 3. 删除传播（chunk 失效 / 来源记忆降级）
    // ------------------------------------------------------------------

    @Test
    fun invalidatingByMessageHidesChunkFromRecentQueries() = runBlocking {
        val threadId = "thread-chunks"
        val messageId = "msg-1"
        memory.saveChunks(
            listOf(
                ChunkEntity(
                    id = "chunk-1",
                    threadId = threadId,
                    segmentId = "seg-1",
                    text = "她说周五有一场面试",
                    firstMessageAt = now,
                    lastMessageAt = now,
                    messageIds = messageId,
                    dim = 2,
                    vec = byteArrayOf(1, 0),
                    scale = 1f,
                    model = "test-embed",
                    createdAt = now,
                ),
            ),
            terms = emptyList(),
        )
        assertEquals(1, memory.recentChunks(threadId).size)

        memory.invalidateChunksByMessage(threadId, messageId, now + 1)

        assertTrue(
            "来源消息被删后 chunk 不应再被 recentChunks 取出",
            memory.recentChunks(threadId).isEmpty(),
        )
        // 对不存在的数据调用也不应抛异常。
        memory.invalidateChunksByMessage(threadId, "missing-message", now + 2)
    }

    @Test
    fun onMessageDeletedMarksSourcedMemoryUnverified() = runBlocking {
        val (_, thread) = characters.createWithThread("Synthetic memory source", "synthetic")
        val threadId = thread.id
        val sourceMessage = chat.append(
            threadId = thread.id,
            segmentId = requireNotNull(thread.activeSegmentId),
            role = "user",
            text = "synthetic source text",
            tokens = 1,
            createdAt = now,
        )
        val messageId = sourceMessage.id
        memory.applyPatch(
            threadId,
            """{"facts":[
                {"k":"面试","v":"周五有一场面试","conf":0.9,"source_message_ids":["$messageId"]}
            ]}""",
            segmentId = "seg-1",
            now = now,
        )
        assertEquals(1, memory.load(threadId).activeFacts().size)

        memory.onMessageDeleted(threadId, messageId, now + 1)

        val fact = memory.load(threadId).facts.single()
        assertEquals(MemoryStatus.UNVERIFIED, fact.status)
        assertFalse(
            "被标记 UNVERIFIED 的条目不再算作可正常渲染的有效条目",
            fact.isActive() && fact.status == MemoryStatus.ACTIVE,
        )
        // 对不存在的数据调用也不应抛异常。
        memory.onMessageDeleted(threadId, "missing-message", now + 2)
    }
}
