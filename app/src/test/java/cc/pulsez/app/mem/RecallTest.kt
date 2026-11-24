package cc.pulsez.app.mem

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [Recall] 的查询构造、召回门控与融合排序测试。
 *
 * 对应文档：03（查询/阈值/融合）、（不召回与降频）、（候选集）。
 */
class RecallTest {

    @Test
    fun buildQueryUsesLastTwoMessagesAndCurrent() {
        val recent = listOf(
            MemMessage.user("第一条不该出现"),
            MemMessage.user("第二条"),
            MemMessage.assistant("第三条"),
        )
        val query = Recall.buildQuery(recent, MemMessage.user("当前消息"))
        assertFalse(query.contains("第一条不该出现"))
        assertTrue(query.contains("第二条"))
        assertTrue(query.contains("第三条"))
        assertTrue(query.contains("当前消息"))
    }

    @Test
    fun buildQueryTruncatesToLastMaxChars() {
        val query = Recall.buildQuery(emptyList(), MemMessage.user("字".repeat(500)), maxChars = 300)
        assertEquals(300, query.length)
        assertEquals("", Recall.buildQuery(emptyList(), MemMessage.user("abc"), maxChars = 0))
    }

    @Test
    fun shouldRecallSkipsEmptyShortAndGreetings() {
        assertFalse(Recall.shouldRecall("", 0))
        assertFalse(Recall.shouldRecall("   ", 0))
        assertFalse(Recall.shouldRecall("在吗", 0))
        assertFalse(Recall.shouldRecall("你好呀", 0))
        assertFalse(Recall.shouldRecall("！！！", 0))
        assertFalse(Recall.shouldRecall("😀😀😀", 0))
        assertTrue(Recall.shouldRecall("你还记得我们上次说过的那个面试吗", 0))
    }

    @Test
    fun shouldRecallDecaysAfterThreeQuietTurns() {
        val text = "你还记得我们上次说过的那个面试吗"
        // 前 3 轮正常召回
        assertTrue(Recall.shouldRecall(text, 0))
        assertTrue(Recall.shouldRecall(text, 1))
        assertTrue(Recall.shouldRecall(text, 2))
        // 连续 3 轮无新话题后降频：每 2 轮召回一次
        assertFalse(Recall.shouldRecall(text, 3))
        assertTrue(Recall.shouldRecall(text, 4))
        assertFalse(Recall.shouldRecall(text, 5))
        assertTrue(Recall.shouldRecall(text, 6))
    }

    @Test
    fun rankFusesVectorAndKeyword() {
        val queryVec = floatArrayOf(1f, 0f, 0f, 0f, 0f, 0f, 0f, 0f)
        fun vec(vararg values: Float): QuantizedVector = VectorMath.quantizeInt8(values)
        val candidates = listOf(
            Candidate(
                id = "close",
                text = "杭州下雨了",
                vec = vec(1f, 0f, 0f, 0f, 0f, 0f, 0f, 0f),
                lastMessageAt = 3_000L,
                terms = setOf("杭州", "州下", "下雨"),
            ),
            Candidate(
                id = "far",
                text = "今天吃了面",
                vec = vec(0f, 1f, 0f, 0f, 0f, 0f, 0f, 0f),
                lastMessageAt = 2_000L,
                terms = setOf("今天", "天吃", "吃了"),
            ),
            Candidate(
                id = "mid",
                text = "下雨",
                vec = vec(0.7f, 0.7f, 0f, 0f, 0f, 0f, 0f, 0f),
                lastMessageAt = 1_000L,
                terms = setOf("下雨"),
            ),
        )
        val ranked = Recall.rank(candidates, queryVec, setOf("杭州", "下雨"), topK = 2, threshold = 0.35f)
        assertEquals(2, ranked.size)
        assertEquals("close", ranked[0].id)
        assertEquals("mid", ranked[1].id)
    }

    @Test
    fun rankFallsBackToKeywordOnlyWithoutVector() {
        val candidates = listOf(
            Candidate(id = "a", text = "杭州的雨天", terms = setOf("杭州", "州的", "的雨")),
            Candidate(id = "b", text = "上海出差", terms = setOf("上海", "海出", "出差")),
            Candidate(id = "c", text = "杭州", terms = setOf("杭州")),
        )
        val ranked = Recall.rank(candidates, null, setOf("杭州", "的雨"), topK = 2)
        assertEquals(listOf("a", "c"), ranked.map { it.id })
    }

    @Test
    fun rankAppliesTimeDecay() {
        val day = 86_400_000L
        val reference = 1_700_000_000_000L
        val candidateVec = VectorMath.quantizeInt8(floatArrayOf(1f, 0f, 0f, 0f))
        val candidates = listOf(
            Candidate(
                id = "old",
                text = "同样内容",
                vec = candidateVec,
                lastMessageAt = reference - 180 * day,
                terms = setOf("同样"),
            ),
            Candidate(
                id = "new",
                text = "同样内容",
                vec = candidateVec,
                lastMessageAt = reference,
                terms = setOf("同样"),
            ),
        )
        val ranked = Recall.rank(
            candidates,
            floatArrayOf(1f, 0f, 0f, 0f),
            setOf("同样"),
            topK = 2,
        )
        assertEquals("new", ranked[0].id)
    }

    @Test
    fun rankDropsCandidatesBelowCosineThreshold() {
        val candidate = Candidate(
            id = "noisy",
            text = "完全无关的内容",
            vec = VectorMath.quantizeInt8(floatArrayOf(0f, 1f, 0f, 0f)),
            lastMessageAt = 1L,
            terms = setOf("完全", "全无", "无关"),
        )
        val ranked = Recall.rank(
            listOf(candidate),
            floatArrayOf(1f, 0f, 0f, 0f),
            setOf("杭州"),
            topK = 6,
            threshold = 0.35f,
        )
        assertTrue(ranked.isEmpty())
    }

    // ------------------------------------------------- 阻断 ：维度混用不崩

    @Test
    fun rankWithQueryVecAcceptsNullVecCandidateAndUsesKeywordScore() {
        // 典型混合集：用户配了向量服务（queryVec 非空），候选里既有带向量的聊天块，
        // 也有 vec=null 的导入块（dim=0）。导入块必须只走关键词，且不抛。
        val queryVec = FloatArray(8) { 0f }
        queryVec[0] = 1f
        val candidates = listOf(
            Candidate(
                id = "chat",
                text = "杭州下雨了",
                vec = VectorMath.quantizeInt8(floatArrayOf(1f, 0f, 0f, 0f, 0f, 0f, 0f, 0f)),
                lastMessageAt = 2_000L,
                terms = setOf("杭州"),
            ),
            Candidate(
                id = "imported",
                text = "旧聊天里也说过杭州",
                vec = null,
                lastMessageAt = 1_000L,
                terms = setOf("杭州"),
            ),
        )
        val ranked = Recall.rank(candidates, queryVec, setOf("杭州"), topK = 6, threshold = 0.35f)
        // 不抛异常是主断言；导入块靠关键词仍可被召回（不能因为没向量就整条丢掉）。
        assertEquals(listOf("chat", "imported"), ranked.map { it.id })
    }

    @Test
    fun rankSkipsVectorScoreWhenCandidateDimMismatchesQuery() {
        // 第二道防线：即使调用方漏做转换、把 dim=0 / dim=3 的 QuantizedVector 塞进候选，
        // rank 也必须跳过向量分（关键词分照常）而不是让 VectorMath.cosine 的 require 抛。
        val queryVec = FloatArray(8) { 0f }
        queryVec[0] = 1f
        val candidates = listOf(
            Candidate(
                id = "legacy-dim0",
                text = "杭州",
                vec = QuantizedVector(dim = 0, bytes = ByteArray(0), scale = 1f),
                lastMessageAt = 3_000L,
                terms = setOf("杭州"),
            ),
            Candidate(
                id = "legacy-dim3",
                text = "杭州下雨",
                vec = QuantizedVector(dim = 3, bytes = ByteArray(3) { 1 }, scale = 1f),
                lastMessageAt = 2_000L,
                terms = setOf("杭州", "下雨"),
            ),
            Candidate(
                id = "no-keyword-hit",
                text = "上海出差",
                vec = QuantizedVector(dim = 0, bytes = ByteArray(0), scale = 1f),
                lastMessageAt = 1_000L,
                terms = setOf("上海", "出差"),
            ),
        )
        val ranked = Recall.rank(
            candidates,
            queryVec,
            setOf("杭州", "下雨"),
            topK = 6,
            threshold = 0.35f,
        )
        assertFalse("关键词命中的异维候选不能被丢掉", ranked.isEmpty())
        assertEquals(setOf("legacy-dim0", "legacy-dim3"), ranked.map { it.id }.toSet())
    }
}
