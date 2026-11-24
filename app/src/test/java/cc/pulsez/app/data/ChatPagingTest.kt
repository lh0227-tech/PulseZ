package cc.pulsez.app.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import cc.pulsez.app.data.db.MessageEntity
import cc.pulsez.app.data.db.ImportSourceSessionEntity
import cc.pulsez.app.data.db.PulsezDatabase
import cc.pulsez.app.data.db.SegmentEntity
import cc.pulsez.app.data.repo.CharacterRepository
import cc.pulsez.app.data.repo.ChatRepository
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 聊天页「查看更早」的游标分页（ ⑤）。
 *
 * 锁三条不变量（Robolectric 内存 Room，纯离线）：
 * 1. 首屏 `tail(80)` + 前插 `tailBefore(60)` **不重不漏**、整体时间正序；
 * 2. 同一毫秒的多条消息靠 `id` 兜底，翻页也不重不漏（`tail` 与 `before` 的排序键一致）；
 * 3. 空 thread 返回空列表，不崩。
 *
 * 与 `RoomIntegrationTest` 同一套 Robolectric 内存库模式。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ChatPagingTest {

    private lateinit var db: PulsezDatabase
    private lateinit var characters: CharacterRepository
    private lateinit var chat: ChatRepository

    private lateinit var threadId: String
    private lateinit var segmentId: String

    private val base = 1_700_000_000_000L

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, PulsezDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        characters = CharacterRepository(db)
        chat = ChatRepository(db)
        runBlocking {
            val (_, thread) = characters.createWithThread("阿淮", "温和、话不多")
            threadId = thread.id
            segmentId = thread.activeSegmentId!!
        }
    }

    @After
    fun tearDown() {
        db.close()
    }

    /** 直接按指定 id / 时间落一条消息：分页测试需要完全确定的排序键。 */
    private suspend fun insertMessage(id: String, createdAt: Long, targetSegmentId: String = segmentId) {
        db.messageDao().upsert(
            MessageEntity(
                id = id,
                threadId = threadId,
                segmentId = targetSegmentId,
                role = "user",
                content = "消息 $id",
                tokens = 1,
                emotion = null,
                status = "OK",
                draft = null,
                usageIn = null,
                usageOut = null,
                previousContent = null,
                createdAt = createdAt,
            ),
        )
    }

    private fun idsOf(count: Int = 100): List<String> = (0 until count).map { "m-%03d".format(it) }

    // ------------------------------------------------------------------
    // 1. 不同 createdAt：tail(80) + tailBefore(60) 不重不漏、顺序正确
    // ------------------------------------------------------------------

    @Test
    fun `tailThenTailBeforeCoversAllMessagesWithoutOverlapOrGap`() = runBlocking {
        val ids = idsOf()
        ids.forEachIndexed { index, id -> insertMessage(id, base + index * 1_000L) }

        val firstPage = chat.tail(threadId, limit = 80)
        assertEquals("首屏应取满一页", 80, firstPage.size)
        assertEquals("首屏应是最近 80 条（时间正序）", ids.subList(20, 100), firstPage.map { it.id })

        val cursor = firstPage.first()
        val older = chat.tailBefore(threadId, cursor.createdAt, cursor.id, limit = 60)
        assertEquals("更早一页应是剩下 20 条", 20, older.size)
        assertEquals(ids.subList(0, 20), older.map { it.id })

        // 不重不漏：两页拼起来正好是全部 100 条，且整体时间正序。
        assertEquals("两页拼接应无重叠、无缺口", ids, older.map { it.id } + firstPage.map { it.id })

        // 再往前就是头了：返回空，且不抛。
        val oldest = older.first()
        assertTrue(
            "已到最早一条时 tailBefore 应返回空",
            chat.tailBefore(threadId, oldest.createdAt, oldest.id, limit = 60).isEmpty(),
        )
    }

    // ------------------------------------------------------------------
    // 2. 同毫秒混合：同一毫秒内用 id 兜底，翻页不重不漏
    // ------------------------------------------------------------------

    @Test
    fun `sameMillisecondMessagesUseIdTieBreaker`() = runBlocking {
        val ids = idsOf()
        // 100 条全部落在同一毫秒：只靠 createdAt 无法区分先后，必须用 id 兜底。
        ids.forEach { id -> insertMessage(id, base) }

        val firstPage = chat.tail(threadId, limit = 80)
        assertEquals(80, firstPage.size)
        // 排序键 (createdAt DESC, id DESC) 后反转为正序 → id 升序的后 80 条。
        assertEquals(ids.subList(20, 100), firstPage.map { it.id })

        val cursor = firstPage.first()
        val older = chat.tailBefore(threadId, cursor.createdAt, cursor.id, limit = 60)
        assertEquals(20, older.size)
        assertEquals(ids.subList(0, 20), older.map { it.id })
        assertEquals("同毫秒下翻页同样不重不漏", ids, older.map { it.id } + firstPage.map { it.id })
    }

    @Test
    fun `mixedTimestampsWithSameMillisecondGroupsPageWithoutOverlapOrGap`() = runBlocking {
        val ids = idsOf()
        // 每 3 条共享一个毫秒：组内靠 id 兜底、组间靠 createdAt。
        ids.forEachIndexed { index, id -> insertMessage(id, base + (index / 3) * 1_000L) }

        val firstPage = chat.tail(threadId, limit = 80)
        assertEquals(ids.subList(20, 100), firstPage.map { it.id })

        val cursor = firstPage.first()
        val older = chat.tailBefore(threadId, cursor.createdAt, cursor.id, limit = 60)
        assertEquals(ids.subList(0, 20), older.map { it.id })
        assertEquals("混合毫秒分页不重不漏", ids, older.map { it.id } + firstPage.map { it.id })
    }

    @Test
    fun `chat order keeps overlapping source segments contiguous and paginates by segment`() = runBlocking {
        val originalOpen = db.segmentDao().byId(segmentId)!!
        db.segmentDao().upsert(
            originalOpen.copy(
                state = "SEALED",
                firstMessageAt = base,
                lastMessageAt = base + 6_000,
                batonReason = "IMPORT",
            ),
        )
        val sourceA = SegmentEntity(
            id = "source-segment-a", threadId = threadId, ordinal = 1, summary = null, styleNotes = null,
            firstMessageAt = base + 1_000, lastMessageAt = base + 4_000, tokenSum = 2,
            state = "SEALED", batonReason = "IMPORT", createdAt = base,
        )
        val sourceB = sourceA.copy(
            id = "source-segment-b", ordinal = 2, firstMessageAt = base + 2_000,
            lastMessageAt = base + 3_000,
        )
        val active = originalOpen.copy(id = "active-open-segment", ordinal = 3, state = "OPEN", batonReason = null)
        db.segmentDao().upsert(sourceA)
        db.segmentDao().upsert(sourceB)
        db.segmentDao().upsert(active)
        db.threadDao().setActiveSegment(threadId, active.id, active.ordinal)

        val characterId = db.threadDao().byId(threadId)!!.characterId!!
        db.importBatchDao().upsertSources(
            listOf(
                ImportSourceSessionEntity(
                    id = "source-row-a", batchId = "batch-order", targetCharacterId = characterId,
                    targetThreadId = threadId, sourceSessionId = "source-a", title = "Source A",
                    sourceStartedAt = base, messageCount = 2, segmentId = sourceA.id, indexStatus = "DONE",
                ),
                ImportSourceSessionEntity(
                    id = "source-row-b", batchId = "batch-order", targetCharacterId = characterId,
                    targetThreadId = threadId, sourceSessionId = "source-b", title = "Source B",
                    sourceStartedAt = base + 1_500, messageCount = 2, segmentId = sourceB.id, indexStatus = "DONE",
                ),
            ),
        )

        // Timestamps overlap and globally sort A/B/live out of segment order.
        insertMessage("old-1", base + 5_000)
        insertMessage("old-2", base + 6_000)
        insertMessage("a-1", base + 1_000, sourceA.id)
        insertMessage("a-2", base + 4_000, sourceA.id)
        insertMessage("b-1", base + 2_000, sourceB.id)
        insertMessage("b-2", base + 3_000, sourceB.id)
        insertMessage("live-1", base + 2_500, active.id)

        val sourceRows = db.importBatchDao().sourcesForThread(threadId).associateBy { it.segmentId }
        val allOrdered = chat.chatTailInSegmentOrder(threadId, limit = 20)
        assertEquals(listOf("old-1", "old-2", "a-1", "a-2", "b-1", "b-2", "live-1"), allOrdered.map { it.message.id })
        assertEquals(listOf(0, 0, 1, 1, 2, 2, 3), allOrdered.map { it.segmentOrdinal })
        assertEquals(
            listOf(null, null, "source-a", "source-a", "source-b", "source-b", null),
            allOrdered.map { sourceRows[it.message.segmentId]?.sourceSessionId },
        )

        val newestPage = chat.chatTailInSegmentOrder(threadId, limit = 4)
        assertEquals(listOf("a-2", "b-1", "b-2", "live-1"), newestPage.map { it.message.id })
        val olderPage = chat.chatTailBeforeInSegmentOrder(
            threadId,
            newestPage.first().segmentOrdinal,
            newestPage.first().message.createdAt,
            newestPage.first().message.id,
            limit = 2,
        )
        assertEquals(listOf("old-2", "a-1"), olderPage.map { it.message.id })
        val oldestPage = chat.chatTailBeforeInSegmentOrder(
            threadId,
            olderPage.first().segmentOrdinal,
            olderPage.first().message.createdAt,
            olderPage.first().message.id,
            limit = 2,
        )
        assertEquals(listOf("old-1"), oldestPage.map { it.message.id })
        assertEquals(
            "分段顺序翻页不重不漏",
            allOrdered.map { it.message.id },
            oldestPage.map { it.message.id } + olderPage.map { it.message.id } + newestPage.map { it.message.id },
        )
    }

    // ------------------------------------------------------------------
    // 3. 空 thread
    // ------------------------------------------------------------------

    @Test
    fun `emptyThreadPagesReturnEmptyWithoutCrash`() = runBlocking {
        assertTrue("空 thread 的 tail 应为空", chat.tail(threadId, limit = 80).isEmpty())
        assertTrue(
            "空 thread 的 tailBefore 应为空",
            chat.tailBefore(threadId, base, "m-000", limit = 60).isEmpty(),
        )
        // 不存在的 thread 也不应崩（返回空）。
        assertTrue(chat.tail("missing-thread", limit = 80).isEmpty())
        assertTrue(chat.tailBefore("missing-thread", base, "m-000", limit = 60).isEmpty())
    }
}
