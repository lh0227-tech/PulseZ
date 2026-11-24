package cc.pulsez.app.core

import cc.pulsez.app.data.db.ChunkEntity
import cc.pulsez.app.data.db.SegmentEntity
import cc.pulsez.app.data.db.ThreadEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 阻断越权写入持久层到召回候选的路径，并检查交接并发。
 *
 * **纯 JVM**（不启动 Robolectric / Room / AppContainer）：`ChunkEntity` / `ThreadEntity` /
 * `SegmentEntity` 是普通 data class（Room 注解只在编译期给 KSP 用），
 * `ChatEngine.chunkToCandidate` / `canSealBatonTarget` 是 companion 纯函数。
 *
 *
 * - （导入索引不做 embedding：`dim=0 / vec 空`）；
 * - （向量召回维度混用崩溃）、 （交接旧快照）。
 */
class ChatEngineRecallGuardTest {

    // ------------------------------------------------------------ chunkToCandidate

    private fun chunk(
        id: String = "chunk-1",
        dim: Int = 0,
        vec: ByteArray = ByteArray(0),
        scale: Float = 1f,
        text: String = "你说：杭州下雨了\nTA 说：记得带伞",
        lastMessageAt: Long = 1_700_000_000_000L,
    ): ChunkEntity = ChunkEntity(
        id = id,
        threadId = "t-1",
        segmentId = "seg-1",
        text = text,
        firstMessageAt = lastMessageAt - 1_000L,
        lastMessageAt = lastMessageAt,
        messageIds = "u,a",
        dim = dim,
        vec = vec,
        scale = scale,
        model = if (dim > 0) "bge-m3" else "none",
        createdAt = lastMessageAt,
        deletedAt = null,
    )

    @Test
    fun importedChunkWithZeroDimBecomesNullVecCandidate() {
        // ImportIndexer 写出的导入块：dim=0 / vec 空 / model="none"。
        // 修复前这里会构造 QuantizedVector(dim=0)，配置向量服务后每轮 prepare 必崩。
        val candidate = ChatEngine.chunkToCandidate(chunk(dim = 0, vec = ByteArray(0)))

        assertNull("dim=0 的导入块必须 vec=null（只走关键词）", candidate.vec)
        assertEquals("chunk-1", candidate.id)
        assertEquals("你说：杭州下雨了\nTA 说：记得带伞", candidate.text)
        assertEquals(1_700_000_000_000L, candidate.lastMessageAt)
        assertTrue("token 估算仍要算（注入预算用）", candidate.tokens > 0)
    }

    @Test
    fun vectorChunkKeepsQuantizedVector() {
        val vec = ByteArray(8) { index -> (index + 1).toByte() }
        val candidate = ChatEngine.chunkToCandidate(
            chunk(dim = 8, vec = vec, scale = 0.25f, text = "普通聊天索引块"),
        )

        // 带向量的聊天块必须保留向量（向量召回是一级能力）；null 时 requireNotNull 直接失败。
        val quantized = requireNotNull(candidate.vec)
        assertEquals(8, quantized.dim)
        assertEquals(0.25f, quantized.scale, 0f)
        assertTrue(vec.contentEquals(quantized.bytes))
    }

    @Test
    fun positiveDimButEmptyVecIsDegradedToKeywordOnly() {
        // 防御脏数据/历史行：dim>0 但 vec 为空时同样不能构造向量（否则 cosine 索引越界）。
        val candidate = ChatEngine.chunkToCandidate(chunk(dim = 8, vec = ByteArray(0)))

        assertNull("vec 为空时即使 dim>0 也必须降级为关键词候选", candidate.vec)
    }

    // ------------------------------------------------------------ canSealBatonTarget

    private fun thread(
        id: String = "t-1",
        activeSegmentId: String? = "seg-2",
        segmentOrdinal: Int = 2,
    ): ThreadEntity = ThreadEntity(
        id = id,
        characterId = "c-1",
        title = null,
        createdAt = 0L,
        lastMessageAt = 0L,
        activeSegmentId = activeSegmentId,
        segmentOrdinal = segmentOrdinal,
        totalMessages = 0,
        totalTokens = 0L,
    )

    private fun segment(
        id: String,
        ordinal: Int,
        state: String,
        threadId: String = "t-1",
    ): SegmentEntity = SegmentEntity(
        id = id,
        threadId = threadId,
        ordinal = ordinal,
        summary = null,
        styleNotes = null,
        firstMessageAt = 0L,
        lastMessageAt = 0L,
        tokenSum = 0,
        state = state,
        batonReason = null,
        createdAt = 0L,
    )

    @Test
    fun latestOpenSegmentPointedByActiveIsSafeToSeal() {
        val target = segment(id = "seg-2", ordinal = 2, state = "OPEN")
        assertTrue(
            "active 指向的最新 OPEN 段是唯一合法封段目标",
            ChatEngine.canSealBatonTarget(thread(), target),
        )
    }

    @Test
    fun missingActiveSegmentIsNotSafeToSeal() {
        assertFalse(ChatEngine.canSealBatonTarget(thread(activeSegmentId = null), segment("seg-2", 2, "OPEN")))
        assertFalse("thread 没有段时 latest 为 null", ChatEngine.canSealBatonTarget(thread(), null))
    }

    @Test
    fun staleSnapshotWhereLatestIsNotActiveIsRejected() {
        // 后台导入推进过：最新段换成 seg-3，但调用方手里还是 seg-2 → 必须放弃本轮。
        val stale = segment(id = "seg-2", ordinal = 2, state = "OPEN")
        val threadPointingAtNewer = thread(activeSegmentId = "seg-3", segmentOrdinal = 3)
        assertFalse(ChatEngine.canSealBatonTarget(threadPointingAtNewer, stale))
    }

    @Test
    fun alreadySealedSegmentIsNotSafeToSeal() {
        // 并发交接抢先封过：即使 id 还是 active，也不能再封一次（会丢新段/重复 ordinal）。
        val sealed = segment(id = "seg-2", ordinal = 2, state = "SEALED")
        assertFalse(ChatEngine.canSealBatonTarget(thread(), sealed))
    }
}
