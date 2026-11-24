package cc.pulsez.app.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import cc.pulsez.app.data.db.PulsezDatabase
import cc.pulsez.app.data.db.ThreadKind
import cc.pulsez.app.data.repo.CharacterRepository
import cc.pulsez.app.data.repo.ChatRepository
import cc.pulsez.app.data.repo.MemoryRepository
import cc.pulsez.app.mem.Fact
import cc.pulsez.app.mem.MemoryTable
import cc.pulsez.app.mem.Relation
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 多角色数据层验收（ 第 1/2/5 条 + schema 契约）：
 * 建群 thread + 成员顺序、群消息 `speakerCharacterId` 与历史顺序、记忆按 ownerId 隔离。
 *
 * Robolectric + 内存 Room，不连真机。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MultiCharacterDataTest {

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
    // 1. 建群：thread(kind=GROUP, characterId=null) + thread_member 顺序
    // ------------------------------------------------------------------

    @Test
    fun createGroupThreadPersistsKindAndMemberOrdinals() = runBlocking {
        val (a, threadA) = characters.createWithThread("阿淮", "温和")
        val (b, _) = characters.createWithThread("小鹿", "活泼")
        val (c, _) = characters.createWithThread("阿岚", "冷静")

        val group = chat.createGroupThread(listOf(b.id, a.id, c.id), "我们仨")

        // thread 契约
        assertEquals(ThreadKind.GROUP, group.kind)
        assertNull("群聊 thread 没有单一主角", group.characterId)
        assertEquals("我们仨", group.title)
        val persisted = db.threadDao().byId(group.id)
        assertNotNull(persisted)
        assertEquals(ThreadKind.GROUP, persisted!!.kind)
        // 群聊也有首个空段，append 永远有落点
        assertNotNull(group.activeSegmentId)
        assertEquals(1, db.segmentDao().forThread(group.id).size)

        // 单聊 thread 仍是 SINGLE，按 kind 查询互不串
        assertEquals(ThreadKind.SINGLE, threadA.kind)
        assertEquals(1, db.threadDao().activeByKind(ThreadKind.GROUP).size)
        assertEquals(3, db.threadDao().activeByKind(ThreadKind.SINGLE).size)

        // 成员顺序 = 传入顺序，ordinal 0 起
        val members = chat.membersOf(group.id)
        assertEquals(listOf(b.id, a.id, c.id), members.map { it.characterId })
        assertEquals(listOf(0, 1, 2), members.map { it.ordinal })
        assertEquals(group.id, db.threadMemberDao().ofCharacter(a.id).single().threadId)

        // 群名复用 thread.title，setTitle/setKind 可用
        db.threadDao().setTitle(group.id, "新的群名")
        assertEquals("新的群名", db.threadDao().byId(group.id)?.title)
        db.threadDao().setKind(group.id, ThreadKind.SINGLE)
        assertEquals(ThreadKind.SINGLE, db.threadDao().byId(group.id)?.kind)
        db.threadDao().setKind(group.id, ThreadKind.GROUP)

        // remove 只删一个成员
        db.threadMemberDao().remove(group.id, c.id)
        assertEquals(listOf(b.id, a.id), chat.membersOf(group.id).map { it.characterId })
    }

    @Test
    fun createGroupThreadRejectsInvalidMemberSets() = runBlocking {
        val (a, _) = characters.createWithThread("阿淮", "温和")
        val (b, _) = characters.createWithThread("小鹿", "活泼")

        assertIllegalArgument("1 人不能建群") { chat.createGroupThread(listOf(a.id)) }
        assertIllegalArgument("重复成员去重后只剩 1 人") {
            chat.createGroupThread(listOf(a.id, a.id))
        }
        assertIllegalArgument("不存在的角色不能进群") {
            chat.createGroupThread(listOf(a.id, "missing-character"))
        }
        assertEquals("校验失败不能留下半成品群", 0, db.threadDao().activeByKind(ThreadKind.GROUP).size)

        // 2–4 人是合法区间；重复传入会去重（顺序保留第一次出现）
        val group = chat.createGroupThread(listOf(a.id, b.id, a.id), null)
        assertEquals(2, chat.membersOf(group.id).size)
        assertNull("空白群名落 null，UI 用默认标题", group.title)
    }

    // ------------------------------------------------------------------
    // 2. 群消息：speakerCharacterId + 历史顺序
    // ------------------------------------------------------------------

    @Test
    fun groupMessagesCarrySpeakerAndHistoryIsOrderedByMemberOrdinal() = runBlocking {
        val (a, _) = characters.createWithThread("阿淮", "温和")
        val (b, _) = characters.createWithThread("小鹿", "活泼")
        val (c, _) = characters.createWithThread("阿岚", "冷静")
        val group = chat.createGroupThread(listOf(a.id, b.id, c.id), null)
        val segmentId = group.activeSegmentId!!

        // 用户消息（speaker = null）
        chat.append(group.id, segmentId, role = "user", text = "在吗", tokens = 2, createdAt = now)
        // 三位成员在同一毫秒回复：靠 ordinal 兜底排序，必须与成员列表顺序一致
        chat.append(group.id, segmentId, "assistant", "我在", 2, createdAt = now + 1, speakerCharacterId = c.id)
        chat.append(group.id, segmentId, "assistant", "我也在", 2, createdAt = now + 1, speakerCharacterId = a.id)
        chat.append(group.id, segmentId, "assistant", "嗯", 1, createdAt = now + 1, speakerCharacterId = b.id)

        val history = chat.groupHistory(group.id)
        assertEquals(4, history.size)
        assertEquals("user", history.first().role)
        assertNull("用户消息不带发言人", history.first().speakerCharacterId)
        assertEquals(
            "同一毫秒内按成员 ordinal 排序",
            listOf(a.id, b.id, c.id),
            history.drop(1).map { it.speakerCharacterId },
        )
        assertEquals(listOf("我也在", "嗯", "我在"), history.drop(1).map { it.content })

        // 按发言人过滤
        assertEquals(listOf("我也在"), chat.historyForSpeaker(group.id, a.id).map { it.content })
        assertEquals(listOf("嗯"), chat.historyForSpeaker(group.id, b.id).map { it.content })
        assertTrue(chat.historyForSpeaker(group.id, c.id).none { it.content == "我也在" })

        // 单聊 append 不传 speaker 时仍为 null（旧调用兼容）
        val single = chat.ensureThread(a.id)
        val singleMessage = chat.append(single.id, single.activeSegmentId!!, "assistant", "单聊回复", 2)
        assertNull(singleMessage.speakerCharacterId)
    }

    // ------------------------------------------------------------------
    // 3. 记忆隔离：按 (threadId, characterId) 读写，A 的 fact 不出现在 B 的结果里
    // ------------------------------------------------------------------

    @Test
    fun memoryIsIsolatedByOwnerId() = runBlocking {
        val (a, _) = characters.createWithThread("阿淮", "温和")
        val (b, _) = characters.createWithThread("小鹿", "活泼")
        val group = chat.createGroupThread(listOf(a.id, b.id), null)
        val segmentId = group.activeSegmentId

        memory.applyPatch(
            group.id,
            """{"facts":[{"k":"秘密","v":"A 知道的事","conf":0.9}]}""",
            segmentId,
            now,
            ownerId = a.id,
        )
        memory.applyPatch(
            group.id,
            """{"facts":[{"k":"秘密","v":"B 知道的事","conf":0.9}]}""",
            segmentId,
            now,
            ownerId = b.id,
        )

        val aMemory = memory.load(group.id, a.id)
        val bMemory = memory.load(group.id, b.id)
        assertEquals(listOf("A 知道的事"), aMemory.facts.map { it.value })
        assertEquals(listOf("B 知道的事"), bMemory.facts.map { it.value })
        assertTrue(
            "A 的 fact 不能出现在 B 的查询结果里",
            bMemory.facts.none { it.value == "A 知道的事" },
        )
        assertEquals(1, memory.activeCount(group.id, a.id))
        assertEquals(1, memory.activeCount(group.id, b.id))

        // 存储层确实按 characterId 落库
        val aRows = db.memoryDao().forOwner(group.id, a.id)
        assertEquals(1, aRows.size)
        assertEquals(a.id, aRows.single().characterId)
        assertEquals("A 知道的事", aRows.single().value)

        // 单聊维度（ownerId = null）不被群聊记忆污染
        assertTrue("群聊记忆不应出现在单聊维度", memory.load(group.id).facts.isEmpty())
        memory.applyPatch(
            group.id,
            """{"facts":[{"k":"维度","v":"单聊维度的记忆","conf":0.9}]}""",
            segmentId,
            now,
        )
        assertTrue(memory.load(group.id, a.id).facts.none { it.value == "单聊维度的记忆" })

        // 删除只影响 A 自己的记忆
        memory.deleteEntry(group.id, "FACT", "A 知道的事", ownerId = a.id)
        assertTrue(memory.load(group.id, a.id).activeFacts().isEmpty())
        assertEquals(1, memory.load(group.id, b.id).activeFacts().size)
        assertEquals("B 知道的事", memory.load(group.id, b.id).activeFacts().single().value)
    }

    @Test
    fun groupOwnerDimensionDoesNotLeakSharedRelationState() = runBlocking {
        val (a, _) = characters.createWithThread("阿淮", "温和")
        val (b, _) = characters.createWithThread("小鹿", "活泼")
        val singleThread = chat.ensureThread(a.id)
        val group = chat.createGroupThread(listOf(a.id, b.id), null)

        // 单聊关系状态照旧可写可读（带一条 fact，因为 load 在无记忆行时直接返回空表）
        memory.save(
            singleThread.id,
            MemoryTable(
                facts = listOf(
                    Fact(
                        key = "称呼",
                        value = "她喜欢被叫阿淮",
                        conf = 0.9,
                        importance = 4,
                        validFrom = now,
                        firstSeenAt = now,
                        lastSeenAt = now,
                    ),
                ),
                relation = Relation(stage = "暧昧", nicknameByMe = "阿淮", updatedAt = now),
            ),
            segmentId = null,
            now = now,
        )
        assertEquals("暧昧", memory.load(singleThread.id).relation?.stage)

        // 群聊 owner 维度写关系状态：thread_state 没有角色维度，必须安全降级不落库、不串味
        memory.save(
            group.id,
            MemoryTable(relation = Relation(stage = "疏远", updatedAt = now)),
            segmentId = null,
            now = now,
            ownerId = a.id,
        )
        assertNull("群聊 A 维度不应读到关系状态", memory.load(group.id, a.id).relation)
        assertNull("群聊 B 维度不应读到关系状态", memory.load(group.id, b.id).relation)
        assertNull("群聊不应该抢用单聊的 thread_state 行", db.threadStateDao().get(group.id))
    }

    // ------------------------------------------------------------------

    private suspend fun assertIllegalArgument(message: String, block: suspend () -> Unit) {
        try {
            block()
            fail(message)
        } catch (_: IllegalArgumentException) {
            // 期望：参数校验失败
        }
    }
}
