package cc.pulsez.app.core

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import cc.pulsez.app.AppContainer
import cc.pulsez.app.data.db.MessageEntity
import cc.pulsez.app.data.db.SegmentEntity
import cc.pulsez.app.data.repo.ImportBatchGroupPlan
import cc.pulsez.app.data.repo.ImportBatchPlan
import cc.pulsez.app.data.repo.ImportBatchWindowPlan
import cc.pulsez.app.data.repo.ImportRepository
import cc.pulsez.app.data.settings.SettingsStore
import cc.pulsez.app.importer.deepseek.DeepSeekSessionPreview
import cc.pulsez.app.importer.deepseek.DeepSeekTurn
import cc.pulsez.app.importer.deepseek.ParsedDeepSeekSession
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ChatEngineImportTailTest {

    private lateinit var context: Context
    private var container: AppContainer? = null

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.deleteDatabase(APP_DB_NAME)
        runBlocking { SettingsStore(context).update { it.clear() } }
    }

    @After
    fun tearDown() {
        runCatching { container?.database?.close() }
        context.deleteDatabase(APP_DB_NAME)
    }

    private fun seg(id: String, ordinal: Int, state: String, batonReason: String?): SegmentEntity = SegmentEntity(
        id = id,
        threadId = "t1",
        ordinal = ordinal,
        summary = null,
        styleNotes = null,
        firstMessageAt = 1_000L,
        lastMessageAt = 2_000L,
        tokenSum = 100,
        state = state,
        batonReason = batonReason,
        createdAt = 1_000L,
    )

    private fun msg(id: String, role: String, content: String, at: Long): MessageEntity = MessageEntity(
        id = id,
        threadId = "t1",
        segmentId = "s1",
        role = role,
        content = content,
        tokens = content.length + 4,
        emotion = null,
        status = "OK",
        draft = null,
        usageIn = null,
        usageOut = null,
        previousContent = null,
        createdAt = at,
    )

    @Test
    fun findLatestImportSegmentMatchesOnlyWhenLastSealedBeforeOpenIsImport() {
        val segmentsAfterImport = listOf(
            seg("imp-1", 0, "SEALED", ChatEngine.IMPORT_REASON),
            seg("imp-2", 1, "SEALED", ChatEngine.IMPORT_REASON),
            seg("open-1", 2, "OPEN", "INIT"),
        )
        assertEquals(
            "imp-2",
            ChatEngine.findLatestImportSegmentBeforeOpen(segmentsAfterImport, "open-1")?.id,
        )

        val segmentsAfterBatonSeal = listOf(
            seg("imp-1", 0, "SEALED", ChatEngine.IMPORT_REASON),
            seg("sealed-budget", 1, "SEALED", "BUDGET"),
            seg("open-2", 2, "OPEN", "BUDGET"),
        )
        assertNull(
            "正常交接封存过之后不再回头取旧导入段尾部",
            ChatEngine.findLatestImportSegmentBeforeOpen(segmentsAfterBatonSeal, "open-2"),
        )
    }

    @Test
    fun takeImportTailRowsCapsByCountAnd12000CharsInOriginalOrder() {
        val fortyMessages = (1..40).map { idx ->
            val role = if (idx % 2 == 1) "user" else "assistant"
            msg("m-$idx", role, "第 $idx 句", idx.toLong())
        }
        val tail30 = ChatEngine.takeImportTailRows(fortyMessages, maxMessages = 30, maxChars = 12_000)
        assertEquals(30, tail30.size)
        assertEquals((11..40).map { "m-$it" }, tail30.map { it.id })

        val longMessages = (1..10).map { idx ->
            msg("long-$idx", if (idx % 2 == 1) "user" else "assistant", "字".repeat(3_000), idx.toLong())
        }
        val cappedByChars = ChatEngine.takeImportTailRows(longMessages, maxMessages = 30, maxChars = 12_000)
        assertEquals(4, cappedByChars.size)
        assertEquals(listOf("long-7", "long-8", "long-9", "long-10"), cappedByChars.map { it.id })
        assertTrue(cappedByChars.sumOf { it.content.length } <= 12_000)
    }

    @Test
    fun preparePrependsImportTailAndKeepsStyleSamplesAndUndoBatchClean() = runBlocking {
        val app = AppContainer(context)
        container = app
        val importRepo = ImportRepository(app.database)
        val baseAt = 1_700_000_000_000L
        val turns = (1..40).map { idx ->
            DeepSeekTurn(
                role = if (idx % 2 == 1) DeepSeekTurn.ROLE_USER else DeepSeekTurn.ROLE_ASSISTANT,
                text = "旧聊天第 $idx 句",
                at = baseAt + idx * 1_000L,
                sourceMessageId = idx.toLong(),
            )
        }
        val (existingChar, existingThread) = app.characters.createWithThread(
            name = "阿淮",
            persona = "温柔、爱记旧事。",
            styleSamples = listOf("别熬夜，早点睡。", "我在听你说。"),
        )
        val session = ParsedDeepSeekSession(
            sourceSessionId = "s-import-1",
            title = "旧窗口",
            insertedAt = baseAt,
            updatedAt = baseAt + 40_000L,
            turns = turns,
            issues = emptyList(),
        )
        val preview = DeepSeekSessionPreview(
            entryName = "chat_sessions/s-import-1.json",
            sourceSessionId = "s-import-1",
            title = "旧窗口",
            sourceStartedAt = baseAt,
            messageCount = turns.size,
            lastMessageAt = baseAt + 40_000L,
            damageCode = null,
        )
        val plan = ImportBatchPlan(
            id = "batch-tail-test",
            fingerprint = "fp-tail-test",
            zipName = "deepseek.zip",
            groups = listOf(
                ImportBatchGroupPlan(
                    id = "g1",
                    targetCharacterId = existingChar.id,
                    name = "阿淮",
                    persona = "温柔、爱记旧事。",
                ),
            ),
            windows = listOf(
                ImportBatchWindowPlan(
                    preview = preview,
                    groupId = "g1",
                ),
            ),
        )
        importRepo.beginBatch(plan)
        val groupResult = importRepo.commitBatchGroup(plan.id, "g1", listOf(session))
        importRepo.finishBatch(plan.id, ImportRepository.BATCH_COMPLETE, ImportRepository.POST_PROCESS_DONE)
        val thread = app.database.threadDao().byId(groupResult.threadId ?: existingThread.id)!!
        assertNotNull(thread.activeSegmentId)

        // 用户进入续聊：先落库当轮 user 消息，再调用 prepare
        val currentUserMsg = app.chat.append(
            threadId = thread.id,
            segmentId = thread.activeSegmentId!!,
            role = "user",
            text = "我回来啦，还记得刚刚说的吗？",
            tokens = 12,
        )
        val prepared = app.engine.prepare(
            thread = thread,
            characterId = existingChar.id,
            userText = "我回来啦，还记得刚刚说的吗？",
            excludeMessageId = currentUserMsg.id,
        )

        // ① system 含干净的「TA：」语气样本，不含「旁白：TA 的语气示例：」
        val firstSystem = prepared.messages.first().content
        assertTrue(firstSystem.contains("TA：别熬夜，早点睡。"))
        assertFalse(firstSystem.contains("旁白：TA 的语气示例："))

        // ③ history 包含最新导入段末尾 30 条（旧聊天第 11..40 句）
        val historyMessages = prepared.messages.drop(1).dropLast(1)
        assertEquals(30, historyMessages.size)
        assertEquals("旧聊天第 11 句", historyMessages.first().content)
        assertEquals("旧聊天第 40 句", historyMessages.last().content)

        // ⑤ user 只有最新一句，且由于无记忆与召回，中间没有插空 ④ [system]
        assertEquals("user", prepared.messages.last().role)
        assertEquals("我回来啦，还记得刚刚说的吗？", prepared.messages.last().content)
        assertTrue(prepared.messages.none { it.content.contains("【临场块】") })

        // 另外验证：新建角色导入后仅做只读 prepare，ImportRepository.undoBatch 仍可 100% 干净撤销
        val undoSession = session.copy(sourceSessionId = "s-import-2")
        val undoPlan = ImportBatchPlan(
            id = "batch-tail-undo",
            fingerprint = "fp-tail-undo",
            zipName = "deepseek2.zip",
            groups = listOf(
                ImportBatchGroupPlan(
                    id = "g2",
                    targetCharacterId = null,
                    name = "林野",
                    persona = "安静",
                ),
            ),
            windows = listOf(
                ImportBatchWindowPlan(
                    preview = preview.copy(sourceSessionId = "s-import-2"),
                    groupId = "g2",
                ),
            ),
        )
        importRepo.beginBatch(undoPlan)
        val g2Result = importRepo.commitBatchGroup(undoPlan.id, "g2", listOf(undoSession))
        importRepo.finishBatch(undoPlan.id, ImportRepository.BATCH_COMPLETE, ImportRepository.POST_PROCESS_DONE)
        val g2CharId = g2Result.characterId!!
        val g2Thread = app.database.threadDao().byId(g2Result.threadId!!)!!
        val readOnlyPrepared = app.engine.prepare(g2Thread, g2CharId, "试一下")
        assertTrue(readOnlyPrepared.messages.any { it.content == "旧聊天第 40 句" })
        // prepare 全程只读，撤销必须 100% 成功
        importRepo.undoBatch(undoPlan.id)
        assertNull(app.database.characterDao().byId(g2CharId))
    }

    private companion object {
        const val APP_DB_NAME = "pulsez.db"
    }
}
