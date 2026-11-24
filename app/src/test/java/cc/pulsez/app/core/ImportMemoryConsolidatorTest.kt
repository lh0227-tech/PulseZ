package cc.pulsez.app.core

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import cc.pulsez.app.data.db.PulsezDatabase
import cc.pulsez.app.data.repo.ImportedSegment
import cc.pulsez.app.data.repo.MemoryRepository
import cc.pulsez.app.data.settings.Consolidation
import cc.pulsez.app.mem.BatonPolicy
import cc.pulsez.app.mem.MemMessage
import cc.pulsez.app.mem.MemoryExtractionPrompt
import cc.pulsez.app.mem.MemoryStatus
import cc.pulsez.app.mem.TokenEstimator
import kotlinx.coroutines.runBlocking
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
 * 导入记忆整理的纯 JVM 单测：模型、写库、记账、进度全部是注入的 Fake。
 *
 * 覆盖 ：90 天过滤、40k 分片、每片一次 `completeJson`、
 * `applyPatch` 拿到 JSON、失败片不崩且计数、续跑不重复、用量每片一次、可停止。
 *
 * 时间基准用**固定常量**而不是 `System.currentTimeMillis`，避免 90 天窗口断言随运行日期漂移。
 */
class ImportMemoryConsolidatorTest {

    private val threadId = "thread-1"
    private val base = 1_700_000_000_000L

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

    /** 两条消息 = 一片（远小于 40k）；用于只关心调用次数的用例。 */
    private fun smallMessages(): List<ImportMessage> = listOf(
        user("m1", "你好", 1L),
        ta("m2", "在的", 2L),
    )

    /** 10 条 × 7004 token = 3 片（35020 / 35020 / 28016 token，见下）；用于分片/费用用例。 */
    private fun tenLongMessages(): List<ImportMessage> = buildList {
        repeat(10) { index ->
            val text = "啊".repeat(10_000)
            add(
                if (index % 2 == 0) {
                    user("m$index", text, base + index)
                } else {
                    ta("m$index", text, base + index)
                },
            )
        }
    }

    private fun tenLongShardTokens(): List<Int> = listOf(35_020, 35_020, 28_016)

    /** 默认估价口径：输入 + 输出都按 ¥1 / 百万 token（只为算式可验，不是真实价格）。 */
    private fun linearCost(input: Int, output: Int): Double = (input + output) / 1_000_000.0

    /**
 * 注入全部 IO 的 Fake 组合，并用一个可变字段模拟 `ImportPrefs.consolidation` 的断点，
 * 方便断言「失败后断点保留 / 第二次只重试失败片」这类跨 run 行为。
 *
 * @param jsonFor 第 n 次（0 起，跨 run 累计）`completeJson` 的返回值；null = 未配置/失败
 * @param initialProgress 第一次 run 前已有的断点（null = 没有进度）
 */
    private inner class Harness(
        messages: List<ImportMessage>,
        private val jsonFor: suspend (Int) -> String? = { "{}" },
        initialProgress: Consolidation? = null,
        private val cost: (Int, Int) -> Double = { input, output -> linearCost(input, output) },
    ) {
        private val byId = messages.associateBy { it.id }
        val completeRequests = mutableListOf<Pair<String, String>>()
        val patches = mutableListOf<Pair<String, String>>()
        val usages = mutableListOf<Pair<Int, Int>>()
        val savedProgress = mutableListOf<Consolidation>()
        val cleared = mutableListOf<String>()
        val costCalls = mutableListOf<Pair<Int, Int>>()

        /** 模拟 DataStore 里的断点（run 之间共享；save/clear 会改它）。 */
        var stored: Consolidation? = initialProgress
            private set

        val unit = ImportMemoryConsolidator(
            loadMessages = { ids -> ids.mapNotNull { byId[it] } },
            applyPatch = { id, json ->
                patches += id to json
                true
            },
            completeJson = { system, prompt ->
                completeRequests += system to prompt
                jsonFor(completeRequests.size - 1)
            },
            recordUsage = { input, output -> usages += input to output },
            costOf = { input, output ->
                costCalls += input to output
                cost(input, output)
            },
            loadProgress = { id -> stored?.takeIf { it.threadId == id } },
            saveProgress = { state ->
                savedProgress += state
                stored = state
            },
            clearProgress = { id ->
                cleared += id
                if (stored?.threadId == id) stored = null
            },
        )
    }

    /**
 * 把 `completeJson` 的请求 prompt 映射回分片下标（纯函数，ALL 范围用例专用）。
 *
 * 用 prompt 精确匹配来断言「调用了哪一片」，比只数次数更能防「done 片被重调」的回归。
 */
    private fun requestedShardIndexes(
        messages: List<ImportMessage>,
        requests: List<Pair<String, String>>,
    ): List<Int> {
        val mem = messages.sortedBy { it.at }
            .map { MemMessage(it.role, it.text, it.at) }
        val prompts = BatonPolicy.shard(
            messages = mem,
            shardTokens = ImportMemoryConsolidator.SHARD_TOKENS,
            overlap = ImportMemoryConsolidator.SHARD_OVERLAP,
        ).map { MemoryExtractionPrompt.buildUser(it) }
        return requests.map { (_, prompt) -> prompts.indexOf(prompt) }
    }

    /** 期望的断点：只有指定分片成功/失败，其余未尝试。 */
    private fun progressOf(
        done: Set<Int>,
        failed: Set<Int>,
        total: Int,
    ): Consolidation = Consolidation(
        threadId = threadId,
        doneShards = done,
        failedShards = failed,
        totalShards = total,
    )

    // ------------------------------------------------------------------ plan

    @Test
    fun planLast90DaysIncludesExactCutoffAndExcludesOneMsEarlier() = runBlocking {
        val now = 1_800_000_000_000L
        val cutoff = now - ImportMemoryConsolidator.NINETY_DAYS_MILLIS
        val messages = listOf(
            user("before", "差 1 毫秒", cutoff - 1),
            user("edge", "恰好 90 天整", cutoff),
            user("inside", "窗口内", now - 1_000L),
            user("ancient", "更早", cutoff - 100_000L),
        )
        val segments = listOf(segment("seg-1", *messages.toTypedArray()))
        val harness = Harness(messages)

        val within = harness.unit.plan(threadId, segments, ConsolidateRange.LAST_90_DAYS, now = now)
        val all = harness.unit.plan(threadId, segments, ConsolidateRange.ALL, now = now)

        assertEquals("边界（now-90d）算窗口内，差 1ms 不算", 2, within.messageCount)
        assertEquals(4, all.messageCount)
        assertTrue(harness.completeRequests.isEmpty())
        assertTrue("plan 只是估算，不能写记忆", harness.patches.isEmpty())
        assertTrue(harness.savedProgress.isEmpty())
    }

    @Test
    fun planCountsShardsInputTokensAndEstimatedCost() = runBlocking {
        val messages = tenLongMessages()
        val segments = listOf(segment("seg-1", *messages.toTypedArray()))
        val harness = Harness(messages)

        val plan = harness.unit.plan(threadId, segments, ConsolidateRange.ALL, now = base)

        assertEquals("10 × 7004 token 按 40k 分片 = 3 片", 3, plan.shards)
        assertEquals(10, plan.messageCount)
        assertEquals(tenLongShardTokens().sum(), plan.inputTokens)
        assertEquals(plan.inputTokens + plan.shards * ImportMemoryConsolidator.OUTPUT_TOKEN_ASSUMPTION, 104_056)
        // 每片都按「片 token + 2000 输出上限假设」估价一次。
        assertEquals(tenLongShardTokens(), harness.costCalls.map { it.first })
        assertTrue(harness.costCalls.all { it.second == ImportMemoryConsolidator.OUTPUT_TOKEN_ASSUMPTION })
        assertEquals(104_056 / 1_000_000.0, plan.estimatedYuan, 1e-12)
        assertTrue("plan 不得发起模型调用", harness.completeRequests.isEmpty())
    }

    @Test
    fun planEstimatesAllShardsEvenWhenResumeProgressExists() = runBlocking {
        // ：plan 是「全部片」的上限估价，不扣续跑断点（UI 确认不少报）。
        val messages = tenLongMessages()
        val segments = listOf(segment("seg-1", *messages.toTypedArray()))
        val harness = Harness(
            messages,
            initialProgress = progressOf(done = setOf(0, 1), failed = emptySet(), total = 3),
        )

        val plan = harness.unit.plan(threadId, segments, ConsolidateRange.ALL, now = base)

        assertEquals("估价按全部片，不扣已完成的片", 3, plan.shards)
        assertEquals(104_056 / 1_000_000.0, plan.estimatedYuan, 1e-12)
        assertTrue(harness.completeRequests.isEmpty())
    }

    // ------------------------------------------------------------------ run：正常路径

    @Test
    fun runCallsModelOncePerShardAndWritesEveryPatch() = runBlocking {
        val messages = tenLongMessages()
        val segments = listOf(segment("seg-1", *messages.toTypedArray()))
        val harness = Harness(messages)
        val plan = harness.unit.plan(threadId, segments, ConsolidateRange.ALL, now = base)
        val progress = mutableListOf<Pair<Int, Int>>()

        val result = harness.unit.run(
            threadId = threadId,
            segments = segments,
            range = ConsolidateRange.ALL,
            plan = plan,
            progress = { done, total -> progress += done to total },
        )

        assertEquals(3, harness.completeRequests.size)
        assertEquals("每片一次 completeJson", 3, harness.patches.size)
        assertTrue(harness.patches.all { it.first == threadId })
        assertEquals("每片一次记账", 3, harness.usages.size)
        assertEquals(
            listOf(
                progressOf(setOf(0), emptySet(), 3),
                progressOf(setOf(0, 1), emptySet(), 3),
                progressOf(setOf(0, 1, 2), emptySet(), 3),
            ),
            harness.savedProgress,
        )
        assertEquals("全部跑完才清进度", listOf(threadId), harness.cleared)
        assertEquals(listOf(0 to 3, 1 to 3, 2 to 3, 3 to 3), progress)
        assertEquals(3, result.shardsTotal)
        assertEquals(3, result.shardsDone)
        assertEquals(0, result.failed)
        assertFalse(result.cancelled)
        assertEquals("全部完成时费用 = plan 的估算", plan.estimatedYuan, result.estimatedYuan, 1e-12)
    }

    @Test
    fun completeJsonReceivesMemoryExtractionPrompts() = runBlocking {
        val messages = smallMessages()
        val segments = listOf(segment("seg-1", *messages.toTypedArray()))
        val harness = Harness(messages)
        val plan = harness.unit.plan(threadId, segments, ConsolidateRange.ALL, now = base)

        harness.unit.run(threadId, segments, ConsolidateRange.ALL, plan)

        val (system, prompt) = harness.completeRequests.single()
        assertEquals(MemoryExtractionPrompt.buildSystem(), system)
        assertTrue(prompt.contains("以下是需要抽取的对话原文"))
        assertTrue("用户原话要进 prompt", prompt.contains("你好"))
        assertTrue("TA 的话也进 prompt（但抽取侧只写用户事实）", prompt.contains("在的"))
    }

    @Test
    fun applyPatchReceivesTheModelJson() = runBlocking {
        val json = """{"facts":[{"k":"城市","v":"杭州","conf":0.9}],"events":[],"loops":[],"relation":{},"boundaries":[],"prefs":[]}"""
        val harness = Harness(smallMessages(), jsonFor = { json })
        val segments = listOf(segment("seg-1", *smallMessages().toTypedArray()))
        val plan = harness.unit.plan(threadId, segments, ConsolidateRange.ALL, now = base)

        harness.unit.run(threadId, segments, ConsolidateRange.ALL, plan)

        assertEquals(listOf(threadId to json), harness.patches)
    }

    @Test
    fun usageIsRecordedOncePerShardWithEstimatedTokens() = runBlocking {
        val messages = smallMessages()
        val segments = listOf(segment("seg-1", *messages.toTypedArray()))
        val harness = Harness(messages, jsonFor = { "{}" })
        val plan = harness.unit.plan(threadId, segments, ConsolidateRange.ALL, now = base)

        harness.unit.run(threadId, segments, ConsolidateRange.ALL, plan)

        val expectedInput = TokenEstimator.estimateMessages(
            listOf(
                MemMessage(MemMessage.ROLE_USER, "你好", 1L),
                MemMessage(MemMessage.ROLE_ASSISTANT, "在的", 2L),
            ),
        )
        assertEquals(listOf(expectedInput to TokenEstimator.estimate("{}")), harness.usages)
    }

    // ------------------------------------------------------------------ run：失败 / 续跑 / 取消

    @Test
    fun failedShardKeepsProgressAndSecondRunRetriesOnlyThatShard() = runBlocking {
        val messages = tenLongMessages()
        val segments = listOf(segment("seg-1", *messages.toTypedArray()))
        // 第 1 次叫模型（分片 0）成功，第 2 次（分片 1）失败，第 3 次（分片 2）成功。
        val harness = Harness(messages, jsonFor = { call -> if (call == 1) null else "{}" })
        val plan = harness.unit.plan(threadId, segments, ConsolidateRange.ALL, now = base)

        val first = harness.unit.run(threadId, segments, ConsolidateRange.ALL, plan)

        // 失败片不算「成功」：进度停在 2/3，断点保留（修复前这里会 clearProgress）。
        assertEquals(3, first.shardsTotal)
        assertEquals("失败片不计入成功片数", 2, first.shardsDone)
        assertEquals(1, first.failed)
        assertFalse(first.cancelled)
        assertEquals("失败片不写记忆，其余片照写", 2, harness.patches.size)
        assertEquals("失败片不记账", 2, harness.usages.size)
        assertEquals("失败后不能清进度", emptyList<String>(), harness.cleared)
        assertEquals(
            "断点：成功片 0/2 + 失败片 1",
            progressOf(setOf(0, 2), setOf(1), 3),
            harness.stored,
        )
        assertEquals(
            "本次只按成功片折算花费（失败片不记账）",
            37_020 / 1_000_000.0 + 30_016 / 1_000_000.0,
            first.estimatedYuan,
            1e-12,
        )

        // 第二次 run：模拟用户再点「整理往事」（模型恢复）。只允许重试失败片 1。
        val callsBefore = harness.completeRequests.size
        val second = harness.unit.run(threadId, segments, ConsolidateRange.ALL, plan)

        val secondRunRequests = harness.completeRequests.drop(callsBefore)
        assertEquals("第二次只调一次模型（失败片 1）", 1, secondRunRequests.size)
        assertEquals(
            "只重试失败片，已成功的 0/2 绝不重调",
            listOf(1),
            requestedShardIndexes(messages, secondRunRequests),
        )
        assertEquals("第二次只补写一片记忆", 3, harness.patches.size)
        assertEquals("第二次只记一片用量", 3, harness.usages.size)
        assertEquals("第二次只按失败片折算花费（不重复记账）", (35_020 + 2_000) / 1_000_000.0, second.estimatedYuan, 1e-12)
        assertEquals(3, second.shardsTotal)
        assertEquals(3, second.shardsDone)
        assertEquals(0, second.failed)
        assertEquals("全部成功后才清进度", listOf(threadId), harness.cleared)
        assertNull("清进度后断点为 null", harness.stored)
    }

    @Test
    fun doneShardsAreNeverReCalled() = runBlocking {
        val messages = tenLongMessages()
        val segments = listOf(segment("seg-1", *messages.toTypedArray()))
        // 断点：0/2 成功、1 失败 → 只应重试 1。
        val harness = Harness(
            messages,
            initialProgress = progressOf(done = setOf(0, 2), failed = setOf(1), total = 3),
        )
        val plan = harness.unit.plan(threadId, segments, ConsolidateRange.ALL, now = base)
        val progress = mutableListOf<Pair<Int, Int>>()

        val result = harness.unit.run(
            threadId = threadId,
            segments = segments,
            range = ConsolidateRange.ALL,
            plan = plan,
            progress = { done, total -> progress += done to total },
        )

        assertEquals("已成功片一次都不调", 1, harness.completeRequests.size)
        assertEquals(listOf(1), requestedShardIndexes(messages, harness.completeRequests))
        assertEquals("首帧进度是断点，不是 0", listOf(2 to 3, 3 to 3), progress)
        assertEquals(3, result.shardsDone)
        assertEquals(0, result.failed)
        assertEquals(listOf(threadId), harness.cleared)
    }

    @Test
    fun allNullFromModelKeepsFailedShardsForRetry() = runBlocking {
        val messages = tenLongMessages()
        val segments = listOf(segment("seg-1", *messages.toTypedArray()))
        // 未配置 Key / 模型全失败：completeJson 一律 null。
        val harness = Harness(messages, jsonFor = { null })
        val plan = harness.unit.plan(threadId, segments, ConsolidateRange.ALL, now = base)

        val result = harness.unit.run(threadId, segments, ConsolidateRange.ALL, plan)

        assertEquals(3, result.failed)
        assertEquals("失败片不算成功片数", 0, result.shardsDone)
        assertEquals(0.0, result.estimatedYuan, 1e-12)
        assertTrue("null 不得写记忆", harness.patches.isEmpty())
        assertTrue("null 不得记账", harness.usages.isEmpty())
        assertEquals("每片仍尝试过一次", 3, harness.completeRequests.size)
        assertEquals("全部失败也保留断点（下次只重试失败片）", emptyList<String>(), harness.cleared)
        assertEquals(
            "失败片全部进 failedShards",
            progressOf(emptySet(), setOf(0, 1, 2), 3),
            harness.stored,
        )
    }

    @Test
    fun resumeFromSavedProgressOnlyRunsRemainingShards() = runBlocking {
        val messages = tenLongMessages()
        val segments = listOf(segment("seg-1", *messages.toTypedArray()))
        val harness = Harness(
            messages = messages,
            jsonFor = { "{}" },
            initialProgress = progressOf(done = setOf(0, 1), failed = emptySet(), total = 3),
        )
        val plan = harness.unit.plan(threadId, segments, ConsolidateRange.ALL, now = base)

        val result = harness.unit.run(threadId, segments, ConsolidateRange.ALL, plan)

        assertEquals("只跑第 3 片", 1, harness.completeRequests.size)
        assertEquals(listOf(2), requestedShardIndexes(messages, harness.completeRequests))
        assertEquals(1, harness.patches.size)
        assertEquals(listOf(progressOf(setOf(0, 1, 2), emptySet(), 3)), harness.savedProgress)
        assertEquals(listOf(threadId), harness.cleared)
        assertEquals(3, result.shardsTotal)
        assertEquals(3, result.shardsDone)
        assertEquals(0, result.failed)
        assertFalse(result.cancelled)
        assertEquals((28_016 + 2_000) / 1_000_000.0, result.estimatedYuan, 1e-12)
    }

    @Test
    fun mismatchedTotalStartsOverInsteadOfSkippingWrongShards() = runBlocking {
        val messages = tenLongMessages()
        val segments = listOf(segment("seg-1", *messages.toTypedArray()))
        // 旧进度是 5 片（消息集合变了）：旧索引不再对应新分片，不能硬续，否则会漏片。
        val harness = Harness(
            messages,
            initialProgress = progressOf(done = setOf(0, 1), failed = emptySet(), total = 5),
        )
        val plan = harness.unit.plan(threadId, segments, ConsolidateRange.ALL, now = base)

        val result = harness.unit.run(threadId, segments, ConsolidateRange.ALL, plan)

        assertEquals("分片数变了：从头整理全部 3 片", 3, harness.completeRequests.size)
        assertEquals(listOf(0, 1, 2), requestedShardIndexes(messages, harness.completeRequests))
        assertEquals(3, result.shardsDone)
        assertEquals(listOf(threadId), harness.cleared)
    }

    @Test
    fun cancelStopsMidwayAndKeepsProgress() = runBlocking {
        val messages = tenLongMessages()
        val segments = listOf(segment("seg-1", *messages.toTypedArray()))
        val harness = Harness(messages)
        val plan = harness.unit.plan(threadId, segments, ConsolidateRange.ALL, now = base)
        val progress = mutableListOf<Pair<Int, Int>>()

        val result = harness.unit.run(
            threadId = threadId,
            segments = segments,
            range = ConsolidateRange.ALL,
            plan = plan,
            progress = { done, total -> progress += done to total },
            isCancelled = { harness.completeRequests.isNotEmpty() },
        )

        assertTrue(result.cancelled)
        assertEquals("只跑完第 1 片", 1, result.shardsDone)
        assertEquals(3, result.shardsTotal)
        assertEquals(0, result.failed)
        assertEquals(1, harness.completeRequests.size)
        assertEquals(1, harness.patches.size)
        assertEquals("取消时保留进度", listOf(progressOf(setOf(0), emptySet(), 3)), harness.savedProgress)
        assertTrue("取消时不能清进度（否则下次从头再来）", harness.cleared.isEmpty())
        assertEquals((35_020 + 2_000) / 1_000_000.0, result.estimatedYuan, 1e-12)
        assertEquals(listOf(0 to 3, 1 to 3), progress)
    }

    @Test
    fun noMessagesRunsNothingAndClearsStaleProgress() = runBlocking {
        val harness = Harness(
            emptyList(),
            initialProgress = progressOf(done = setOf(0, 1), failed = emptySet(), total = 3),
        )
        var progress: Pair<Int, Int>? = null

        val result = harness.unit.run(
            threadId = threadId,
            segments = emptyList(),
            range = ConsolidateRange.ALL,
            plan = ConsolidatePlan(0, 0, 0, 0.0),
            progress = { done, total -> progress = done to total },
        )

        assertEquals(ConsolidateResult(0, 0, 0, 0.0, cancelled = false), result)
        assertTrue(harness.completeRequests.isEmpty())
        assertTrue(harness.patches.isEmpty())
        assertEquals("没有可整理内容时顺手清掉旧进度", listOf(threadId), harness.cleared)
        assertEquals(0 to 0, progress)
    }
}

/**
 * `MemoryRepository.addBoundary`（ 唯一新增的仓储方法）的 Robolectric + 内存 Room 测试。
 *
 * 放在 自己的测试文件里（不改别人的 `ImportRepositoryTest`），只验证新的那个方法：
 * 写进去/去重/owner 隔离/空文本忽略。
 *
 * （记忆控制权）、（「不想被提起」清单）；（许可）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MemoryRepositoryAddBoundaryTest {

    private lateinit var db: PulsezDatabase
    private lateinit var repo: MemoryRepository

    private val threadId = "thread-boundary"

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, PulsezDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repo = MemoryRepository(db)
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun addBoundaryWritesHighPriorityActiveEntry() = runBlocking {
        repo.addBoundary(threadId, "  别再提前男友  ")

        val boundaries = repo.load(threadId).activeBoundaries()
        assertEquals(1, boundaries.size)
        assertEquals("别再提前男友", boundaries.single().value)
        assertEquals(1.0, boundaries.single().conf, 0.0)
        assertEquals("边界是最高重要度", 5, boundaries.single().importance)
        assertEquals(MemoryStatus.ACTIVE, boundaries.single().status)
        assertTrue(boundaries.single().validFrom > 0L)
        assertNull(
            "不是模型抽取产物，不挂段",
            db.memoryDao().allForThread(threadId).single().sourceSegmentId,
        )
    }

    @Test
    fun duplicateTextIsDeduped() = runBlocking {
        repo.addBoundary(threadId, "别再提那件事")
        repo.addBoundary(threadId, " 别再提那件事 ")

        assertEquals(1, repo.load(threadId).activeBoundaries().size)
        assertEquals("不能出现两条有效记录", 1, db.memoryDao().allForThread(threadId).size)
    }

    @Test
    fun boundaryDoesNotWipeOtherMemoryKinds() = runBlocking {
        repo.applyPatch(
            threadId = threadId,
            patchJson = """{"facts":[{"k":"城市","v":"杭州","conf":0.9}],"events":[],"loops":[],"relation":{},"boundaries":[],"prefs":[]}""",
            segmentId = null,
        )
        repo.addBoundary(threadId, "别再提搬家")

        val table = repo.load(threadId)
        assertEquals(1, table.activeFacts().size)
        assertEquals("杭州", table.activeFacts().single().value)
        assertEquals(1, table.activeBoundaries().size)
    }

    @Test
    fun ownerDimensionIsIsolated() = runBlocking {
        repo.addBoundary(threadId, "群聊里这个角色的边界", ownerId = "char-a")

        assertEquals(1, repo.load(threadId, ownerId = "char-a").activeBoundaries().size)
        assertTrue("单聊维度不能被群聊角色的边界污染", repo.load(threadId).activeBoundaries().isEmpty())
    }

    @Test
    fun blankTextIsIgnored() = runBlocking {
        repo.addBoundary(threadId, "   ")

        assertTrue(repo.load(threadId).activeBoundaries().isEmpty())
    }
}
