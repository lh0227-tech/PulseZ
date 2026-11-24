package cc.pulsez.app.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import cc.pulsez.app.core.GroupMemoryExtractor
import cc.pulsez.app.core.GroupMemoryShard
import cc.pulsez.app.data.db.PulsezDatabase
import cc.pulsez.app.data.group.RoomGroupHistoryPort
import cc.pulsez.app.data.repo.CharacterRepository
import cc.pulsez.app.data.repo.ChatRepository
import cc.pulsez.app.data.repo.MemoryRepository
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 群聊「各自记忆」落库验收（ 记忆隔离）。
 *
 * 用真实 Room（Robolectric + 内存库）跑通完整链路：
 * 群历史 → 按角色选分片 → 抽取（Fake 模型输出）→ `MemoryRepository.applyPatch(ownerId = A)`，
 * 断言 **A 的记忆行 `characterId = A`，B 与 thread 维度一行都没有被写**。
 * 不连真机、不联网。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class GroupMemoryOwnerWriteTest {

    private lateinit var db: PulsezDatabase
    private lateinit var characters: CharacterRepository
    private lateinit var chat: ChatRepository
    private lateinit var memory: MemoryRepository
    private lateinit var historyPort: RoomGroupHistoryPort

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, PulsezDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        characters = CharacterRepository(db)
        chat = ChatRepository(db)
        memory = MemoryRepository(db)
        historyPort = RoomGroupHistoryPort(chat, characters)
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun extractionWritesOnlyTheOwningCharacterDimension() = runBlocking {
        val (a, _) = characters.createWithThread("阿甲", "温和、话不多")
        val (b, _) = characters.createWithThread("阿乙", "活泼")
        val group = chat.createGroupThread(listOf(a.id, b.id), "我们俩")
        val segmentId = requireNotNull(group.activeSegmentId)

        // 一轮群聊：用户说、A 回、B 也回（B 的台词是隔离性的关键样本）
        chat.append(group.id, segmentId, "user", "我最近养了只猫，叫豆豆", tokens = 10)
        chat.append(
            group.id,
            segmentId,
            "assistant",
            "豆豆这名字好可爱",
            tokens = 10,
            speakerCharacterId = a.id,
        )
        chat.append(
            group.id,
            segmentId,
            "assistant",
            "我家狗叫大黄，比你那只乖多了",
            tokens = 10,
            speakerCharacterId = b.id,
        )

        val shard = GroupMemoryShard.select(historyPort.history(group.id), characterId = a.id)
        var capturedPrompt: String? = null
        val patch = """{"facts":[{"k":"宠物","v":"用户养了一只叫豆豆的猫","conf":0.9,""" +
            """"valid_from":"2026-11-01"}],"events":[],"loops":[],"relation":{},""" +
            """"boundaries":[],"prefs":[]}"""

        val extractor = GroupMemoryExtractor(
            completeJson = { request ->
                capturedPrompt = request.messages[1].content
                patch
            },
            applyPatch = { json ->
                // 真实落库：写入维度固定 ownerId = A（群聊角色维度）
                memory.applyPatch(
                    threadId = group.id,
                    patchJson = json,
                    segmentId = segmentId,
                    ownerId = a.id,
                )
            },
        )
        val extracted = extractor.extract(model = "deepseek-flash", shard = shard)

        assertTrue(extracted)
        val prompt = requireNotNull(capturedPrompt)
        assertTrue("A 的抽取 prompt 必须包含用户消息", prompt.contains("我最近养了只猫，叫豆豆"))
        assertTrue("A 的抽取 prompt 必须包含 A 自己说过的话", prompt.contains("豆豆这名字好可爱"))
        assertFalse("别人的台词不能进 A 的记忆输入", prompt.contains("大黄"))
        assertFalse(prompt.contains("我家狗叫"))

        // 写入侧隔离：行 characterId = A；B 维度与 thread 维度都没有被污染。
        val rowsA = db.memoryDao().forOwner(group.id, a.id)
        assertTrue("A 的记忆必须真的落库", rowsA.isNotEmpty())
        assertTrue("所有写入行的 characterId 必须是 A", rowsA.all { it.characterId == a.id })
        assertTrue("记忆内容来自用户明说过的事实", rowsA.any { it.value.contains("豆豆") })
        assertTrue(db.memoryDao().forOwner(group.id, b.id).isEmpty())
        assertTrue("不能写 thread 维度（会串到所有角色）", db.memoryDao().forOwner(group.id, null).isEmpty())
        assertTrue(memory.activeCount(group.id, a.id) >= 1)
        assertEquals(0, memory.activeCount(group.id, b.id))
    }

    @Test
    fun repeatedExtractionForSameCharacterDoesNotTouchOtherMembers() = runBlocking {
        val (a, _) = characters.createWithThread("阿甲", "温和")
        val (b, _) = characters.createWithThread("阿乙", "活泼")
        val group = chat.createGroupThread(listOf(a.id, b.id), "我们俩")
        val segmentId = requireNotNull(group.activeSegmentId)
        chat.append(group.id, segmentId, "user", "我在杭州上班", tokens = 8)

        val firstPatch = """{"facts":[{"k":"城市","v":"用户在杭州上班","conf":0.9,""" +
            """"valid_from":"2026-11-01"}],"events":[],"loops":[],"relation":{},""" +
            """"boundaries":[],"prefs":[]}"""
        val secondPatch = """{"facts":[{"k":"城市","v":"用户在杭州上班","conf":0.9,""" +
            """"valid_from":"2026-11-01"}],"events":[],"loops":[],"relation":{},""" +
            """"boundaries":[],"prefs":[]}"""

        fun runExtraction(patch: String) = GroupMemoryExtractor(
            completeJson = { patch },
            applyPatch = { json ->
                memory.applyPatch(group.id, json, segmentId, ownerId = a.id)
            },
        )

        runBlocking {
            runExtraction(firstPatch).extract("deepseek-flash", GroupMemoryShard.select(historyPort.history(group.id), a.id))
            runExtraction(secondPatch).extract("deepseek-flash", GroupMemoryShard.select(historyPort.history(group.id), a.id))
        }

        // 同 key 同值只刷新、不重复；B 始终为空（双时态合并不影响隔离）。
        assertEquals(1, memory.activeCount(group.id, a.id))
        assertTrue(db.memoryDao().forOwner(group.id, b.id).isEmpty())
    }
}
