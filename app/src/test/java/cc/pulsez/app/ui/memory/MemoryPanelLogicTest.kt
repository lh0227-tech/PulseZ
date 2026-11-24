package cc.pulsez.app.ui.memory

import cc.pulsez.app.core.ConsolidatePlan
import cc.pulsez.app.core.EmbedPort
import cc.pulsez.app.core.ImportIndexer
import cc.pulsez.app.data.db.ChunkEntity
import cc.pulsez.app.data.db.MessageEntity
import cc.pulsez.app.data.db.SegmentEntity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 记忆面板纯函数单测。
 *
 * 只覆盖**不碰 Android / Compose / Room 运行时**的部分（工程没有 UI 测试）：
 * - 整理进度与费用确认判定；
 * - `IMPORT 段 + 消息 → ImportedSegment` 的纯映射（ 契约）；
 * - 面板文案逐字锁定（ 附录 A 收敛稿）。
 *
 * `MemoryViewModel` 的构造依赖 `AppContainer`（Context/DataStore/HTTP），按任务要求**不强行**造 fake；
 * plan → 必要时确认 → run → refresh 的完整界面流程由设备运行验证。
 */
class MemoryPanelLogicTest {

    // ------------------------------------------------------------------ fixtures

    private fun plan(yuan: Double): ConsolidatePlan = ConsolidatePlan(
        shards = 2,
        inputTokens = 1_000,
        messageCount = 4,
        estimatedYuan = yuan,
    )

    private fun segment(id: String = "seg-1"): SegmentEntity = SegmentEntity(
        id = id,
        threadId = "t",
        ordinal = 1,
        summary = null,
        styleNotes = null,
        firstMessageAt = 100,
        lastMessageAt = 200,
        tokenSum = 0,
        state = "SEALED",
        batonReason = "IMPORT",
        createdAt = 1,
    )

    private fun message(id: String, at: Long, role: String = "user"): MessageEntity = MessageEntity(
        id = id,
        threadId = "t",
        segmentId = "seg-1",
        role = role,
        content = "内容-$id",
        tokens = 1,
        emotion = null,
        status = "OK",
        draft = null,
        usageIn = null,
        usageOut = null,
        previousContent = null,
        createdAt = at,
    )

    // ------------------------------------------------------------------ 进度

    @Test
    fun `formatConsolidateProgress shows done over total`() {
        assertEquals("3 / 8", formatConsolidateProgress(3, 8))
        assertEquals("0 / 0", formatConsolidateProgress(0, 0))
    }

    @Test
    fun `formatConsolidateProgress clamps dirty values`() {
        assertEquals("0 / 8", formatConsolidateProgress(-2, 8))
        assertEquals("8 / 8", formatConsolidateProgress(9, 8))
        // total<0 归一成 0 / 0（脏进度/取消竞态不该出现负数）
        assertEquals("0 / 0", formatConsolidateProgress(0, -1))
    }

    // ------------------------------------------------------------------ 费用确认

    @Test
    fun `consent is required only above one yuan`() {
        // 例：¥0.20 直接跑；¥1.00 不打扰（阈值是「> ¥1」）。
        assertFalse(shouldRequireConsent(plan(0.0)))
        assertFalse(shouldRequireConsent(plan(0.20)))
        assertFalse(shouldRequireConsent(plan(1.0)))
        assertTrue(shouldRequireConsent(plan(1.0001)))
        assertTrue(shouldRequireConsent(plan(2.50)))
    }

    // ------------------------------------------------------------------ IMPORT 段映射

    @Test
    fun `importedSegmentFrom keeps message order and first last times`() {
        val imported = importedSegmentFrom(
            segment = segment("seg-9"),
            messages = listOf(
                message("m1", at = 1_600_000_000_000L, role = "user"),
                message("m2", at = 1_600_000_060_000L, role = "assistant"),
                message("m3", at = 1_600_000_120_000L, role = "user"),
            ),
        )

        requireNotNull(imported)
        // DB 不存 sourceSessionId： 也不读它，填空串（与 DeepSeekImporter 的续导重建同口径）。
        assertEquals("", imported.sourceSessionId)
        assertEquals("seg-9", imported.segmentId)
        assertEquals(listOf("m1", "m2", "m3"), imported.messageIds)
        assertEquals(1_600_000_000_000L, imported.firstMessageAt)
        assertEquals(1_600_000_120_000L, imported.lastMessageAt)
    }

    @Test
    fun `importedSegmentFrom skips empty segment`() {
        assertNull(importedSegmentFrom(segment(), emptyList()))
    }

    // ------------------------------------------------------------------ 导入未整理入口

    @Test
    fun `import pending needs both import segments and no consolidated mark`() {
        // 没有 IMPORT 段：不显示入口（普通 TA）。
        assertFalse(importPendingFor(false, "thread-1", emptySet()))
        // 有 IMPORT 段、没标记：显示入口。
        assertTrue(importPendingFor(true, "thread-1", emptySet()))
        // 有 IMPORT 段，但这个 thread 已整段整理过：入口收起（避免从 0 重跑、重复花钱）。
        assertFalse(importPendingFor(true, "thread-1", setOf("thread-1")))
        // 标记是按 threadId 的：别的 thread 被标记不能影响当前 thread。
        assertTrue(importPendingFor(true, "thread-1", setOf("thread-2")))
    }

    // ------------------------------------------------------------------ 提示 → 设置页目标

    @Test
    fun `guided notices map to explicit settings targets`() {
        // ：去向由显式枚举给出，UI 不再比较提示语字符串。
        assertEquals(NoticeOpenTarget.CHAT, noticeOpenTargetFor(MemoryCopy.NOTICE_NEED_AI))
        assertEquals(
            NoticeOpenTarget.FIND_OLD_THINGS,
            noticeOpenTargetFor(MemoryCopy.NOTICE_BACKFILL_NEED_SERVICE),
        )
        // 普通反馈不出现引导按钮（不会静默走进某个设置页）。
        assertNull(noticeOpenTargetFor(MemoryCopy.NOTICE_DELETED))
        assertNull(noticeOpenTargetFor(MemoryCopy.NOTICE_BACKFILLED))
        assertNull(noticeOpenTargetFor(MemoryCopy.NOTICE_CONSOLIDATE_FAILED))
        assertNull(noticeOpenTargetFor(null))
    }

    @Test
    fun `withNotice carries target and clears it for plain feedback`() {
        // ViewModel 设置提示语时一起写 target（纯状态映射，不依赖 AppContainer）。
        val needAi = MemoryUiState().withNotice(MemoryCopy.NOTICE_NEED_AI)
        assertEquals(MemoryCopy.NOTICE_NEED_AI, needAi.notice)
        assertEquals(NoticeOpenTarget.CHAT, needAi.noticeOpenTarget)

        val needService = MemoryUiState().withNotice(MemoryCopy.NOTICE_BACKFILL_NEED_SERVICE)
        assertEquals(MemoryCopy.NOTICE_BACKFILL_NEED_SERVICE, needService.notice)
        assertEquals(NoticeOpenTarget.FIND_OLD_THINGS, needService.noticeOpenTarget)

        // 换一条普通反馈：上一条的引导目标不能残留（按钮会跟着错的提示出现）。
        val deleted = needService.withNotice(MemoryCopy.NOTICE_DELETED)
        assertEquals(MemoryCopy.NOTICE_DELETED, deleted.notice)
        assertNull("普通反馈不该带引导按钮", deleted.noticeOpenTarget)

        // 收起提示：文字与目标一起清空。
        val dismissed = needAi.withNotice(null)
        assertNull(dismissed.notice)
        assertNull(dismissed.noticeOpenTarget)
    }

    @Test
    fun `guide button labels are derived from target`() {
        // 按钮文案也由同一枚举派生：未连 AI → 去连接；找旧事未配置 → 去添加。
        assertEquals(MemoryCopy.OPEN_SETTINGS, openButtonLabel(NoticeOpenTarget.CHAT))
        assertEquals("去添加", openButtonLabel(NoticeOpenTarget.FIND_OLD_THINGS))
    }

    // ------------------------------------------------------------------ 文案

    @Test
    fun `panel copy matches the agreed wording verbatim`() {
        assertEquals("TA 记得的事", MemoryCopy.TITLE)
        // 首次进入说明（需求 A，面板顶部 caption）
        assertEquals(
            "这里是被整理过的重点，不是每一句话。记错或不想留的，可以忘掉或让 TA 别再提。",
            MemoryCopy.CAPTION,
        )
        assertEquals(
            "这里还是空的。聊到的事会整理在这里，不想留的可以随时忘掉，或让 TA 以后别再提。",
            MemoryCopy.EMPTY,
        )
        // 分组（「别碰的地方」→「不想被提起的」）
        assertEquals("TA 记得的你", MemoryCopy.GROUP_FACT)
        assertEquals("你们之间", MemoryCopy.GROUP_RELATION)
        assertEquals("还没说完的事", MemoryCopy.GROUP_LOOP)
        assertEquals("你喜欢和不喜欢的事", MemoryCopy.GROUP_PREF)
        assertEquals("不想被提起的", MemoryCopy.GROUP_BOUNDARY)
        // 导入未整理分支
        assertEquals(
            "这些是导入的旧事，不整理也会留着；想让 TA 记成长期重点可以点整理，按你的服务商计费（预估超过 ¥1 会先问你）。",
            MemoryCopy.IMPORT_TIP,
        )
        assertEquals("整理往事", MemoryCopy.CONSOLIDATE)
        assertEquals("再整理一些", MemoryCopy.CONSOLIDATE_AGAIN)
        // 90 天回退后仍无可整理内容时，说「没有可整理的旧事」，
        // 不再说「最近 90 天」（回退后说 90 天会误导）。
        assertEquals("没有可整理的旧事。", MemoryCopy.NOTICE_NOTHING_AT_ALL)
        // 删除/边界反馈（附录 A 4.7：可兑现的删除承诺）
        assertEquals("删掉了。以后不会再用这条。", MemoryCopy.NOTICE_DELETED)
        assertEquals("不会主动提这件事了。", MemoryCopy.NOTICE_BOUNDARY_ADDED)
        assertEquals("以后别再提这件事", MemoryCopy.BOUNDARY)
        // 费用确认（文案修复）：只说金额，不上屏内部计量单位「片」
        assertEquals(
            "整理成记忆大概 ¥1.20，继续吗？",
            MemoryCopy.consentText(1.2, 3),
        )
        // ≥100 元不带小数（「数字」口径）
        assertEquals(
            "整理成记忆大概 ¥128，继续吗？",
            MemoryCopy.consentText(128.4, 20),
        )
        // 未连 AI：一行提示（不崩、不承诺记忆）
        assertEquals("先在设置里连上 AI，再回来整理。", MemoryCopy.NOTICE_NEED_AI)
    }

    @Test
    fun `panel copy uses TA and avoids technical words`() {
        val allTexts = listOf(
            MemoryCopy.TITLE,
            MemoryCopy.CAPTION,
            MemoryCopy.EMPTY,
            MemoryCopy.GROUP_FACT,
            MemoryCopy.GROUP_RELATION,
            MemoryCopy.GROUP_LOOP,
            MemoryCopy.GROUP_PREF,
            MemoryCopy.GROUP_BOUNDARY,
            MemoryCopy.MENU,
            MemoryCopy.FORGET,
            MemoryCopy.BOUNDARY,
            MemoryCopy.IMPORT_TIP,
            MemoryCopy.CONSOLIDATE,
            MemoryCopy.CONSOLIDATE_AGAIN,
            MemoryCopy.CONSOLIDATING,
            MemoryCopy.CONSOLIDATE_CANCEL,
            MemoryCopy.PROGRESS_RUNNING_PREFIX,
            MemoryCopy.PROGRESS_PARTIAL_PREFIX,
            MemoryCopy.NOTICE_DELETED,
            MemoryCopy.NOTICE_DELETE_FAILED,
            MemoryCopy.NOTICE_BOUNDARY_ADDED,
            MemoryCopy.NOTICE_BOUNDARY_FAILED,
            MemoryCopy.NOTICE_NEED_AI,
            MemoryCopy.OPEN_SETTINGS,
            MemoryCopy.NOTICE_CONSOLIDATED,
            MemoryCopy.NOTICE_CONSOLIDATE_FAILED,
            MemoryCopy.NOTICE_CONSOLIDATE_CANCELLED,
            MemoryCopy.NOTICE_NOTHING_AT_ALL,
            MemoryCopy.NOTICE_NO_IMPORT,
            MemoryCopy.CONSENT_CONTINUE,
            MemoryCopy.CONSENT_CANCEL,
            MemoryCopy.consentText(1.20, 3),
            // 老数据补建
            MemoryCopy.BACKFILL_TIP,
            MemoryCopy.BACKFILL,
            MemoryCopy.BACKFILL_RETRY,
            MemoryCopy.BACKFILLING,
            MemoryCopy.BACKFILL_PROGRESS_PREFIX,
            MemoryCopy.BACKFILL_CANCEL,
            MemoryCopy.NOTICE_BACKFILLED,
            MemoryCopy.NOTICE_BACKFILL_FAILED,
            MemoryCopy.NOTICE_BACKFILL_CANCELLED,
            MemoryCopy.NOTICE_BACKFILL_NOTHING,
            MemoryCopy.NOTICE_BACKFILL_NEED_SERVICE,
        )
        // /：不出现内部技术词；角色代词只能用 TA，不用「她/他」。
        // 追加禁用词「向量 / embedding」：向量是正式能力，但**用户文案**不暴露机制
        // （V6「说清是什么」≠ 说机制词）。
        val banned = listOf(
            "token", "上下文", "窗口", "向量", "模型", "embedding", "语义索引", "缓存",
            "她", "他",
        )
        allTexts.forEach { text ->
            banned.forEach { word ->
                assertFalse("面板文案不该出现「$word」：$text", text.contains(word))
            }
        }
    }

    // ------------------------------------------------------------------ 老数据补建

    private fun chunk(
        id: String,
        segmentId: String = "seg-1",
        dim: Int = 0,
        model: String = ImportIndexer.MODEL_NONE,
        vec: ByteArray = ByteArray(0),
        text: String = "内容-$id",
        deletedAt: Long? = null,
    ): ChunkEntity = ChunkEntity(
        id = id,
        threadId = "t",
        segmentId = segmentId,
        text = text,
        firstMessageAt = 1L,
        lastMessageAt = 2L,
        messageIds = "m-$id",
        dim = dim,
        vec = vec,
        scale = 1f,
        model = model,
        createdAt = 1L,
        deletedAt = deletedAt,
    )

    /** 记录调用、固定返回 4 维向量的向量端口 Fake。 */
    private class FakeBackfillEmbed(private val fails: Boolean = false) : EmbedPort {
        val calls = mutableListOf<List<String>>()

        override suspend fun embed(texts: List<String>): List<FloatArray>? {
            calls += texts
            if (fails) throw IllegalStateException("模拟找旧事服务失败")
            return texts.map { FloatArray(4) { position -> (position + 1).toFloat() } }
        }
    }

    @Test
    fun `backfill pending chunks only selects import keyword chunks`() {
        val chunks = listOf(
            chunk("import-keyword"), // 应选中
            chunk("import-keyword-2", segmentId = "seg-2"), // 应选中
            chunk("chat-keyword", segmentId = "seg-chat"), // 不是导入段，不选
            chunk("already-vector", dim = 4, model = "bge-m3", vec = ByteArray(4)), // 已有向量，不选
            chunk("other-model", model = "bge-m3"), // 模型不是 "none"，不选
            chunk("deleted", deletedAt = 99L), // 已删，不选
            chunk("blank-text", text = "   "), // 空正文，不选
        )

        val pending = SemanticBackfill.pendingChunks(
            chunks = chunks,
            importSegmentIds = setOf("seg-1", "seg-2"),
        )

        assertEquals(listOf("import-keyword", "import-keyword-2"), pending.map { it.id })
    }

    @Test
    fun `backfill batches never exceed sixteen`() {
        val chunks = (1..40).map { chunk("c$it") }
        val batches = SemanticBackfill.batches(chunks)

        assertEquals(listOf(16, 16, 8), batches.map { it.size })
        assertEquals("分批不能丢块", 40, batches.sumOf { it.size })
        assertEquals(ImportIndexer.EMBED_BATCH_SIZE, SemanticBackfill.BATCH_SIZE)
    }

    @Test
    fun `backfill withVector writes quantized dims and keeps other fields`() {
        val original = chunk("c1")

        val updated = SemanticBackfill.withVector(original, floatArrayOf(0.5f, -1f, 2f, 0f), "bge-m3")

        assertEquals(4, updated.dim)
        assertEquals(4, updated.vec.size)
        assertTrue("scale 必须为正", updated.scale > 0f)
        assertEquals("bge-m3", updated.model)
        assertEquals("其它字段原样保留", original.id, updated.id)
        assertEquals(original.text, updated.text)
        assertEquals(original.messageIds, updated.messageIds)
        assertEquals(original.segmentId, updated.segmentId)
        assertEquals(original.lastMessageAt, updated.lastMessageAt)
    }

    @Test
    fun `backfill run updates rows and reports progress`() = runBlocking {
        val chunks = (1..20).map { chunk("c$it") }
        val saved = mutableListOf<ChunkEntity>()
        val progress = mutableListOf<Pair<Int, Int>>()
        val embed = FakeBackfillEmbed()

        val result = SemanticBackfill.run(
            chunks = chunks,
            embed = embed,
            model = "bge-m3",
            saveChunks = { rows -> saved += rows },
            progress = { done, total -> progress += done to total },
        )

        assertEquals(20, result.total)
        assertEquals(20, result.embedded)
        assertEquals(0, result.failedChunks)
        assertFalse(result.cancelled)
        assertEquals("批 ≤16：16 + 4 两次调用", listOf(16, 4), embed.calls.map { it.size })
        assertEquals("每批一次进度回调（含开始 0/20）", 3, progress.size)
        assertEquals(20 to 20, progress.last())
        assertEquals(20, saved.size)
        saved.forEach { row ->
            assertEquals(4, row.dim)
            assertEquals(4, row.vec.size)
            assertEquals("bge-m3", row.model)
        }
    }

    @Test
    fun `backfill run skips failed batches and keeps succeeded ones`() = runBlocking {
        val chunks = (1..20).map { chunk("c$it") }
        val saved = mutableListOf<ChunkEntity>()
        var call = 0
        val embed = object : EmbedPort {
            override suspend fun embed(texts: List<String>): List<FloatArray>? {
                call++
                // 前 3 次（一批的重试）都失败：该批跳过；后面的批成功。
                if (call <= 3) throw IllegalStateException("模拟失败")
                return texts.map { FloatArray(4) { 1f } }
            }
        }

        val result = SemanticBackfill.run(
            chunks = chunks,
            embed = embed,
            model = "bge-m3",
            saveChunks = { rows -> saved += rows },
        )

        assertEquals(20, result.total)
        assertEquals("第一批（16 块）失败跳过，第二批（4 块）成功", 4, result.embedded)
        assertEquals(16, result.failedChunks)
        assertEquals("只保存成功的那一批", 4, saved.size)
        assertTrue(saved.all { it.dim == 4 })
    }

    @Test
    fun `backfill run is cancellable and keeps finished batches`() = runBlocking {
        val chunks = (1..20).map { chunk("c$it") }
        val saved = mutableListOf<ChunkEntity>()
        var checks = 0

        val result = SemanticBackfill.run(
            chunks = chunks,
            embed = FakeBackfillEmbed(),
            model = "bge-m3",
            saveChunks = { rows -> saved += rows },
            // 第一批开始前返回 false（继续），第一批之后返回 true（停下）→ 只处理一批。
            isCancelled = { checks++ > 0 },
        )

        assertTrue(result.cancelled)
        assertEquals("只处理了第一批", 16, result.processed)
        assertEquals(16, saved.size)
        assertEquals(16, result.embedded)
    }

    @Test
    fun `backfill copy says what it is who charges and what happens without it`() {
        // ：让用户能答出「这是什么 / 谁收钱 / 不做会怎样」。
        assertEquals("让 TA 能按意思找旧事", MemoryCopy.BACKFILL)
        assertTrue("要说明用什么处理", MemoryCopy.BACKFILL_TIP.contains("找旧事"))
        assertTrue("要说明费用由谁收", MemoryCopy.BACKFILL_TIP.contains("费用由你的服务商收取"))
        assertTrue("要说明按下之后的效果", MemoryCopy.BACKFILL_TIP.contains("按意思找到"))
        assertTrue("不自动执行：入口是一句「让 TA 能…」，不是自动提示",
            MemoryCopy.BACKFILL.startsWith("让 TA"))
        assertEquals("正在处理 3 / 10", MemoryCopy.BACKFILL_PROGRESS_PREFIX + formatConsolidateProgress(3, 10))
        // 失败/取消都不夸大成「全好了」：说清「做好的保留着」。
        assertTrue(MemoryCopy.NOTICE_BACKFILL_FAILED.contains("保留着"))
        assertTrue(MemoryCopy.NOTICE_BACKFILL_CANCELLED.contains("保留着"))
        assertEquals("好了。以后 TA 能按意思找到这些旧事。", MemoryCopy.NOTICE_BACKFILLED)
    }
}
