package cc.pulsez.app.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import cc.pulsez.app.data.db.MessageEntity
import cc.pulsez.app.data.db.ChunkEntity
import cc.pulsez.app.data.db.ChunkTermEntity
import cc.pulsez.app.data.db.MemoryItemEntity
import cc.pulsez.app.data.db.PulsezDatabase
import cc.pulsez.app.data.db.ThreadStateEntity
import cc.pulsez.app.data.db.ThreadKind
import cc.pulsez.app.data.repo.ChatRepository
import cc.pulsez.app.data.repo.ImportBatchException
import cc.pulsez.app.data.repo.ImportBatchGroupPlan
import cc.pulsez.app.data.repo.ImportBatchPlan
import cc.pulsez.app.data.repo.ImportBatchWindowPlan
import cc.pulsez.app.data.repo.ImportException
import cc.pulsez.app.data.repo.ImportRepository
import cc.pulsez.app.data.repo.ImportSummary
import cc.pulsez.app.data.repo.ImportTarget
import cc.pulsez.app.data.repo.MemoryRepository
import cc.pulsez.app.importer.deepseek.DeepSeekTurn
import cc.pulsez.app.importer.deepseek.DeepSeekSessionPreview
import cc.pulsez.app.importer.deepseek.Issue
import cc.pulsez.app.importer.deepseek.ParsedDeepSeekSession
import cc.pulsez.app.mem.TokenEstimator
import java.util.UUID
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
 * 导入数据层的 Robolectric + 内存 Room 集成测试。
 *
 * 锁死 的 6 条段不变量、 的幂等/抽取隔离/撤销，以及 缺陷修复里的
 * `tail` 同毫秒 tie-breaker 与「查看更早」游标分页（/⑤）。全程离线，不依赖真机。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ImportRepositoryTest {

    private lateinit var db: PulsezDatabase
    private lateinit var repo: ImportRepository
    private lateinit var chat: ChatRepository

    /** 2023 年的时间，明显早于 `System.currentTimeMillis`，用于验证「导入旧时间不把 thread 时间改早」。 */
    private val base = 1_690_000_000_000L

    private val roleUser = DeepSeekTurn.ROLE_USER
    private val roleAssistant = DeepSeekTurn.ROLE_ASSISTANT

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, PulsezDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repo = ImportRepository(db)
        chat = ChatRepository(db)
    }

    @After
    fun tearDown() {
        db.close()
    }

    // ------------------------------------------------------------------
    // 夹具
    // ------------------------------------------------------------------

    private fun turn(role: String, text: String, at: Long, sourceMessageId: Long) =
        DeepSeekTurn(role = role, text = text, at = at, sourceMessageId = sourceMessageId)

    private fun parsed(
        id: String,
        vararg turns: DeepSeekTurn,
        issues: List<Issue> = emptyList(),
    ): ParsedDeepSeekSession = ParsedDeepSeekSession(
        sourceSessionId = id,
        title = "会话 $id",
        insertedAt = turns.firstOrNull()?.at ?: base,
        updatedAt = turns.lastOrNull()?.at ?: base,
        turns = turns.toList(),
        issues = issues,
    )

    /** 每段 2 条消息（user + assistant），`sourceMessageId` 固定 1/2，跨会话也唯一靠 sessionId 区分。 */
    private fun twoTurnSession(id: String, startAt: Long): ParsedDeepSeekSession = parsed(
        id,
        turn(roleUser, "你好，$id", startAt, 1L),
        turn(roleAssistant, "收到，$id", startAt + 1_000, 2L),
    )

    private suspend fun doImport(
        sessions: List<ParsedDeepSeekSession>,
        name: String = "TA",
        persona: String? = "温和",
        zipName: String = "deepseek-export.zip",
        resume: String? = null,
        progress: (done: Int, total: Int, messages: Int) -> Unit = { _, _, _ -> },
    ): ImportSummary = repo.importSessions(
        target = ImportTarget.NewCharacter(
            name = name,
            persona = persona,
            zipName = zipName,
            resumeCharacterId = resume,
        ),
        sessions = sessions,
        progress = progress,
    )

    private suspend fun segmentsOf(summary: ImportSummary) =
        db.segmentDao().forThread(summary.threadId)

    private suspend fun messagesOf(summary: ImportSummary) =
        db.messageDao().allForThread(summary.threadId)

    private fun messageFor(
        summary: ImportSummary,
        id: String,
        createdAt: Long,
        content: String = "同毫秒消息",
    ): MessageEntity = MessageEntity(
        id = id,
        threadId = summary.threadId,
        segmentId = summary.segments.first().segmentId,
        role = roleUser,
        content = content,
        tokens = TokenEstimator.estimate(content),
        emotion = null,
        status = "OK",
        draft = null,
        usageIn = null,
        usageOut = null,
        previousContent = null,
        createdAt = createdAt,
        deletedAt = null,
        speakerCharacterId = null,
    )

    private fun batchPlan(
        batchId: String,
        fingerprint: String,
        targetCharacterId: String?,
        groupId: String,
        sourceIds: List<String>,
    ) = ImportBatchPlan(
        id = batchId,
        fingerprint = fingerprint,
        zipName = "synthetic.zip",
        groups = listOf(
            ImportBatchGroupPlan(
                id = groupId,
                targetCharacterId = targetCharacterId,
                name = "Imported TA",
                persona = "synthetic persona",
            ),
        ),
        windows = sourceIds.mapIndexed { index, sourceId ->
            ImportBatchWindowPlan(
                preview = DeepSeekSessionPreview(
                    entryName = "chat_sessions/$sourceId.json",
                    sourceSessionId = sourceId,
                    title = "Synthetic $sourceId",
                    sourceStartedAt = base + index * 10_000L,
                    messageCount = 2,
                ),
                groupId = groupId,
            )
        },
    )

    private fun memoryRow(threadId: String, sourceSegmentId: String? = null) = MemoryItemEntity(
        id = "memory-synthetic",
        threadId = threadId,
        kind = "FACT",
        key = "synthetic-key",
        value = "synthetic memory",
        confidence = 0.8f,
        importance = 2,
        happenedAt = null,
        dueAt = null,
        validFrom = base,
        validTo = null,
        firstSeenAt = base,
        lastSeenAt = base,
        sourceSegmentId = sourceSegmentId,
        sourceMessageIds = "synthetic-source-message",
        status = "ACTIVE",
        supersededBy = null,
        characterId = null,
    )

    private fun syntheticChunk(id: String, threadId: String, segmentId: String, messageId: String) = ChunkEntity(
        id = id,
        threadId = threadId,
        segmentId = segmentId,
        text = "synthetic indexed text",
        firstMessageAt = base,
        lastMessageAt = base + 1,
        messageIds = messageId,
        dim = 1,
        vec = byteArrayOf(1),
        scale = 1f,
        model = "synthetic",
        createdAt = base,
        deletedAt = null,
    )

    // ------------------------------------------------------------------
    // 1. 新建 TA：character + thread + 唯一 OPEN 段（第 1/2/4 条）
    // ------------------------------------------------------------------

    @Test
    fun newCharacterCreatesCharacterThreadAndSingleOpenSegment() = runBlocking {
        val session = parsed(
            "s-1",
            turn(roleUser, "你好", base, 1),
            turn(roleAssistant, "在的", base + 1_000, 2),
            turn(roleUser, "今天有点累", base + 2_000, 3),
            turn(roleAssistant, "那就早点休息", base + 3_000, 4),
        )

        val summary = doImport(listOf(session))

        // 角色字段
        val character = requireNotNull(db.characterDao().byId(summary.characterId)) { "角色应已落库" }
        assertEquals("TA", character.name)
        assertEquals("温和", character.persona)
        assertEquals(ImportRepository.SOURCE_TYPE_DEEPSEEK, character.sourceType)
        assertEquals("deepseek-export.zip", character.sourceRef)
        assertNull("不存原始 ZIP 原文", character.rawImported)
        assertTrue("协议默认开", character.protocolEnabled)

        // thread
        val thread = requireNotNull(db.threadDao().byId(summary.threadId)) { "thread 应已落库" }
        assertEquals(character.id, thread.characterId)
        assertEquals(ThreadKind.SINGLE, thread.kind)
        assertNull("标题留空（用默认）", thread.title)
        assertEquals(1, db.threadDao().active().size)

        // 段：1 个 SEALED(0) + 1 个 OPEN(1)
        val segments = segmentsOf(summary)
        assertEquals("一个导入会话一个 SEALED 段 + 一个 OPEN 段", 2, segments.size)
        val sealed = segments.single { it.state == "SEALED" }
        val open = segments.single { it.state == "OPEN" }
        assertEquals(0, sealed.ordinal)
        assertEquals(ImportRepository.BATON_REASON_IMPORT, sealed.batonReason)
        assertNull("导入往事不进 ② 编年史：summary 必须为 null", sealed.summary)
        assertEquals(base, sealed.firstMessageAt)
        assertEquals(base + 3_000, sealed.lastMessageAt)
        assertEquals(1, open.ordinal)
        assertNull("OPEN 段不是导入段", open.batonReason)

        // 第 1 条：active 指向唯一 OPEN、ordinal 最大、等于 segmentOrdinal、latest==active
        assertEquals(open.id, thread.activeSegmentId)
        assertEquals(1, thread.segmentOrdinal)
        assertEquals(open.id, db.segmentDao().latest(thread.id)!!.id)
        assertEquals(open.ordinal, segments.maxOf { it.ordinal })
        assertEquals(1, segments.count { it.state == "OPEN" })

        // 第 4 条：导入消息只写 SEALED，active 段为空（③ 不含导入历史）
        assertTrue("active 段必须为空", db.messageDao().bySegment(open.id).isEmpty())
        val inSealed = db.messageDao().bySegment(sealed.id)
        assertEquals(4, inSealed.size)
        assertEquals(
            listOf("user", "assistant", "user", "assistant"),
            inSealed.map { it.role },
        )

        // summary 契约
        assertEquals(1, summary.sessionsImported)
        assertEquals(0, summary.sessionsSkippedExisting)
        assertEquals(0, summary.sessionsFailed)
        assertEquals(4, summary.messagesInserted)
        assertEquals(0, summary.messagesExisting)
        assertEquals(1, summary.segments.size)
        assertEquals(sealed.id, summary.segments.single().segmentId)
        assertEquals(4, summary.segments.single().messageIds.size)
        assertEquals(base, summary.segments.single().firstMessageAt)
        assertEquals(base + 3_000, summary.segments.single().lastMessageAt)
        assertEquals(base + 3_000, summary.lastImportedAt)
        assertTrue("新建 TA：允许撤销", summary.createdNewCharacter)
        // 只吃 summary.segments 不查 DB：id 集合必须与 DB 一致
        assertEquals(inSealed.map { it.id }.toSet(), summary.segments.single().messageIds.toSet())
    }

    // ------------------------------------------------------------------
    // 2. 两段会话：SEALED 0/1 + OPEN 2；计数与时间
    // ------------------------------------------------------------------

    @Test
    fun twoSessionsProduceSealedOrdinalsZeroOneAndOpenTwo() = runBlocking {
        val summary = doImport(
            listOf(
                twoTurnSession("s-1", base),
                twoTurnSession("s-2", base + 10_000),
            ),
        )

        val thread = db.threadDao().byId(summary.threadId)!!
        val segments = segmentsOf(summary)
        assertEquals(3, segments.size)
        val sealed = segments.filter { it.state == "SEALED" }.sortedBy { it.ordinal }
        assertEquals("SEALED 段按会话顺序拿 ordinal 0/1", listOf(0, 1), sealed.map { it.ordinal })
        val open = segments.single { it.state == "OPEN" }
        assertEquals(2, open.ordinal)
        assertEquals(2, thread.segmentOrdinal)
        assertEquals(open.id, thread.activeSegmentId)

        // 第 5 条：totalMessages += 新增；lastExtractAt = 最后一条导入时间（原值 0）
        assertEquals(4, thread.totalMessages)
        assertEquals(base + 11_000, thread.lastExtractAt)
        // 导入的是旧时间：lastMessageAt 是 max(创建时间, 导入最后)，绝不能因此被改早
        assertTrue("lastMessageAt 不能被导入的旧时间改早", thread.lastMessageAt >= base + 11_000)
        assertEquals(4, messagesOf(summary).size)
        assertEquals(4, summary.messagesInserted)
    }

    // ------------------------------------------------------------------
    // 3. 幂等：同 sessions 再导一次（续导）不产生任何新数据
    // ------------------------------------------------------------------

    @Test
    fun reimportWithResumeIsIdempotent() = runBlocking {
        val sessions = listOf(
            twoTurnSession("s-1", base),
            twoTurnSession("s-2", base + 10_000),
        )
        val first = doImport(sessions)
        val segmentsBefore = segmentsOf(first).size
        val messagesBefore = messagesOf(first).size
        val threadBefore = db.threadDao().byId(first.threadId)!!

        val second = doImport(sessions, resume = first.characterId)

        assertEquals("幂等命中不得再写消息", 0, second.messagesInserted)
        assertEquals(4, second.messagesExisting)
        assertEquals(0, second.sessionsImported)
        assertEquals(2, second.sessionsSkippedExisting)
        assertEquals(0, second.sessionsFailed)
        assertTrue("幂等命中不得建段", second.segments.isEmpty())
        assertNull("没有新消息就没有 lastImportedAt", second.lastImportedAt)
        assertFalse(
            "续导 + 0 新增：createdNewCharacter=false，结果页不给撤销",
            second.createdNewCharacter,
        )

        assertEquals(messagesBefore, messagesOf(first).size)
        assertEquals(segmentsBefore, segmentsOf(first).size)
        val threadAfter = db.threadDao().byId(first.threadId)!!
        assertEquals(threadBefore.totalMessages, threadAfter.totalMessages)
        assertEquals(threadBefore.segmentOrdinal, threadAfter.segmentOrdinal)
        assertEquals(threadBefore.lastExtractAt, threadAfter.lastExtractAt)

        // 复用同一个 TA：不新建角色/thread
        assertEquals(1, db.characterDao().all().size)
        assertEquals(1, db.threadDao().active().size)
    }

    // ------------------------------------------------------------------
    // 4. 中断续导：先导第 1 段，再用全量 + resume 补完
    // ------------------------------------------------------------------

    @Test
    fun interruptedImportResumesWithoutDuplicates() = runBlocking {
        val s1 = twoTurnSession("s-1", base)
        val s2 = twoTurnSession("s-2", base + 10_000)
        val first = doImport(listOf(s1))
        assertEquals(2, messagesOf(first).size)

        val second = doImport(listOf(s1, s2), resume = first.characterId)

        assertEquals(2, second.messagesInserted)
        assertEquals(2, second.messagesExisting)
        assertEquals(1, second.sessionsImported)
        assertEquals(1, second.sessionsSkippedExisting)
        assertEquals(4, messagesOf(second).size)
        assertFalse(
            "续导复用旧 TA：结果页不能提供整批撤销，否则会隐藏既往历史",
            second.createdNewCharacter,
        )

        val thread = db.threadDao().byId(first.threadId)!!
        val segments = segmentsOf(second)
        assertEquals("1 个已有 SEALED + 1 个新 SEALED + 1 个 OPEN", 3, segments.size)
        assertEquals(2, segments.count { it.state == "SEALED" })
        val open = segments.single { it.state == "OPEN" }
        assertEquals("续导不重建 OPEN 段", open.id, thread.activeSegmentId)
        assertEquals(2, thread.segmentOrdinal)
        assertEquals(4, thread.totalMessages)
    }

    // ------------------------------------------------------------------
    // 5. 600 条消息：IN 分批（≤500/批）不触发 SQLite 变量上限
    // ------------------------------------------------------------------

    @Test
    fun largeSessionBatchesExistingIdLookupWithoutHittingVariableLimit() = runBlocking {
        val turns = (1..600).map { i ->
            turn(
                if (i % 2 == 1) roleUser else roleAssistant,
                "第 $i 条消息",
                base + i * 1_000L,
                i.toLong(),
            )
        }
        val session = parsed("s-big", *turns.toTypedArray())
        val first = doImport(listOf(session))
        assertEquals(600, first.messagesInserted)
        assertEquals(600, messagesOf(first).size)

        // 第二次导入要一次查 600 个 id（内部按 500+100 两批），必须不炸。
        val second = doImport(listOf(session), resume = first.characterId)
        assertEquals(0, second.messagesInserted)
        assertEquals(600, second.messagesExisting)
        assertEquals(1, second.sessionsSkippedExisting)
        assertEquals("幂等：消息数不变", 600, messagesOf(second).size)
        assertEquals("幂等：段数不变（1 SEALED + 1 OPEN）", 2, segmentsOf(second).size)
    }

    // ------------------------------------------------------------------
    // 6. 撤销：只软删角色，消息/段/thread 都保留
    // ------------------------------------------------------------------

    @Test
    fun undoImportOnlySoftDeletesCharacter() = runBlocking {
        val summary = doImport(listOf(twoTurnSession("s-1", base)))

        repo.undoImport(summary.characterId)

        assertTrue("撤销后不出现在角色列表", db.characterDao().all().isEmpty())
        assertNull("byId 也要过滤软删", db.characterDao().byId(summary.characterId))
        // 物理行仍在，只是 deletedAt 有值（不物理清库）
        val cursor = db.openHelper.readableDatabase.query(
            "SELECT deletedAt FROM character WHERE id = ?",
            arrayOf<Any?>(summary.characterId),
        )
        assertTrue(cursor.moveToFirst())
        assertFalse("必须是软删（deletedAt 非空），不是物理删除", cursor.isNull(0))
        cursor.close()

        assertNotNull("thread 保留", db.threadDao().byId(summary.threadId))
        assertEquals(2, messagesOf(summary).size)
        assertEquals(2, segmentsOf(summary).size)
        // 重复撤销不抛
        repo.undoImport(summary.characterId)
    }

    // ------------------------------------------------------------------
    // 7. 时间顺序 + tail 同毫秒 tie-breaker
    // ------------------------------------------------------------------

    @Test
    fun createdAtIncreasesAndTailBreaksTiesByMessageId() = runBlocking {
        val summary = doImport(listOf(twoTurnSession("s-1", base)))
        val imported = messagesOf(summary)
        assertEquals(2, imported.size)
        assertTrue("导入消息 createdAt 必须严格递增", imported[0].createdAt < imported[1].createdAt)

        // 同毫秒两条：tail 必须用 id DESC 兜底，结果稳定
        val sameAt = imported.last().createdAt + 1_000
        db.messageDao().upsert(messageFor(summary, "m-aaa", sameAt))
        db.messageDao().upsert(messageFor(summary, "m-bbb", sameAt))

        val tail = db.messageDao().tail(summary.threadId, 10)
        assertEquals(sameAt, tail[0].createdAt)
        assertEquals(
            "同一毫秒按 id DESC 兜底",
            listOf("m-bbb", "m-aaa"),
            tail.filter { it.createdAt == sameAt }.map { it.id },
        )
        assertTrue(
            "tail 整体必须 (createdAt DESC, id DESC)",
            tail.zipWithNext().all { (a, b) ->
                a.createdAt > b.createdAt || (a.createdAt == b.createdAt && a.id > b.id)
            },
        )
    }

    // ------------------------------------------------------------------
    // 8. 导入消息字段（映射表落库口径）
    // ------------------------------------------------------------------

    @Test
    fun importedMessagesCarryImportContractFields() = runBlocking {
        val text = "今天的风很大"
        val session = parsed(
            "s-1",
            turn(roleUser, text, base, 42),
            turn(roleAssistant, "嗯，像要下雨", base + 500, 43),
        )
        val summary = doImport(listOf(session))

        // 确定性 id：与 原式一致（测试侧独立再算一遍）
        val expectedId = UUID.nameUUIDFromBytes(
            "deepseek:v3:${summary.characterId}:s-1:42".toByteArray(Charsets.UTF_8),
        ).toString()
        assertEquals(
            ImportRepository.messageIdFor(summary.characterId, "s-1", 42),
            expectedId,
        )
        assertEquals(
            listOf(expectedId, ImportRepository.messageIdFor(summary.characterId, "s-1", 43)),
            summary.segments.single().messageIds,
        )

        val message = db.messageDao().byIds(listOf(expectedId)).single()
        assertEquals(summary.threadId, message.threadId)
        assertEquals(summary.segments.single().segmentId, message.segmentId)
        assertEquals("user", message.role)
        assertEquals(text, message.content)
        assertEquals(TokenEstimator.estimate(text), message.tokens)
        assertEquals("OK", message.status)
        assertNull(message.deletedAt)
        assertNull(message.speakerCharacterId)
        assertNull(message.emotion)
        assertNull(message.draft)
        assertNull(message.usageIn)
        assertNull(message.usageOut)
        assertNull(message.previousContent)
        assertEquals(base, message.createdAt)
    }

    // ------------------------------------------------------------------
    // 9. 续导目标不存在 / 已删 → RESUME_TARGET_MISSING
    // ------------------------------------------------------------------

    @Test
    fun resumeWithMissingOrDeletedCharacterThrowsResumeTargetMissing() = runBlocking {
        val sessions = listOf(twoTurnSession("s-1", base))

        val missing = runCatching { doImport(sessions, resume = "no-such-character") }.exceptionOrNull()
        assertTrue("必须是 ImportException", missing is ImportException)
        assertEquals(
            ImportException.RESUME_TARGET_MISSING,
            (missing as ImportException).code,
        )

        val summary = doImport(sessions)
        repo.undoImport(summary.characterId)
        val deleted = runCatching { doImport(sessions, resume = summary.characterId) }.exceptionOrNull()
        assertTrue("已软删的角色不能续导", deleted is ImportException)
        assertEquals(
            ImportException.RESUME_TARGET_MISSING,
            (deleted as ImportException).code,
        )
        // 续导失败不能顺手新建第二个 TA
        assertTrue(db.characterDao().all().isEmpty())
    }

    // ------------------------------------------------------------------
    // 10. 名字/人设归一化（名字必填预填，persona 可空）
    // ------------------------------------------------------------------

    @Test
    fun blankNameFallsBackToUnnamedAndPersonaDefaultsToEmpty() = runBlocking {
        val blank = doImport(listOf(twoTurnSession("s-1", base)), name = "   ", persona = null)
        val blankCharacter = db.characterDao().byId(blank.characterId)!!
        assertEquals("未命名", blankCharacter.name)
        assertEquals("", blankCharacter.persona)

        val typed = doImport(
            listOf(twoTurnSession("s-2", base)),
            name = " 小满 ",
            persona = " 爱画画 ",
        )
        val typedCharacter = db.characterDao().byId(typed.characterId)!!
        assertEquals("小满", typedCharacter.name)
        assertEquals("爱画画", typedCharacter.persona)
    }

    // ------------------------------------------------------------------
    // 11. 进度回调：每会话一次 + 累计消息数
    // ------------------------------------------------------------------

    @Test
    fun progressReportsEachSessionAndCumulativeInsertedMessages() = runBlocking {
        val calls = mutableListOf<Triple<Int, Int, Int>>()
        doImport(
            listOf(
                twoTurnSession("s-1", base),
                twoTurnSession("s-2", base + 10_000),
            ),
        ) { done, total, messages -> calls += Triple(done, total, messages) }

        assertEquals("开始一次 + 每会话一次", 3, calls.size)
        assertEquals(Triple(0, 2, 0), calls[0])
        assertEquals(Triple(1, 2, 2), calls[1])
        assertEquals(Triple(2, 2, 4), calls[2])
    }

    // ------------------------------------------------------------------
    // 12. 空会话防御：计失败、不建空段
    // ------------------------------------------------------------------

    @Test
    fun emptyTurnsSessionCountsAsFailedAndWritesNoSegment() = runBlocking {
        val good = twoTurnSession("s-1", base)
        val empty = ParsedDeepSeekSession(
            sourceSessionId = "s-empty",
            title = null,
            insertedAt = base,
            updatedAt = base,
            turns = emptyList(),
            issues = listOf(Issue("EMPTY_SESSION")),
        )

        val summary = doImport(listOf(good, empty))

        assertEquals(1, summary.sessionsImported)
        assertEquals(1, summary.sessionsFailed)
        assertEquals(0, summary.sessionsSkippedExisting)
        assertEquals(2, summary.messagesInserted)
        assertTrue(summary.issues.any { it.code == "EMPTY_SESSION" })
        assertEquals("空会话不得建段（只有 1 SEALED + 1 OPEN）", 2, segmentsOf(summary).size)
        assertEquals(2, messagesOf(summary).size)
    }

    // ------------------------------------------------------------------
    // 13. 中断后接着聊：active 段可读；续导不改 activeSegmentId（第 6 条）
    // ------------------------------------------------------------------

    @Test
    fun resumeKeepsActiveSegmentAndNewChatMessagesStayReadable() = runBlocking {
        val first = doImport(listOf(twoTurnSession("s-1", base)))
        val thread = db.threadDao().byId(first.threadId)!!
        val activeBefore = thread.activeSegmentId!!

        // 模拟中断后用户接着聊：新消息写进现有 OPEN 段
        chat.append(thread.id, activeBefore, roleUser, "我先说一句", 5, createdAt = base + 100_000)
        assertEquals(1, db.messageDao().bySegment(activeBefore).size)
        // 计数口径：首导 2 条 + append（ChatRepository 的 touch 每次 +1）= 3。
        assertEquals(3, db.threadDao().byId(thread.id)!!.totalMessages)

        val second = doImport(
            listOf(twoTurnSession("s-1", base), twoTurnSession("s-2", base + 10_000)),
            resume = first.characterId,
        )
        assertEquals(2, second.messagesInserted)

        val threadAfter = db.threadDao().byId(first.threadId)!!
        assertEquals("续导不得改 activeSegmentId", activeBefore, threadAfter.activeSegmentId)
        assertEquals(
            "§4.5 第 6 条：append 的消息仍能被 bySegment(active) 读到",
            listOf("我先说一句"),
            db.messageDao().bySegment(activeBefore).map { it.content },
        )
        assertEquals(2, threadAfter.segmentOrdinal)
        // 2（首导 s-1）+ 1（append）+ 2（续导只补 s-2）= 5；
        // 幂等命中的 s-1 不重复累加，append 的 +1 由 ChatRepository.touch 负责，两者不叠加同一条。
        assertEquals("2 + 1 + 2 = 5", 5, threadAfter.totalMessages)

        // 导入消息一条都不在 active 段
        val activeIds = db.messageDao().bySegment(activeBefore).map { it.id }.toSet()
        second.segments.flatMap { it.messageIds }.forEach { assertFalse(it in activeIds) }
    }

    // ------------------------------------------------------------------
    // 14. 导入历史不进 ③ / ②：全部 SEALED、active 为空、段 summary 为 null
    // ------------------------------------------------------------------

    @Test
    fun importedHistoryNeverEntersActiveSegmentOrChronicle() = runBlocking {
        val summary = doImport(
            listOf(
                twoTurnSession("s-1", base),
                twoTurnSession("s-2", base + 10_000),
            ),
        )
        val thread = db.threadDao().byId(summary.threadId)!!
        val active = thread.activeSegmentId!!

        assertTrue("③ 只读 active 段：必须为空", db.messageDao().bySegment(active).isEmpty())
        val sealedSegments = segmentsOf(summary).filter { it.state == "SEALED" }
        assertEquals(2, sealedSegments.size)
        assertEquals(2, summary.segments.size)
        summary.segments.forEach { assertFalse(it.segmentId == active) }

        val all = messagesOf(summary)
        assertEquals(4, all.size)
        val sealedIds = sealedSegments.map { it.id }.toSet()
        assertTrue("导入消息必须全部在 SEALED 段", all.all { it.segmentId in sealedIds })
        sealedSegments.forEach { assertNull("导入段 summary=null，不进 ② 编年史", it.summary) }
    }

    // ------------------------------------------------------------------
    // 15. 收尾计数只前进（bumpLast* 的 max 语义，定向 UPDATE）
    // ------------------------------------------------------------------

    @Test
    fun closingCountersOnlyMoveForward() = runBlocking {
        val later = parsed(
            "s-later",
            turn(roleUser, "晚的-1", base + 500_000, 1),
            turn(roleAssistant, "晚的-2", base + 501_000, 2),
        )
        val first = doImport(listOf(later))
        val before = db.threadDao().byId(first.threadId)!!
        assertEquals(base + 501_000, before.lastExtractAt)
        assertEquals(2, before.totalMessages)

        val earlier = parsed(
            "s-earlier",
            turn(roleUser, "早的-1", base, 1),
            turn(roleAssistant, "早的-2", base + 1_000, 2),
        )
        val second = doImport(listOf(earlier), resume = first.characterId)
        assertEquals(2, second.messagesInserted)

        val after = db.threadDao().byId(first.threadId)!!
        assertEquals("lastExtractAt 只前进（max 语义）", before.lastExtractAt, after.lastExtractAt)
        assertEquals("lastMessageAt 只前进（max 语义）", before.lastMessageAt, after.lastMessageAt)
        assertEquals("totalMessages 定向累加", 4, after.totalMessages)
    }

    // ------------------------------------------------------------------
    // 16. 中断续导的抽取隔离：幂等命中的上一轮会话也要计入 lastExtractAt
    // ------------------------------------------------------------------

    @Test
    fun resumeExtractionIsolationAlsoCoversSkippedSessions() = runBlocking {
        // 上一轮：s1 已写入（时间更晚）；模拟「进程被杀」——消息在、lastExtractAt 没写。
        val s1 = twoTurnSession("s-1", base + 100_000)
        val first = doImport(listOf(s1))
        val thread = db.threadDao().byId(first.threadId)!!
        db.threadDao().markExtracted(thread.id, 0L)

        // 续导：s1 幂等命中，s2 是更早的会话 → 只看「新写入」会把 lastExtractAt 写小，
        // 自动抽取的 after(lastExtractAt) 就会把上一轮导入的往事当成新对话读走。
        val s2 = twoTurnSession("s-2", base)
        val second = doImport(listOf(s1, s2), resume = first.characterId)
        assertEquals(2, second.messagesInserted)
        assertEquals(1, second.sessionsSkippedExisting)

        val after = db.threadDao().byId(first.threadId)!!
        assertEquals(
            "lastExtractAt 必须覆盖幂等命中的上一轮会话",
            base + 101_000,
            after.lastExtractAt,
        )
    }

    // ------------------------------------------------------------------
    // 17. 事务回滚：段不变量被破坏时整个会话事务回滚，不留半截 SEALED 段
    // ------------------------------------------------------------------

    @Test
    fun failedSessionTransactionRollsBackWithoutHalfWrittenSegment() = runBlocking {
        val first = doImport(listOf(twoTurnSession("s-1", base)))
        val thread = db.threadDao().byId(first.threadId)!!
        val open = segmentsOf(first).single { it.state == "OPEN" }
        // 人为破坏不变量（模拟并发/外部异常把 OPEN 封存）：导入必须在事务里失败
        db.segmentDao().upsert(open.copy(state = "SEALED"))

        val segmentsBefore = segmentsOf(first).size
        val messagesBefore = messagesOf(first).size
        val ordinalBefore = db.threadDao().byId(first.threadId)!!.segmentOrdinal

        val second = doImport(
            listOf(twoTurnSession("s-2", base + 10_000)),
            resume = first.characterId,
        )

        assertEquals(1, second.sessionsFailed)
        assertEquals(0, second.sessionsImported)
        assertEquals(0, second.messagesInserted)
        assertTrue(
            "失败原因码应是 OPEN_SEGMENT_MISSING",
            second.issues.any { it.code == ImportException.OPEN_SEGMENT_MISSING },
        )
        assertEquals("不得留下半截 SEALED 段", segmentsBefore, segmentsOf(first).size)
        assertEquals("不得写入半截消息", messagesBefore, messagesOf(first).size)
        assertEquals(
            "thread.segmentOrdinal 不得被推进",
            ordinalBefore,
            db.threadDao().byId(first.threadId)!!.segmentOrdinal,
        )
    }

    // ------------------------------------------------------------------
    // 18. 「查看更早」游标分页（⑤ / 依赖）：同毫秒不重不漏
    // ------------------------------------------------------------------

    @Test
    fun beforeCursorPaginatesWithoutDuplicatesAcrossSameMillisecond() = runBlocking {
        val summary = doImport(listOf(twoTurnSession("s-1", base)))
        val sameAt = base + 50_000
        listOf("m-001", "m-002", "m-003").forEach { id ->
            db.messageDao().upsert(messageFor(summary, id, sameAt))
        }

        val firstPage = db.messageDao().before(summary.threadId, sameAt, "m-003", 2)
        assertEquals(listOf("m-002", "m-001"), firstPage.map { it.id })

        val secondPage = db.messageDao().before(
            summary.threadId,
            firstPage.last().createdAt,
            firstPage.last().id,
            99,
        )
        assertTrue(
            "翻页不能重复",
            secondPage.none { it.id in firstPage.map { page -> page.id } },
        )
        assertEquals("更早的导入消息应出现在下一页", 2, secondPage.size)
    }

    // ------------------------------------------------------------------
    // 19. 多来源分组同一 TA：有序来源边界、续导不重放成功组
    // ------------------------------------------------------------------

    @Test
    fun groupedSourcesKeepBoundariesAndSuccessfulGroupIsNotReplayedAfterResume() = runBlocking {
        val plan = batchPlan("batch-order", "fingerprint-order", null, "group-order", listOf("late", "early", "missing-b", "missing-a"))
        repo.beginBatch(plan)
        val late = twoTurnSession("late", base + 20_000).copy(sourceStartedAt = base + 20_000)
        val early = twoTurnSession("early", base).copy(sourceStartedAt = base)
        val missingB = twoTurnSession("missing-b", base + 30_000).copy(sourceStartedAt = null)
        val missingA = twoTurnSession("missing-a", base + 40_000).copy(sourceStartedAt = null)

        val first = repo.commitBatchGroup(plan.id, "group-order", listOf(missingB, late, early, missingA))
        assertEquals(4, first.sourceSessionsImported)
        assertEquals(8, first.messagesInserted)
        val sourceRows = repo.importSourcesForThread(first.threadId!!)
        assertEquals(listOf("early", "late", "missing-a", "missing-b"), sourceRows.map { it.sourceSessionId })
        assertEquals(listOf(2, 2, 2, 2), sourceRows.map { it.messageCount })
        assertEquals(4, sourceRows.map { it.segmentId }.distinct().size)

        repo.finishBatch(plan.id, ImportRepository.BATCH_CANCELLED, ImportRepository.POST_PROCESS_PENDING)
        val resumed = requireNotNull(repo.resumableBatch(plan.fingerprint))
        assertEquals(ImportRepository.GROUP_SUCCESS, resumed.groups.single().status)
        repo.beginBatch(resumed)
        val retry = repo.commitBatchGroup(plan.id, "group-order", listOf(early, late, missingA, missingB))
        assertEquals(8, retry.messagesInserted)
        assertEquals(8, db.messageDao().allForThread(first.threadId).size)
        assertEquals(4, repo.importSourcesForThread(first.threadId).size)
    }

    @Test
    fun previewAssignmentsPersistForNewExistingAndSkippedDestinations() = runBlocking {
        val existing = doImport(listOf(twoTurnSession("assignment-seed", base)))
        fun preview(id: String, at: Long?) = DeepSeekSessionPreview(
            entryName = "chat_sessions/$id.json",
            sourceSessionId = id,
            title = "Synthetic $id",
            sourceStartedAt = at,
            messageCount = 2,
        )
        val plan = ImportBatchPlan(
            id = "batch-assignments",
            fingerprint = "fingerprint-assignments",
            zipName = "synthetic.zip",
            groups = listOf(
                ImportBatchGroupPlan("existing-group", existing.characterId, "Existing", "ignored"),
                ImportBatchGroupPlan("new-group", null, "New", "synthetic persona"),
            ),
            windows = listOf(
                ImportBatchWindowPlan(preview("to-existing", base), "existing-group"),
                ImportBatchWindowPlan(preview("to-new", base + 10_000), "new-group"),
                ImportBatchWindowPlan(preview("skip-me", null), null),
            ),
        )

        repo.beginBatch(plan)
        repo.finishBatch(plan.id, ImportRepository.BATCH_CANCELLED, ImportRepository.POST_PROCESS_PENDING)
        val restored = requireNotNull(repo.resumableBatch(plan.fingerprint))

        assertEquals(setOf("existing-group", "new-group"), restored.groups.map { it.id }.toSet())
        assertEquals(existing.characterId, restored.groups.single { it.id == "existing-group" }.targetCharacterId)
        assertEquals(
            mapOf("to-existing" to "existing-group", "to-new" to "new-group", "skip-me" to null),
            restored.windows.associate { it.preview.sourceSessionId to it.groupId },
        )
        assertEquals("Synthetic to-new", restored.windows.single { it.preview.sourceSessionId == "to-new" }.preview.title)
        assertEquals(2, restored.windows.single { it.preview.sourceSessionId == "to-new" }.preview.messageCount)
        assertEquals(base + 10_000, restored.windows.single { it.preview.sourceSessionId == "to-new" }.preview.sourceStartedAt)
        assertTrue(repo.importSourcesForThread(existing.threadId).isEmpty())
    }

    // ------------------------------------------------------------------
    // 20. 组事务失败不留半组，失败/取消状态可续导重试
    // ------------------------------------------------------------------

    @Test
    fun groupedCommitFailureLeavesNoSourceOrPartialMessagesAndCanRetry() = runBlocking {
        val existing = doImport(listOf(twoTurnSession("existing", base)))
        val open = segmentsOf(existing).single { it.state == "OPEN" }
        val plan = batchPlan("batch-retry", "fingerprint-retry", existing.characterId, "group-retry", listOf("new"))
        repo.beginBatch(plan)
        db.segmentDao().upsert(open.copy(state = "SEALED"))

        val error = runCatching {
            repo.commitBatchGroup(plan.id, "group-retry", listOf(twoTurnSession("new", base + 20_000)))
        }.exceptionOrNull()
        assertTrue(error is ImportException)
        assertEquals(2, db.messageDao().allForThread(existing.threadId).size)
        assertTrue(repo.importSourcesForThread(existing.threadId).isEmpty())
        assertEquals(ImportRepository.GROUP_PENDING, db.importBatchDao().group("group-retry")?.status)

        db.segmentDao().upsert(open)
        repo.finishBatch(plan.id, ImportRepository.BATCH_CANCELLED, ImportRepository.POST_PROCESS_PENDING)
        val resumed = requireNotNull(repo.resumableBatch(plan.fingerprint))
        assertEquals(ImportRepository.GROUP_PENDING, resumed.groups.single().status)
        repo.beginBatch(resumed)
        val result = repo.commitBatchGroup(plan.id, "group-retry", listOf(twoTurnSession("new", base + 20_000)))
        assertEquals(2, result.messagesInserted)
        assertEquals(1, repo.importSourcesForThread(existing.threadId).size)
    }

    @Test
    fun cancellationInsideGroupRollsBackEarlierSourceWritesAndNewTarget() = runBlocking {
        val plan = batchPlan("batch-cancel-group", "fingerprint-cancel-group", null, "group-cancel-group", listOf("first", "second"))
        repo.beginBatch(plan)
        val sessions = listOf(twoTurnSession("first", base), twoTurnSession("second", base + 10_000))
        var cancellationChecks = 0

        val error = runCatching {
            repo.commitBatchGroup(
                plan.id,
                "group-cancel-group",
                sessions,
                isCancelled = { ++cancellationChecks >= 4 },
            )
        }.exceptionOrNull()

        assertTrue(error is java.util.concurrent.CancellationException)
        assertTrue(db.characterDao().all().isEmpty())
        assertTrue(db.threadDao().allByKind(ThreadKind.SINGLE).isEmpty())
        assertTrue(db.importBatchDao().sourcesForBatch(plan.id).isEmpty())
        assertTrue(db.importBatchDao().windows(plan.id).all { it.status == ImportRepository.WINDOW_PENDING })
        assertEquals(ImportRepository.GROUP_PENDING, db.importBatchDao().group("group-cancel-group")?.status)
    }

    // ------------------------------------------------------------------
    // 21. 来源 ID 跨 TA 防重；历史/备份里已有的跨 TA 重复行仍保留
    // ------------------------------------------------------------------

    @Test
    fun duplicateSourceIsGlobalAndPreservesExistingCrossTargetRows() = runBlocking {
        val firstTarget = doImport(listOf(twoTurnSession("seed-a", base)))
        val secondTarget = doImport(listOf(twoTurnSession("seed-b", base + 50_000)))
        val session = twoTurnSession("shared-source", base + 100_000)

        val firstPlan = batchPlan("batch-dup-a", "fingerprint-dup-a", firstTarget.characterId, "group-dup-a", listOf("shared-source"))
        repo.beginBatch(firstPlan)
        val first = repo.commitBatchGroup(firstPlan.id, "group-dup-a", listOf(session))
        assertEquals(2, first.messagesInserted)
        repo.finishBatch(firstPlan.id, ImportRepository.BATCH_COMPLETE, ImportRepository.POST_PROCESS_DONE)
        val firstSource = repo.importSourcesForThread(firstTarget.threadId).single { it.sourceSessionId == "shared-source" }
        // Simulate a historical backup that already contains the same source under another TA. The
        // schema deliberately keeps the old per-TA unique key, so both rows can be preserved.
        db.importBatchDao().upsertSources(
            listOf(
                firstSource.copy(
                    id = "legacy-cross-target-source-row",
                    batchId = "legacy-backup-batch",
                    targetCharacterId = secondTarget.characterId,
                    targetThreadId = secondTarget.threadId,
                    segmentId = "legacy-cross-target-segment",
                ),
            ),
        )
        assertEquals(2, db.importBatchDao().sourcesForSessionIds(listOf("shared-source")).size)

        val duplicatePlan = batchPlan("batch-dup-a2", "fingerprint-dup-a2", firstTarget.characterId, "group-dup-a2", listOf("shared-source"))
        repo.beginBatch(duplicatePlan)
        val duplicate = repo.commitBatchGroup(duplicatePlan.id, "group-dup-a2", listOf(session))
        assertEquals(0, duplicate.messagesInserted)
        assertEquals(1, duplicate.sourceSessionsSkipped)
        assertEquals(2, duplicate.messagesExisting)
        assertEquals(1, repo.importSourcesForThread(firstTarget.threadId).count { it.sourceSessionId == "shared-source" })

        val otherTargetPlan = batchPlan("batch-dup-b", "fingerprint-dup-b", secondTarget.characterId, "group-dup-b", listOf("shared-source"))
        repo.beginBatch(otherTargetPlan)
        val otherTarget = repo.commitBatchGroup(otherTargetPlan.id, "group-dup-b", listOf(session))
        assertEquals(0, otherTarget.messagesInserted)
        assertEquals(1, otherTarget.sourceSessionsSkipped)
        val preservedBackupSource = repo.importSourcesForThread(secondTarget.threadId).single { it.sourceSessionId == "shared-source" }
        assertEquals("legacy-cross-target-source-row", preservedBackupSource.id)
        assertEquals("legacy-backup-batch", preservedBackupSource.batchId)
        assertEquals(2, db.importBatchDao().sourcesForSessionIds(listOf("shared-source")).size)
    }

    @Test
    fun legacyImportIsDetectedByExactDeterministicMessageIdsAcrossTargets() = runBlocking {
        val legacyTarget = doImport(listOf(twoTurnSession("legacy-source", base)))
        val otherTarget = doImport(listOf(twoTurnSession("other-seed", base + 50_000)))
        val preview = DeepSeekSessionPreview(
            entryName = "chat_sessions/legacy-source.json",
            sourceSessionId = "legacy-source",
            title = "Synthetic legacy source",
            sourceStartedAt = base,
            messageCount = 2,
            sourceMessageIds = listOf(1L, 2L),
        )
        assertEquals(setOf("legacy-source"), repo.importedSourceSessionIds(listOf(preview)))

        val plan = batchPlan("batch-legacy-dup", "fingerprint-legacy-dup", otherTarget.characterId, "group-legacy-dup", listOf("legacy-source"))
        repo.beginBatch(plan)
        val result = repo.commitBatchGroup(plan.id, "group-legacy-dup", listOf(twoTurnSession("legacy-source", base)))

        assertEquals(0, result.messagesInserted)
        assertEquals(1, result.sourceSessionsSkipped)
        assertEquals(2, result.messagesExisting)
        assertTrue(repo.importSourcesForThread(otherTarget.threadId).none { it.sourceSessionId == "legacy-source" })
        assertEquals(2, db.messageDao().allForThread(legacyTarget.threadId).size)
        assertEquals(2, db.messageDao().allForThread(otherTarget.threadId).size)
    }

    // ------------------------------------------------------------------
    // 22. 撤销只清本批来源/索引，保护已有消息/记忆和之后新增内容
    // ------------------------------------------------------------------

    @Test
    fun batchUndoPreservesExistingAndLaterMessagesMemoryAndIndexes() = runBlocking {
        val existing = doImport(listOf(twoTurnSession("old-source", base)))
        val oldSegment = existing.segments.single().segmentId
        val oldMemory = memoryRow(existing.threadId, oldSegment)
        db.memoryDao().insertAll(listOf(oldMemory))
        val oldMessages = db.messageDao().bySegment(oldSegment)
        val oldMessage = oldMessages.first()

        val plan = batchPlan("batch-undo-safe", "fingerprint-undo-safe", existing.characterId, "group-undo-safe", listOf("imported-source"))
        repo.beginBatch(plan)
        repo.commitBatchGroup(plan.id, "group-undo-safe", listOf(twoTurnSession("imported-source", base + 20_000)))
        repo.finishBatch(plan.id, ImportRepository.BATCH_COMPLETE, ImportRepository.POST_PROCESS_DONE)
        val importedSource = repo.importSourcesForThread(existing.threadId).single { it.sourceSessionId == "imported-source" }
        val importedMessageId = db.messageDao().bySegment(importedSource.segmentId).first().id

        val activeSegment = requireNotNull(db.threadDao().byId(existing.threadId)?.activeSegmentId)
        val laterAt = System.currentTimeMillis() + 10_000
        val later = chat.append(existing.threadId, activeSegment, roleUser, "synthetic later chat", 1, createdAt = laterAt)
        // ChatRepository.append -> ThreadDao.touch already advances totalMessages.
        val oldChunk = syntheticChunk("old-chunk", existing.threadId, oldSegment, oldMessage.id)
        val batchChunk = syntheticChunk("batch-chunk", existing.threadId, importedSource.segmentId, importedMessageId)
        val laterChunk = syntheticChunk("later-chunk", existing.threadId, activeSegment, later.id)
        db.chunkDao().insertAll(listOf(oldChunk, batchChunk, laterChunk))
        db.chunkDao().insertTerms(
            listOf(
                ChunkTermEntity("keep-old", oldChunk.id, existing.threadId, 1),
                ChunkTermEntity("remove-batch", batchChunk.id, existing.threadId, 1),
                ChunkTermEntity("keep-later", laterChunk.id, existing.threadId, 1),
            ),
        )

        repo.undoBatch(plan.id)

        val remaining = db.messageDao().allForThread(existing.threadId)
        assertEquals((oldMessages.map { it.id } + later.id).toSet(), remaining.map { it.id }.toSet())
        assertTrue(db.segmentDao().forThread(existing.threadId).none { it.id == importedSource.segmentId })
        assertEquals(listOf(oldChunk.id), db.chunkDao().forSegment(oldSegment).map { it.id })
        assertTrue(db.chunkDao().forSegment(importedSource.segmentId).isEmpty())
        assertEquals(listOf(laterChunk.id), db.chunkDao().forSegment(activeSegment).map { it.id })
        val searchableIds = db.chunkDao().searchTerms(existing.threadId, listOf("keep-old", "remove-batch", "keep-later"), 10)
            .map { it.chunkId }.toSet()
        assertEquals(setOf(oldChunk.id, laterChunk.id), searchableIds)
        assertEquals(listOf(oldMemory), db.memoryDao().allForThread(existing.threadId))
        assertTrue(repo.importSourcesForThread(existing.threadId).isEmpty())
        assertEquals(3, db.threadDao().byId(existing.threadId)?.totalMessages)
        assertEquals(laterAt, db.threadDao().byId(existing.threadId)?.lastMessageAt)
    }

    // ------------------------------------------------------------------
    // 23. 记忆变化时撤销 fail-closed：任何来源消息和索引都不部分删除
    // ------------------------------------------------------------------

    @Test
    fun batchUndoRefusesAllDeletionIfMemoryChanged() = runBlocking {
        val existing = doImport(listOf(twoTurnSession("memory-seed", base)))
        db.memoryDao().insertAll(listOf(memoryRow(existing.threadId)))
        val plan = batchPlan("batch-memory-guard", "fingerprint-memory-guard", existing.characterId, "group-memory-guard", listOf("memory-import"))
        repo.beginBatch(plan)
        repo.commitBatchGroup(plan.id, "group-memory-guard", listOf(twoTurnSession("memory-import", base + 20_000)))
        repo.finishBatch(plan.id, ImportRepository.BATCH_COMPLETE, ImportRepository.POST_PROCESS_DONE)
        val source = repo.importSourcesForThread(existing.threadId).single { it.sourceSessionId == "memory-import" }
        val importedCount = db.messageDao().allForThread(existing.threadId).size
        db.memoryDao().insertAll(listOf(memoryRow(existing.threadId).copy(value = "later synthetic memory")))

        val error = runCatching { repo.undoBatch(plan.id) }.exceptionOrNull()
        assertTrue(error is ImportBatchException)
        assertEquals(ImportBatchException.MEMORY_CHANGED, (error as ImportBatchException).code)
        assertEquals(importedCount, db.messageDao().allForThread(existing.threadId).size)
        assertTrue(db.segmentDao().forThread(existing.threadId).any { it.id == source.segmentId })
        assertTrue(repo.importSourcesForThread(existing.threadId).any { it.sourceSessionId == "memory-import" })
        assertEquals(ImportRepository.BATCH_COMPLETE, db.importBatchDao().batch(plan.id)?.status)
    }

    @Test
    fun relationOnlyMemoryChangeAlsoBlocksBatchUndo() = runBlocking {
        val existing = doImport(listOf(twoTurnSession("relation-seed", base)))
        val plan = batchPlan("batch-relation-guard", "fingerprint-relation-guard", existing.characterId, "group-relation-guard", listOf("relation-import"))
        repo.beginBatch(plan)
        repo.commitBatchGroup(plan.id, "group-relation-guard", listOf(twoTurnSession("relation-import", base + 20_000)))
        repo.finishBatch(plan.id, ImportRepository.BATCH_COMPLETE, ImportRepository.POST_PROCESS_DONE)
        val source = repo.importSourcesForThread(existing.threadId).single { it.sourceSessionId == "relation-import" }
        val messageCount = db.messageDao().allForThread(existing.threadId).size
        db.threadStateDao().upsert(ThreadStateEntity(existing.threadId, "synthetic stage", "synthetic nick", null, "synthetic mood", base + 30_000))

        val error = runCatching { repo.undoBatch(plan.id) }.exceptionOrNull()
        assertTrue(error is ImportBatchException)
        assertEquals(ImportBatchException.MEMORY_CHANGED, (error as ImportBatchException).code)
        assertEquals(messageCount, db.messageDao().allForThread(existing.threadId).size)
        assertTrue(repo.importSourcesForThread(existing.threadId).any { it.segmentId == source.segmentId })
        assertEquals(ImportRepository.BATCH_COMPLETE, db.importBatchDao().batch(plan.id)?.status)
    }

    @Test
    fun delayedMemoryPatchCannotResurrectUndoneBatchDerivedData() = runBlocking {
        val existing = doImport(listOf(twoTurnSession("late-memory-seed", base)))
        val plan = batchPlan("batch-late-memory", "fingerprint-late-memory", existing.characterId, "group-late-memory", listOf("late-memory-source"))
        repo.beginBatch(plan)
        repo.commitBatchGroup(plan.id, "group-late-memory", listOf(twoTurnSession("late-memory-source", base + 20_000)))
        repo.finishBatch(plan.id, ImportRepository.BATCH_COMPLETE, ImportRepository.POST_PROCESS_DONE)
        val source = repo.importSourcesForThread(existing.threadId).single { it.sourceSessionId == "late-memory-source" }
        val importedMessageId = db.messageDao().bySegment(source.segmentId).first().id

        repo.undoBatch(plan.id)

        val memory = MemoryRepository(db)
        val citedLatePatch = """{"facts":[{"k":"synthetic","v":"derived after undo","conf":0.95,"source_message_ids":["$importedMessageId"]}]}"""
        assertFalse(memory.applyPatch(existing.threadId, citedLatePatch, segmentId = null))
        val sourceLessLatePatch = """{"facts":[{"k":"synthetic","v":"unattributed late result","conf":0.95}]}"""
        assertFalse(memory.applyPatch(existing.threadId, sourceLessLatePatch, segmentId = null))
        assertTrue(db.memoryDao().allForThread(existing.threadId).isEmpty())
    }

    @Test
    fun lateBatchIndexerCannotRecreateChunksAfterUndo() = runBlocking {
        val plan = batchPlan("batch-late-index", "fingerprint-late-index", null, "group-late-index", listOf("index-source"))
        repo.beginBatch(plan)
        val result = repo.commitBatchGroup(plan.id, "group-late-index", listOf(twoTurnSession("index-source", base)))
        val source = repo.importSourcesForThread(result.threadId!!).single()
        val messageId = db.messageDao().bySegment(source.segmentId).first().id
        val chunk = syntheticChunk("late-index-chunk", result.threadId, source.segmentId, messageId)
        val term = ChunkTermEntity("late-index-term", chunk.id, result.threadId, 1)

        assertTrue(repo.saveBatchChunksIfActive(plan.id, listOf(chunk), listOf(term)))
        repo.finishBatch(plan.id, ImportRepository.BATCH_COMPLETE, ImportRepository.POST_PROCESS_DONE)
        repo.undoBatch(plan.id)

        assertFalse(repo.saveBatchChunksIfActive(plan.id, listOf(chunk), listOf(term)))
        assertTrue(db.chunkDao().forSegment(source.segmentId).isEmpty())
        assertTrue(db.chunkDao().searchTerms(result.threadId, listOf("late-index-term"), 10).isEmpty())
    }

    @Test
    fun finalBatchChunkAndCompletedSourceCheckpointCommitTogether() = runBlocking {
        val plan = batchPlan("batch-index-checkpoint", "fingerprint-index-checkpoint", null, "group-index-checkpoint", listOf("checkpoint-source"))
        repo.beginBatch(plan)
        val result = repo.commitBatchGroup(
            plan.id,
            "group-index-checkpoint",
            listOf(twoTurnSession("checkpoint-source", base)),
        )
        val threadId = requireNotNull(result.threadId)
        val source = repo.importSourcesForThread(threadId).single()
        val messageId = db.messageDao().bySegment(source.segmentId).first().id
        val chunk = syntheticChunk("checkpoint-final-chunk", threadId, source.segmentId, messageId)
        val term = ChunkTermEntity("checkpoint-final-term", chunk.id, threadId, 1)

        assertTrue(
            repo.saveBatchChunksIfActive(
                batchId = plan.id,
                chunks = listOf(chunk),
                terms = listOf(term),
                sourceIndexComplete = true,
            ),
        )

        val restoredRepository = ImportRepository(db)
        assertEquals(
            "最终 chunk 和来源完成状态在同一写入事务后可同时读取",
            ImportRepository.INDEX_DONE,
            db.importBatchDao().sourcesForBatch(plan.id).single().indexStatus,
        )
        assertEquals(listOf(chunk.id), db.chunkDao().forSegment(source.segmentId).map { it.id })
        assertEquals(
            "进程重启后续导计划不应重放已完成来源",
            emptyList<String>(),
            restoredRepository.importSegmentsNeedingIndexForBatch(plan.id, threadId).map { it.sourceSessionId },
        )
    }

    // ------------------------------------------------------------------
    // 24. 新建 TA 若后来收到聊天，撤销保留 TA 并精确修复计数
    // ------------------------------------------------------------------

    @Test
    fun undoKeepsNewCharacterWithLaterChatAndRemovesOnlyBatchCount() = runBlocking {
        val plan = batchPlan("batch-new-with-chat", "fingerprint-new-with-chat", null, "group-new-with-chat", listOf("new-target-source"))
        repo.beginBatch(plan)
        val result = repo.commitBatchGroup(plan.id, "group-new-with-chat", listOf(twoTurnSession("new-target-source", base)))
        repo.finishBatch(plan.id, ImportRepository.BATCH_COMPLETE, ImportRepository.POST_PROCESS_DONE)
        val thread = db.threadDao().byId(result.threadId!!)!!
        val activeSegment = requireNotNull(thread.activeSegmentId)
        val laterAt = System.currentTimeMillis() + 10_000
        val later = chat.append(thread.id, activeSegment, roleUser, "synthetic later chat", 1, createdAt = laterAt)
        // ChatRepository.append -> ThreadDao.touch already advances totalMessages.

        repo.undoBatch(plan.id)

        assertNotNull(db.characterDao().byId(result.characterId!!))
        assertEquals(listOf(later.id), db.messageDao().allForThread(thread.id).map { it.id })
        assertEquals(1, db.threadDao().byId(thread.id)?.totalMessages)
        assertEquals(laterAt, db.threadDao().byId(thread.id)?.lastMessageAt)
    }
}
