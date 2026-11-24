package cc.pulsez.app.core

import cc.pulsez.app.data.db.ChunkEntity
import cc.pulsez.app.data.db.ChunkTermEntity
import cc.pulsez.app.data.repo.ImportedSegment
import cc.pulsez.app.mem.BigramIndex
import cc.pulsez.app.mem.TokenEstimator
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 导入本地索引的纯 JVM 单测：不启动 Android、不联网、不碰 Room。
 *
 * 覆盖 ：切块 ≤300 token、`messageIds` 非空且顺序正确、
 * 确定性 chunkId 幂等、时间用源消息时间、无网络调用（索引器本身没有任何网络依赖）。
 * 另覆盖「单块失败不抛、空段不崩」这两条导入后处理的健壮性要求。
 *
 * 说明：这里只通过公开的 `index` 断言行为，不直接调用 `internal buildBlocks`
 * （避免把测试绑在内部实现上；块级字段就是 chunk 行本身的字段）。
 */
class ImportIndexerTest {

    // ------------------------------------------------------------------ 夹具

    private fun user(id: String, text: String, at: Long) =
        ImportMessage(id = id, role = ImportMessage.ROLE_USER, text = text, at = at)

    private fun ta(id: String, text: String, at: Long) =
        ImportMessage(id = id, role = ImportMessage.ROLE_ASSISTANT, text = text, at = at)

    private fun segment(segmentId: String, vararg messages: ImportMessage): ImportedSegment =
        ImportedSegment(
            sourceSessionId = "source-$segmentId",
            segmentId = segmentId,
            messageIds = messages.map { it.id },
            firstMessageAt = messages.firstOrNull()?.at ?: 0L,
            lastMessageAt = messages.lastOrNull()?.at ?: 0L,
        )

    /** 记录 `saveChunks` 的 Fake；可让第 N 次调用抛异常，模拟单块写失败。 */
    private class FakeSaver {
        val chunks = mutableListOf<ChunkEntity>()
        val terms = mutableListOf<ChunkTermEntity>()
        var calls = 0
        var failOnCall: Int? = null

        val save: suspend (List<ChunkEntity>, List<ChunkTermEntity>) -> Unit = { batchChunks, batchTerms ->
            calls++
            if (calls == failOnCall) throw IllegalStateException("模拟写库失败")
            chunks += batchChunks
            terms += batchTerms
        }
    }

    /**
 * 记录调用、可编排成败的向量端口 Fake。
 *
 * @param behavior 按「本批文本 + 第几次调用（从 1 开始）」返回向量；null/抛异常 = 这一批失败；
 * 不传 = 固定返回 [vectorSize] 维向量（每批内容不同，便于区分）。
 */
    private class FakeEmbed(
        private val vectorSize: Int = 4,
        private val behavior: ((texts: List<String>, call: Int) -> List<FloatArray>?)? = null,
    ) : EmbedPort {
        val calls = mutableListOf<List<String>>()

        override suspend fun embed(texts: List<String>): List<FloatArray>? {
            calls += texts
            val custom = behavior
            if (custom != null) return custom(texts, calls.size)
            return texts.mapIndexed { index, _ ->
                FloatArray(vectorSize) { position -> (index + calls.size + position + 1).toFloat() }
            }
        }

        companion object {
            /** 测试向量维度（固定值，断言 dim/vec 大小用）。 */
            const val VECTOR_DIM: Int = 4
        }
    }

    private fun newIndexer(
        messages: List<ImportMessage>,
        saver: FakeSaver,
        loadedInReverse: Boolean = false,
        embed: EmbedPort = EmbedPort { null },
        embedModel: suspend () -> String = { "" },
        embedConfigured: suspend () -> Boolean = { false },
    ): ImportIndexer {
        val byId = messages.associateBy { it.id }
        return ImportIndexer(
            loadMessages = { ids ->
                val loaded = ids.mapNotNull { byId[it] }
                if (loadedInReverse) loaded.reversed() else loaded
            },
            saveChunks = saver.save,
            embed = embed,
            embedModel = embedModel,
            embedConfigured = embedConfigured,
        )
    }

    /** 生成 [pairs] 组「user + TA」消息（顺序与时间递增）。 */
    private fun turnMessages(pairs: Int): List<ImportMessage> = buildList {
        repeat(pairs) { index ->
            add(user("u$index", "用户话 $index", 1_000L + index * 2))
            add(ta("a$index", "回应 $index", 1_001L + index * 2))
        }
    }

    // ------------------------------------------------------------------ 切块

    @Test
    fun fourMessagesBecomeTwoTurnBlocks() = runBlocking {
        val messages = listOf(
            user("m1", "用户一", 1_000L),
            ta("m2", "回应一", 2_000L),
            user("m3", "用户二", 3_000L),
            ta("m4", "回应二", 4_000L),
        )
        val saver = FakeSaver()

        val result = newIndexer(messages, saver).index("thread-1", listOf(segment("seg-1", *messages.toTypedArray())))

        assertEquals(ImportIndexer.Result(chunks = 2, skipped = 0), result)
        assertEquals(2, saver.chunks.size)
        assertEquals("m1,m2", saver.chunks[0].messageIds)
        assertEquals("m3,m4", saver.chunks[1].messageIds)
        assertEquals("你说：用户一\nTA 说：回应一", saver.chunks[0].text)
        assertEquals("你说：用户二\nTA 说：回应二", saver.chunks[1].text)
    }

    @Test
    fun chunkNeverExceedsThreeHundredTokens() = runBlocking {
        // 平假名不在 TokenEstimator 的 CJK 区，1 token/字 → 200 字各 200 token，
        // 一对就是 400+，必须拆块才可能 ≤300（保证"超了就切"真的发生）。
        val messages = listOf(
            user("u1", "あ".repeat(400), 1L),
            ta("a1", "い".repeat(400), 2L),
            user("u2", "う".repeat(50), 3L),
            ta("a2", "え".repeat(50), 4L),
        )
        val saver = FakeSaver()

        newIndexer(messages, saver).index("thread-1", listOf(segment("seg-1", *messages.toTypedArray())))

        assertTrue("至少要切出块来", saver.chunks.isNotEmpty())
        saver.chunks.forEach { chunk ->
            assertTrue(
                "单块不能超过 300 token（实际 ${TokenEstimator.estimate(chunk.text)}）",
                TokenEstimator.estimate(chunk.text) <= ImportIndexer.MAX_BLOCK_TOKENS,
            )
            assertTrue("块内 messageIds 不能为空", chunk.messageIds.isNotBlank())
        }
        // 400 字的消息对会被拆成两块，50 字的消息对仍是一块 → 共 3 块。
        assertEquals(3, saver.chunks.size)
        // 一条消息都不能丢。
        val covered = saver.chunks.flatMap { it.messageIds.split(",") }.toSet()
        assertEquals(setOf("u1", "a1", "u2", "a2"), covered)
    }

    @Test
    fun messageIdsKeepSegmentOrderEvenIfLoaderReturnsShuffled() = runBlocking {
        val messages = listOf(
            user("m1", "先说的", 1_000L),
            ta("m2", "先回的", 2_000L),
            user("m3", "后说的", 3_000L),
            ta("m4", "后回的", 4_000L),
        )
        val saver = FakeSaver()

        newIndexer(messages, saver, loadedInReverse = true)
            .index("thread-1", listOf(segment("seg-1", *messages.toTypedArray())))

        assertEquals("必须按 segment.messageIds 的源顺序，不能按读库顺序", "m1,m2", saver.chunks[0].messageIds)
        assertEquals("m3,m4", saver.chunks[1].messageIds)
        assertTrue(saver.chunks[0].text.startsWith("你说：先说的"))
    }

    @Test
    fun consecutiveSameRoleMessagesAreAllKept() = runBlocking {
        val messages = listOf(
            user("u1", "第一句", 1L),
            user("u2", "第二句", 2L),
            ta("a1", "回一句", 3L),
            ta("a2", "再补一句", 4L),
        )
        val saver = FakeSaver()

        newIndexer(messages, saver).index("thread-1", listOf(segment("seg-1", *messages.toTypedArray())))

        // u1 单独成块；u2 + a1 成对；a2 单独成块。
        assertEquals(3, saver.chunks.size)
        assertEquals(listOf("u1", "u2,a1", "a2"), saver.chunks.map { it.messageIds })
        val covered = saver.chunks.flatMap { it.messageIds.split(",") }
        assertEquals(listOf("u1", "u2", "a1", "a2"), covered)
    }

    @Test
    fun firstAndLastTimesComeFromSourceMessagesNotNow() = runBlocking {
        val messages = listOf(
            user("m1", "旧事一", 1_600_000_000_000L),
            ta("m2", "旧回应", 1_600_000_010_000L),
        )
        val saver = FakeSaver()

        newIndexer(messages, saver).index("thread-1", listOf(segment("seg-1", *messages.toTypedArray())))

        val chunk = saver.chunks.single()
        assertEquals(1_600_000_000_000L, chunk.firstMessageAt)
        assertEquals(1_600_000_010_000L, chunk.lastMessageAt)
        assertEquals(1_600_000_000_000L, chunk.createdAt)
        assertTrue("禁止用 now 顶替源时间", chunk.createdAt < System.currentTimeMillis() - 1_000_000L)
    }

    // ------------------------------------------------------------------ 幂等 / 字段

    @Test
    fun chunkIdIsDeterministicAndMatchesDocumentedFormula() = runBlocking {
        val messages = listOf(user("m1", "用户一", 1L), ta("m2", "回应一", 2L))
        val firstRun = FakeSaver()
        val secondRun = FakeSaver()

        newIndexer(messages, firstRun).index("thread-1", listOf(segment("seg-1", *messages.toTypedArray())))
        newIndexer(messages, secondRun).index("thread-1", listOf(segment("seg-1", *messages.toTypedArray())))

        val expected = UUID.nameUUIDFromBytes(
            "deepseek-chunk:thread-1:m1:m2".toByteArray(Charsets.UTF_8),
        ).toString()
        assertEquals(expected, firstRun.chunks.single().id)
        assertEquals(
            "重复索引必须得到同一个 chunkId（REPLACE 不新增行）",
            firstRun.chunks.single().id,
            secondRun.chunks.single().id,
        )
        assertEquals(expected, ImportIndexer.chunkIdFor("thread-1", "m1", "m2"))
    }

    @Test
    fun chunkFieldsAreLocalPlaceholdersWithSourceSegment() = runBlocking {
        val messages = listOf(user("m1", "你好", 5L), ta("m2", "在的", 6L))
        val saver = FakeSaver()

        newIndexer(messages, saver).index("thread-7", listOf(segment("seg-9", *messages.toTypedArray())))

        val chunk = saver.chunks.single()
        assertEquals("thread-7", chunk.threadId)
        assertEquals("seg-9", chunk.segmentId)
        assertEquals("本地索引不做 embedding：dim=0", 0, chunk.dim)
        assertArrayEquals("本地索引不做 embedding：vec 为空", ByteArray(0), chunk.vec)
        assertEquals(1f, chunk.scale, 0f)
        assertEquals(ImportIndexer.MODEL_NONE, chunk.model)
        assertNull(chunk.deletedAt)
    }

    @Test
    fun termsAreCappedAndPointAtTheirOwnChunk() = runBlocking {
        // 200 个不同汉字 → 近 200 个不同二元组，确保触到 take(60) 的上限。
        val longText = buildString { repeat(200) { append((0x4E00 + it).toChar()) } }
        val messages = listOf(user("m1", longText, 1L), ta("m2", longText, 2L))
        val saver = FakeSaver()

        newIndexer(messages, saver).index("thread-1", listOf(segment("seg-1", *messages.toTypedArray())))

        val chunk = saver.chunks.single()
        val expectedTerms = BigramIndex.terms(chunk.text).take(ImportIndexer.MAX_TERMS)
        assertEquals("每块最多 60 个 term", ImportIndexer.MAX_TERMS, expectedTerms.size)
        assertEquals(expectedTerms.toSet(), saver.terms.map { it.term }.toSet())
        saver.terms.forEach { term ->
            assertEquals("term 必须挂在自己的 chunk 上", chunk.id, term.chunkId)
            assertEquals("threadId 必须写对", "thread-1", term.threadId)
            assertEquals("倒排权重固定 1", 1, term.weight)
        }
        assertFalse("不能把其它块的 chunkId 混进来", saver.terms.any { it.chunkId != chunk.id })
    }

    // ------------------------------------------------------------------ 健壮性

    @Test
    fun singleBlockSaveFailureCountsSkippedAndKeepsGoing() = runBlocking {
        val messages = listOf(
            user("m1", "第一句", 1L),
            ta("m2", "第一回", 2L),
            user("m3", "第二句", 3L),
            ta("m4", "第二回", 4L),
        )
        val saver = FakeSaver().apply { failOnCall = 2 }

        val result = newIndexer(messages, saver)
            .index("thread-1", listOf(segment("seg-1", *messages.toTypedArray())))

        assertEquals("第二块写失败只记 skipped，不打断整次索引", 1, result.chunks)
        assertEquals(1, result.skipped)
        assertEquals("第一块已经写进去了", "m1,m2", saver.chunks.single().messageIds)
    }

    @Test
    fun emptySegmentAndEmptySegmentsDoNotCrash() = runBlocking {
        val saver = FakeSaver()
        var loaderCalls = 0
        val indexer = ImportIndexer(
            loadMessages = { ids ->
                loaderCalls++
                ids.map { ImportMessage(it, ImportMessage.ROLE_USER, "x", 0L) }
            },
            saveChunks = saver.save,
        )

        val emptySegments = indexer.index("thread-1", emptyList())
        assertEquals(ImportIndexer.Result(0, 0), emptySegments)
        assertEquals("没有段就不该去读库", 0, loaderCalls)

        val emptyIdsResult = indexer.index("thread-1", listOf(segment("seg-empty")))
        assertEquals(ImportIndexer.Result(0, 0), emptyIdsResult)
        assertEquals("空段不该去读库", 0, loaderCalls)
        assertTrue("空段也不能写 chunk", saver.chunks.isEmpty())
    }

    @Test
    fun segmentLoadFailureDoesNotThrow() = runBlocking {
        val saver = FakeSaver()
        val indexer = ImportIndexer(
            loadMessages = { throw IllegalStateException("模拟读库失败") },
            saveChunks = saver.save,
        )
        val messages = listOf(user("m1", "你好", 1L))
        var progress: Pair<Int, Int>? = null

        val result = indexer.index("thread-1", listOf(segment("seg-1", *messages.toTypedArray()))) { done, total ->
            progress = done to total
        }

        assertEquals(0, result.chunks)
        assertEquals("整段读取失败按 1 个未能索引单元计", 1, result.skipped)
        assertEquals("进度仍要走到终点", 1 to 1, progress)
    }

    @Test
    fun progressReportsEverySegment() = runBlocking {
        val messages = listOf(
            user("m1", "一", 1L),
            ta("m2", "二", 2L),
            user("m3", "三", 3L),
            ta("m4", "四", 4L),
        )
        val saver = FakeSaver()
        val calls = mutableListOf<Pair<Int, Int>>()

        newIndexer(messages, saver).index(
            threadId = "thread-1",
            segments = listOf(
                segment("seg-1", messages[0], messages[1]),
                segment("seg-2", messages[2], messages[3]),
            ),
        ) { done, total -> calls += done to total }

        assertEquals(listOf(0 to 2, 1 to 2, 2 to 2), calls)
    }

    // ------------------------------------------------------------------ 向量

    @Test
    fun unconfiguredEmbeddingNeverCallsPortAndKeepsKeywordOnly() = runBlocking {
        val messages = turnMessages(pairs = 4)
        val saver = FakeSaver()
        val embed = FakeEmbed()
        // 默认 embedConfigured = { false }：即使调用方说「同意」，也不能联网（2）。
        val result = newIndexer(
            messages = messages,
            saver = saver,
            embed = embed,
            embedModel = { "test-embed" },
        ).index("thread-1", listOf(segment("seg-1", *messages.toTypedArray())))

        assertEquals("未配置时一次向量调用都不能发", 0, embed.calls.size)
        assertEquals(4, result.chunks)
        assertEquals(0, result.embedded)
        saver.chunks.forEach { chunk ->
            assertEquals("未配置时 dim=0", 0, chunk.dim)
            assertArrayEquals(ByteArray(0), chunk.vec)
            assertEquals(ImportIndexer.MODEL_NONE, chunk.model)
        }
    }

    @Test
    fun checkpointReadFailureAbortsBeforeEmbeddingAndCanResume() = runBlocking {
        val messages = turnMessages(pairs = 1)
        val byId = messages.associateBy { it.id }
        var paidRequests = 0
        var checkpointWrites = 0
        val brokenReader = ImportIndexer(
            loadMessages = { ids -> ids.mapNotNull(byId::get) },
            saveChunks = { _, _ -> error("batch imports must use the checkpoint writer") },
            embed = EmbedPort { texts ->
                paidRequests++
                texts.map { FloatArray(4) { position -> (position + 1).toFloat() } }
            },
            embedModel = { "model-a" },
            embedConfigured = { true },
            embedConfigFingerprint = { "config-a" },
            loadBatchChunks = { _, _, _ -> throw IllegalStateException("injected checkpoint read failure") },
            saveBatchChunks = { _, _, _, _ ->
                checkpointWrites++
                true
            },
        )

        val failure = runCatching {
            brokenReader.index(
                threadId = "thread-checkpoint-read-failure",
                segments = listOf(segment("source-checkpoint-read-failure", *messages.toTypedArray())),
                batchId = "batch-checkpoint-read-failure",
            )
        }.exceptionOrNull()

        assertTrue(failure is IllegalStateException)
        assertTrue(failure?.message.orEmpty().contains("unable to read import index checkpoint"))
        assertEquals("存储读取失败时必须在付费请求前中止", 0, paidRequests)
        assertEquals("未读到检查点时不得写终态检查点", 0, checkpointWrites)

        val persisted = linkedMapOf<String, ChunkEntity>()
        val resumed = ImportIndexer(
            loadMessages = { ids -> ids.mapNotNull(byId::get) },
            saveChunks = { _, _ -> error("batch imports must use the checkpoint writer") },
            embed = EmbedPort { texts ->
                paidRequests++
                texts.map { FloatArray(4) { position -> (position + 2).toFloat() } }
            },
            embedModel = { "model-a" },
            embedConfigured = { true },
            embedConfigFingerprint = { "config-a" },
            loadBatchChunks = { _, _, ids -> ids.mapNotNull(persisted::get) },
            saveBatchChunks = { _, chunks, _, _ ->
                checkpointWrites++
                chunks.forEach { persisted[it.id] = it }
                true
            },
        ).index(
            threadId = "thread-checkpoint-read-failure",
            segments = listOf(segment("source-checkpoint-read-failure", *messages.toTypedArray())),
            batchId = "batch-checkpoint-read-failure",
        )

        assertEquals("修复读取后来源可续跑", 1, resumed.chunks)
        assertEquals(1, resumed.embedded)
        assertEquals(1, paidRequests)
        assertEquals(1, persisted.size)
        assertEquals("config-a", persisted.values.single().embeddingConfigFingerprint)
    }

    @Test
    fun invalidProviderVectorsAreSavedOnlyAsKeywordChunks() = runBlocking {
        val messages = turnMessages(pairs = 1)
        val invalidVectors = listOf(
            FloatArray(4) { Float.NaN },
            FloatArray(4) { Float.POSITIVE_INFINITY },
            FloatArray(4),
        )

        invalidVectors.forEachIndexed { index, invalidVector ->
            val byId = messages.associateBy { it.id }
            val saved = mutableListOf<ChunkEntity>()
            var calls = 0
            val result = ImportIndexer(
                loadMessages = { ids -> ids.mapNotNull(byId::get) },
                saveChunks = { chunks, _ -> saved += chunks },
                embed = EmbedPort { texts ->
                    calls++
                    texts.map { invalidVector.copyOf() }
                },
                embedModel = { "model-$index" },
                embedConfigured = { true },
                embedConfigFingerprint = { "config-$index" },
            ).index("thread-invalid-vector-$index", listOf(segment("seg-invalid-vector-$index", *messages.toTypedArray())))

            assertEquals("非法向量按既有上限重试", ImportIndexer.EMBED_MAX_RETRIES + 1, calls)
            assertEquals(1, result.chunks)
            assertEquals(0, result.embedded)
            assertEquals(0, saved.single().dim)
            assertEquals(ImportIndexer.MODEL_NONE, saved.single().model)
            assertEquals("非法向量不能留下可复用配置指纹", "", saved.single().embeddingConfigFingerprint)
        }
    }

    @Test
    fun zeroStoredCheckpointVectorIsRecomputedInsteadOfReused() = runBlocking {
        val messages = turnMessages(pairs = 1)
        val byId = messages.associateBy { it.id }
        val persisted = linkedMapOf<String, ChunkEntity>()

        ImportIndexer(
            loadMessages = { ids -> ids.mapNotNull(byId::get) },
            saveChunks = { _, _ -> },
            embed = EmbedPort { texts -> texts.map { FloatArray(4) { position -> (position + 1).toFloat() } } },
            embedModel = { "model-a" },
            embedConfigured = { true },
            embedConfigFingerprint = { "config-a" },
            loadBatchChunks = { _, _, ids -> ids.mapNotNull(persisted::get) },
            saveBatchChunks = { _, chunks, _, _ ->
                chunks.forEach { persisted[it.id] = it }
                true
            },
        ).index(
            threadId = "thread-zero-checkpoint",
            segments = listOf(segment("source-zero-checkpoint", *messages.toTypedArray())),
            batchId = "batch-zero-checkpoint",
        )
        val original = persisted.values.single()
        persisted[original.id] = original.copy(vec = ByteArray(original.dim))

        var retryRequests = 0
        val resumed = ImportIndexer(
            loadMessages = { ids -> ids.mapNotNull(byId::get) },
            saveChunks = { _, _ -> },
            embed = EmbedPort { texts ->
                retryRequests++
                texts.map { FloatArray(4) { position -> (position + 2).toFloat() } }
            },
            embedModel = { "model-a" },
            embedConfigured = { true },
            embedConfigFingerprint = { "config-a" },
            loadBatchChunks = { _, _, ids -> ids.mapNotNull(persisted::get) },
            saveBatchChunks = { _, chunks, _, _ ->
                chunks.forEach { persisted[it.id] = it }
                true
            },
        ).index(
            threadId = "thread-zero-checkpoint",
            segments = listOf(segment("source-zero-checkpoint", *messages.toTypedArray())),
            batchId = "batch-zero-checkpoint",
        )

        assertEquals("全零检查点不可直接复用", 1, retryRequests)
        assertEquals(1, resumed.embedded)
        assertTrue(persisted.values.single().vec.any { it.toInt() != 0 })
    }

    @Test
    fun batchSourceCompletionAndVectorResponseShareOneAtomicCheckpoint() = runBlocking {
        val messages = turnMessages(pairs = 3)
        val byId = messages.associateBy { it.id }
        val completionMarkers = mutableListOf<Boolean?>()
        var attemptedBlocks = 0
        val indexer = ImportIndexer(
            loadMessages = { ids -> ids.mapNotNull(byId::get) },
            saveChunks = { _, _ -> },
            embed = FakeEmbed(),
            embedModel = { "test-embed" },
            embedConfigured = { true },
            saveBatchChunks = { _, chunks, _, sourceIndexComplete ->
                completionMarkers += sourceIndexComplete
                attemptedBlocks += chunks.size
                false // One provider response's blocks must fail/commit as one transaction.
            },
        )

        val result = indexer.index(
            threadId = "thread-batch",
            segments = listOf(segment("batch-source", *messages.toTypedArray())),
            batchId = "batch-source-checkpoint",
        )

        assertEquals(1, completionMarkers.size)
        assertEquals(listOf(true), completionMarkers)
        assertEquals("一个向量响应的所有块必须作为一组持久化", 3, attemptedBlocks)
        assertEquals("检查点事务失败时整批保持可重试", 3, result.skipped)
        assertEquals(0, result.chunks)
    }

    @Test
    fun cancelledSameSourceResumeSkipsOnlyCompleteMatchingVectorBlocks() = runBlocking {
        val messages = turnMessages(pairs = 17)
        val byId = messages.associateBy { it.id }
        val persisted = linkedMapOf<String, ChunkEntity>()
        val firstRunCalls = mutableListOf<List<String>>()
        var cancelled = false
        var lastSourceCompletion: Boolean? = null
        val firstIndexer = ImportIndexer(
            loadMessages = { ids -> ids.mapNotNull(byId::get) },
            saveChunks = { _, _ -> },
            embed = EmbedPort { texts ->
                firstRunCalls += texts
                texts.map { FloatArray(4) { position -> (it.length + position + 1).toFloat() } }
            },
            embedModel = { "model-a" },
            embedConfigured = { true },
            embedConfigFingerprint = { "config-a" },
            loadBatchChunks = { _, _, ids -> ids.mapNotNull(persisted::get) },
            saveBatchChunks = { _, chunks, _, sourceComplete ->
                lastSourceCompletion = sourceComplete
                chunks.forEach { persisted[it.id] = it }
                if (persisted.size == ImportIndexer.EMBED_BATCH_SIZE) cancelled = true
                true
            },
        )
        var cancellation: CancellationException? = null
        try {
            firstIndexer.index(
                threadId = "thread-checkpoint",
                segments = listOf(segment("source-checkpoint", *messages.toTypedArray())),
                batchId = "batch-checkpoint",
                isCancelled = { cancelled },
            )
        } catch (failure: CancellationException) {
            cancellation = failure
        }

        assertTrue("取消必须在已返回请求检查点后、下一请求前生效", cancellation != null)
        assertEquals("已返回并提交的第一请求必须全部成为检查点", ImportIndexer.EMBED_BATCH_SIZE, persisted.size)
        assertTrue(persisted.values.all { it.dim == 4 })
        assertTrue(persisted.values.all { it.embeddingConfigFingerprint == "config-a" })
        assertEquals(1, firstRunCalls.size)
        assertEquals(ImportIndexer.EMBED_BATCH_SIZE, firstRunCalls.single().size)

        cancelled = false
        val retryCalls = mutableListOf<List<String>>()
        val resumed = ImportIndexer(
            loadMessages = { ids -> ids.mapNotNull(byId::get) },
            saveChunks = { _, _ -> },
            embed = EmbedPort { texts ->
                retryCalls += texts
                texts.map { FloatArray(4) { position -> (it.length + position + 2).toFloat() } }
            },
            embedModel = { "model-a" },
            embedConfigured = { true },
            embedConfigFingerprint = { "config-a" },
            loadBatchChunks = { _, _, ids -> ids.mapNotNull(persisted::get) },
            saveBatchChunks = { _, chunks, _, sourceComplete ->
                lastSourceCompletion = sourceComplete
                chunks.forEach { persisted[it.id] = it }
                true
            },
        ).index(
            threadId = "thread-checkpoint",
            segments = listOf(segment("source-checkpoint", *messages.toTypedArray())),
            batchId = "batch-checkpoint",
        )

        assertEquals("续跑仅请求缺失块", 1, retryCalls.size)
        assertEquals(1, retryCalls.single().size)
        assertTrue(retryCalls.single().single().contains("用户话 16"))
        assertEquals(17, resumed.chunks)
        assertEquals(17, resumed.embedded)
        assertEquals("最后块写成功后才提交 DONE", true, lastSourceCompletion)

        val changedConfigCalls = mutableListOf<List<String>>()
        ImportIndexer(
            loadMessages = { ids -> ids.mapNotNull(byId::get) },
            saveChunks = { _, _ -> },
            embed = EmbedPort { texts ->
                changedConfigCalls += texts
                texts.map { FloatArray(4) { position -> (it.length + position + 3).toFloat() } }
            },
            embedModel = { "model-a" },
            embedConfigured = { true },
            embedConfigFingerprint = { "config-b" },
            loadBatchChunks = { _, _, ids -> ids.mapNotNull(persisted::get) },
            saveBatchChunks = { _, chunks, _, _ ->
                chunks.forEach { persisted[it.id] = it }
                true
            },
        ).index(
            threadId = "thread-checkpoint",
            segments = listOf(segment("source-checkpoint", *messages.toTypedArray())),
            batchId = "batch-checkpoint",
        )

        assertEquals("配置指纹变化时不能复用旧向量", 2, changedConfigCalls.size)
        assertEquals(listOf(ImportIndexer.EMBED_BATCH_SIZE, 1), changedConfigCalls.map { it.size })
        assertTrue(persisted.values.all { it.embeddingConfigFingerprint == "config-b" })
    }

    @Test
    fun embedEnabledFalseSkipsPortEvenWhenConfigured() = runBlocking {
        val messages = turnMessages(pairs = 4)
        val saver = FakeSaver()
        val embed = FakeEmbed()

        val result = newIndexer(
            messages = messages,
            saver = saver,
            embed = embed,
            embedModel = { "test-embed" },
            embedConfigured = { true },
        ).index(
            threadId = "thread-1",
            segments = listOf(segment("seg-1", *messages.toTypedArray())),
            embedEnabled = false,
        )

        assertEquals("用户关掉「顺便按意思找旧事」时零调用", 0, embed.calls.size)
        assertEquals(0, result.embedded)
        assertTrue(saver.chunks.all { it.dim == 0 && it.model == ImportIndexer.MODEL_NONE })
    }

    @Test
    fun configuredEmbeddingBatchesBy16AndWritesVectors() = runBlocking {
        // 40 组一来一回 = 40 块 → 16 + 16 + 8 三批（批大小 16）。
        val messages = turnMessages(pairs = 40)
        val saver = FakeSaver()
        val embed = FakeEmbed()
        val embedProgress = mutableListOf<Pair<Int, Int>>()

        val result = newIndexer(
            messages = messages,
            saver = saver,
            embed = embed,
            embedModel = { "test-embed" },
            embedConfigured = { true },
        ).index(
            threadId = "thread-1",
            segments = listOf(segment("seg-1", *messages.toTypedArray())),
            onEmbedProgress = { done, total -> embedProgress += done to total },
        )

        assertEquals(40, result.chunks)
        assertEquals("40 块全部带向量", 40, result.embedded)
        assertEquals("每批 ≤16：16 + 16 + 8", listOf(16, 16, 8), embed.calls.map { it.size })
        assertTrue("关键词 term 仍然照写", saver.terms.isNotEmpty())
        saver.chunks.forEach { chunk ->
            assertEquals(FakeEmbed.VECTOR_DIM, chunk.dim)
            assertEquals(FakeEmbed.VECTOR_DIM, chunk.vec.size)
            assertTrue("scale 必须是有效正数（零向量才可能 1f/0）", chunk.scale.isFinite() && chunk.scale >= 0f)
            assertEquals("model 必须写用户配置的找旧事模型名", "test-embed", chunk.model)
            assertNull(chunk.deletedAt)
        }
        // 每批一次回调；分子/分母是消息条数（每块两条消息）。
        assertEquals("每批回调一次", 3, embedProgress.size)
        assertEquals(80, embedProgress.last().second)
        assertEquals(80, embedProgress.last().first)
        assertTrue("进度必须单调不减", embedProgress.zipWithNext().all { (a, b) -> b.first >= a.first })
    }

    @Test
    fun failedBatchRetriesTwiceThenSkipsButStillWritesKeywordChunks() = runBlocking {
        val messages = turnMessages(pairs = 10) // 10 块 ≤16 → 1 批
        val saver = FakeSaver()
        val embed = FakeEmbed(behavior = { _, _ -> throw IllegalStateException("模拟向量服务失败") })

        val result = newIndexer(
            messages = messages,
            saver = saver,
            embed = embed,
            embedModel = { "test-embed" },
            embedConfigured = { true },
        ).index("thread-1", listOf(segment("seg-1", *messages.toTypedArray())))

        assertEquals("首次 + 重试 2 次 = 3 次", 3, embed.calls.size)
        assertEquals("chunk 仍要写入（关键词可用）", 10, result.chunks)
        assertEquals("这批没有向量", 0, result.embedded)
        assertEquals(0, result.skipped)
        saver.chunks.forEach { chunk ->
            assertEquals(0, chunk.dim)
            assertEquals(ImportIndexer.MODEL_NONE, chunk.model)
        }
        assertTrue("关键词 term 仍然照写", saver.terms.isNotEmpty())
    }

    @Test
    fun retrySucceedsOnThirdAttempt() = runBlocking {
        val messages = turnMessages(pairs = 3)
        val saver = FakeSaver()
        // 前两次返回 null（不可用/失败），第三次才给向量。
        val embed = FakeEmbed(
            behavior = { texts, call ->
                if (call <= 2) null
                else texts.map { FloatArray(FakeEmbed.VECTOR_DIM) { position -> (position + 1).toFloat() } }
            },
        )

        val result = newIndexer(
            messages = messages,
            saver = saver,
            embed = embed,
            embedModel = { "test-embed" },
            embedConfigured = { true },
        ).index("thread-1", listOf(segment("seg-1", *messages.toTypedArray())))

        assertEquals(3, embed.calls.size)
        assertEquals("重试成功后这一批要带向量", 3, result.embedded)
        assertTrue(saver.chunks.all { it.dim == FakeEmbed.VECTOR_DIM })
    }

    @Test
    fun onlyFailedBatchIsSkippedOthersKeepVectors() = runBlocking {
        val messages = turnMessages(pairs = 18) // 18 块 → 16 + 2 两批
        val saver = FakeSaver()
        val embed = FakeEmbed(
            behavior = { texts, call ->
                if (call <= 3) throw IllegalStateException("第一批一直失败")
                texts.map { FloatArray(FakeEmbed.VECTOR_DIM) { 1f } }
            },
        )

        val result = newIndexer(
            messages = messages,
            saver = saver,
            embed = embed,
            embedModel = { "test-embed" },
            embedConfigured = { true },
        ).index("thread-1", listOf(segment("seg-1", *messages.toTypedArray())))

        assertEquals(18, result.chunks)
        assertEquals("只有第二批（2 块）带向量", 2, result.embedded)
        assertEquals("第一批 16 块 + 第二批 2 块都写入", 18, saver.chunks.size)
        assertEquals("第一批重试 2 次 + 第二批 1 次", 4, embed.calls.size)
        assertEquals("第一批 16 块维度为 0", 16, saver.chunks.count { it.dim == 0 })
        assertEquals("第二批带向量", 2, saver.chunks.count { it.dim > 0 })
    }

    @Test
    fun dimensionMismatchBatchIsTreatedAsFailure() = runBlocking {
        val messages = turnMessages(pairs = 2)
        val saver = FakeSaver()
        // 同批维度不一致：写进 DB 会让召回侧 cosine 的 require 抛，必须当失败跳过。
        val embed = FakeEmbed(
            behavior = { texts, _ ->
                texts.mapIndexed { index, _ -> FloatArray(if (index == 0) 4 else 8) { 1f } }
            },
        )

        val result = newIndexer(
            messages = messages,
            saver = saver,
            embed = embed,
            embedModel = { "test-embed" },
            embedConfigured = { true },
        ).index("thread-1", listOf(segment("seg-1", *messages.toTypedArray())))

        assertEquals(3, embed.calls.size)
        assertEquals(0, result.embedded)
        assertTrue(saver.chunks.all { it.dim == 0 })
    }

    @Test
    fun vectorChunkIdsStayDeterministicAndIdempotent() = runBlocking {
        val messages = turnMessages(pairs = 2)
        val first = FakeSaver()
        val second = FakeSaver()
        fun build(saver: FakeSaver) = newIndexer(
            messages = messages,
            saver = saver,
            embed = FakeEmbed(),
            embedModel = { "test-embed" },
            embedConfigured = { true },
        )

        build(first).index("thread-1", listOf(segment("seg-1", *messages.toTypedArray())))
        build(second).index("thread-1", listOf(segment("seg-1", *messages.toTypedArray())))

        assertEquals(first.chunks.size, second.chunks.size)
        assertEquals(
            "重复索引同一批消息必须得到同样的 chunkId（REPLACE 不新增行）",
            first.chunks.map { it.id },
            second.chunks.map { it.id },
        )
        assertEquals(ImportIndexer.chunkIdFor("thread-1", "u0", "a0"), first.chunks.first().id)
        assertEquals("带向量时也仍然是同一个确定性 id", first.chunks.first().id, second.chunks.first().id)
    }
}
