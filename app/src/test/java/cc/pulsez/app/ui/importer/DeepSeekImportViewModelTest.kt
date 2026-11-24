package cc.pulsez.app.ui.importer

import cc.pulsez.app.core.ConsolidateResult
import cc.pulsez.app.data.repo.ImportBatchGroupResult
import cc.pulsez.app.data.repo.ImportBatchResult
import cc.pulsez.app.data.repo.ImportSummary
import cc.pulsez.app.importer.deepseek.ImportErrorCodes
import cc.pulsez.app.importer.deepseek.ZipErrorCodes
import java.time.Instant
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 导入页纯函数单测。只覆盖**不碰 Android/Compose**的映射与格式化：
 * 错误码 → 人话（ + 文案纪律）、费用显示（ 粗估）、时间范围提示、
 * 撤销条件与结果页提示。
 *
 * 不测 Compose UI（工程无 UI 测试）；状态机在 `ImportTaskStore`自己的测试里。
 */
class DeepSeekImportViewModelTest {

    private fun summary(
        createdNewCharacter: Boolean = true,
        messagesInserted: Int = 4,
        batchId: String? = null,
    ): ImportSummary = ImportSummary(
        characterId = "char-1",
        threadId = "thread-1",
        sessionsImported = if (messagesInserted > 0) 1 else 0,
        sessionsSkippedExisting = if (messagesInserted > 0) 0 else 1,
        sessionsFailed = 0,
        messagesInserted = messagesInserted,
        messagesExisting = if (messagesInserted > 0) 0 else 4,
        segments = emptyList(),
        issues = emptyList(),
        lastImportedAt = null,
        createdNewCharacter = createdNewCharacter,
        batchId = batchId,
    )

    @Test
    fun `can undo only for newly created character with inserted messages`() {
        assertTrue("新建 TA + 有新消息：提供撤销", canUndoImport(summary()))
        assertFalse(
            "续导（复用旧 TA）：即使补进了新消息也不提供整批撤销",
            canUndoImport(summary(createdNewCharacter = false, messagesInserted = 4)),
        )
        assertFalse(
            "本次 0 新增：撤销「这次」没意义，还会带走旧历史",
            canUndoImport(summary(createdNewCharacter = false, messagesInserted = 0)),
        )
        assertFalse(
            "新建但 0 新增（极端空会话）：不给撤销",
            canUndoImport(summary(createdNewCharacter = true, messagesInserted = 0)),
        )
    }

    @Test
    fun `undo keeps undo button and promises both branches with plain words`() {
        assertNull("能撤销时不显示说明", undoUnavailableHint(summary()))
        assertEquals(
            "这次是接着上次导入的，不提供整批撤销。",
            undoUnavailableHint(summary(createdNewCharacter = false, messagesInserted = 2)),
        )
        assertEquals(
            "这次没有新增内容。",
            undoUnavailableHint(summary(createdNewCharacter = true, messagesInserted = 0)),
        )
        // 文案纪律：不出现内部技术词，角色代词只用 TA。
        listOf(
            undoUnavailableHint(summary(createdNewCharacter = false, messagesInserted = 2))!!,
            undoUnavailableHint(summary(createdNewCharacter = true, messagesInserted = 0))!!,
        ).forEach { text ->
            listOf("token", "上下文", "窗口", "向量", "模型", "她", "他").forEach { word ->
                assertFalse("文案不该出现「$word」：$text", text.contains(word))
            }
        }
    }

    @Test
    fun `undo confirmation describes exact new and existing TA safety`() {
        val newTaText = undoConfirmationText(summary(createdNewCharacter = true, batchId = "batch-1"))
        assertTrue(newTaText.contains("本批新加的聊天"))
        assertTrue(newTaText.contains("已有聊天会保留"))
        assertTrue(newTaText.contains("后来有聊天也会保留"))
        assertTrue(newTaText.contains("记忆有变化时，撤销会拒绝"))

        val existingTaText = undoConfirmationText(summary(createdNewCharacter = false, batchId = "batch-2"))
        assertTrue(existingTaText.contains("这个已有 TA、已有聊天和之后新增的聊天都会保留"))
        assertTrue(existingTaText.contains("记忆有变化时，撤销会拒绝"))

        assertEquals(
            "这个 TA 会从首页隐藏，但已经写入的聊天会保留。",
            undoConfirmationText(summary(createdNewCharacter = true)),
        )
    }

    @Test
    fun `failed grouped import shows the TA name and human retry reason`() {
        val notices = failedImportGroupNotices(
            batch = ImportBatchResult(
                batchId = "batch-failed",
                groups = listOf(
                    ImportBatchGroupResult(
                        groupId = "failed-group",
                        characterId = null,
                        threadId = null,
                        sourceSessionsImported = 0,
                        sourceSessionsSkipped = 0,
                        messagesInserted = 0,
                        errorCode = "IMPORT_TARGET_MISSING",
                    ),
                ),
                skippedUnassigned = 0,
                postProcessStatus = "DONE",
            ),
            groups = listOf(ImportGroupOption(id = "failed-group", name = "TA-合成")),
        )

        assertEquals(1, notices.size)
        assertEquals("TA-合成", notices.single().groupName)
        assertEquals("原来选的 TA 已经不存在。重新导出压缩包后重新分组，再导入。", notices.single().reason)
        assertFalse(notices.single().reason.contains("IMPORT_TARGET_MISSING"))
        assertTrue(FAILED_GROUP_RETRY_HINT.contains("重新选择同一个压缩包"))
        assertTrue(FAILED_GROUP_RETRY_HINT.contains("失败的分组会重试"))
    }

    @Test
    fun `done hint distinguishes nothing to consolidate from not consolidated`() {
        assertEquals(
            "0 片：明确说没有可整理的旧事，不再引导去面板空转",
            "没有可整理的旧事。",
            consolidationDoneHint(
                consolidation = ConsolidateResult(0, 0, 0, 0.0, cancelled = false),
                messagesInserted = 4,
            ),
        )
        assertEquals(
            "没勾整理/放弃整理：引导去记忆面板",
            NOT_CONSOLIDATED_TEXT,
            consolidationDoneHint(consolidation = null, messagesInserted = 4),
        )
        assertNull(
            "真跑过整理：不需要额外提示",
            consolidationDoneHint(
                consolidation = ConsolidateResult(3, 3, 0, 0.3, cancelled = false),
                messagesInserted = 4,
            ),
        )
        assertNull(
            "没写入任何往事：也不提示整理",
            consolidationDoneHint(consolidation = null, messagesInserted = 0),
        )
        assertEquals("没有可整理的旧事。", NOTHING_TO_CONSOLIDATE_TEXT)
    }

    @Test
    fun importErrorTextCoversPlanCodesInPlainChinese() {
        assertEquals("这不是一个压缩包。", importErrorText(ZipErrorCodes.NOT_ZIP))
        assertEquals(
            "这个文件读不出来，可能坏了或者不是 DeepSeek 导出的。重新导出一次试试？",
            importErrorText(ZipErrorCodes.BAD_ZIP),
        )
        assertEquals(
            "这个压缩包不是 DeepSeek 新版的导出文件。请在 DeepSeek 里重新导出一次。",
            importErrorText("VERSION_UNSUPPORTED"),
        )
        assertEquals("压缩包里没有找到对话。", importErrorText(ImportErrorCodes.NO_SESSIONS))
        assertEquals("手机空间不够了。清一点再来？", importErrorText(ZipErrorCodes.LOW_STORAGE))
        assertEquals("已停止。已经导入的都保留着。", importErrorText(ImportErrorCodes.CANCELLED))
        assertEquals("这次没成。再试一次？", importErrorText("SOMETHING_UNKNOWN"))
        assertEquals(importErrorText(ZipErrorCodes.NOT_ZIP), ImportErrorText.of(ZipErrorCodes.NOT_ZIP))
        assertEquals(importErrorText(ZipErrorCodes.NOT_FOUND), ImportErrorText.of(ZipErrorCodes.NOT_FOUND))
    }

    @Test
    fun importErrorTextNeverLeaksTechnicalWords() {
        // amber：文案纪律：错误文案不出现 token/上下文/窗口/向量/模型。
        val banned = listOf("token", "上下文", "窗口", "向量", "模型")
        val codes = listOf(
            ZipErrorCodes.NOT_ZIP,
            ZipErrorCodes.BAD_ZIP,
            ZipErrorCodes.ENCRYPTED_OR_DAMAGED,
            ZipErrorCodes.TOO_LARGE,
            ZipErrorCodes.TOO_MANY_ENTRIES,
            ZipErrorCodes.ENTRY_TOO_LARGE,
            ZipErrorCodes.BAD_ENCODING,
            ZipErrorCodes.CRC_MISMATCH,
            ZipErrorCodes.LOW_STORAGE,
            ImportErrorCodes.NO_SESSIONS,
            ImportErrorCodes.CANCELLED,
            "VERSION_UNSUPPORTED",
        )
        codes.forEach { code ->
            val text = importErrorText(code)
            banned.forEach { word ->
                assertTrue("「$code」的人话不该出现「$word」：$text", !text.contains(word))
            }
        }
    }

    @Test
    fun formatYuanFollowsCopyRules() {
        assertEquals("¥0.10", formatYuan(0.096))
        assertEquals("¥1.00", formatYuan(1.0))
        // ≥100 元不带小数（「数字」）
        assertEquals("¥128", formatYuan(128.4))
        assertEquals("按实际用量算", estimatePhrase(null))
        assertEquals("大概 ¥0.10", estimatePhrase(0.096))
    }

    @Test
    fun formatImportTimeRangeIsHumanAndStable() {
        val zone = ZoneId.of("UTC")
        assertEquals("时间未知", formatImportTimeRange(null, zone))

        fun at(iso: String): Long = Instant.parse(iso).toEpochMilli()
        assertEquals(
            "时间大概 2025年3月–2026年11月",
            formatImportTimeRange(at("2025-03-02T00:00:00Z")..at("2026-11-19T00:00:00Z"), zone),
        )
        assertEquals(
            "时间大概 2025年3月",
            formatImportTimeRange(at("2025-03-02T00:00:00Z")..at("2025-03-28T00:00:00Z"), zone),
        )
        assertEquals("开始时间未知", formatImportSourceStartTime(null, zone))
        assertEquals("2025年3月2日 10:04", formatImportSourceStartTime(at("2025-03-02T10:04:00Z"), zone))
    }

    // ------------------------------------------------------------------ 建向量

    @Test
    fun `embed option copy says what cost who charges and what happens if off`() {
        assertEquals("顺便让 TA 以后能按意思找到这些旧事", ImportSemanticCopy.OPTION)
        val hint = ImportSemanticCopy.HINT
        assertTrue("要说明用哪个服务", hint.contains("找旧事"))
        assertTrue("要说明费用由谁收", hint.contains("费用由你的服务商收取"))
        assertTrue("要说明不勾会怎样", hint.contains("不勾也能导入"))
        assertTrue("要说明不勾的后果（只能按原话找）", hint.contains("只能按原话找"))
        // 文案纪律：不暴露机制词、不用「她/他」。
        listOf(ImportSemanticCopy.OPTION, ImportSemanticCopy.HINT, ImportSemanticCopy.PHASE).forEach { text ->
            listOf("token", "上下文", "窗口", "向量", "embedding", "语义索引", "模型", "索引", "她", "他")
                .forEach { word -> assertFalse("导入页文案不该出现「$word」：$text", text.contains(word)) }
        }
    }

    @Test
    fun `import phase text separates semantic indexing from memory consolidation`() {
        assertEquals("导入中", importPhaseText(ImportTaskState.Running.Phase.IMPORT, semanticIndexing = true))
        assertEquals(
            "建向量阶段要有自己的文案（与「正在整理」「正在整理成记忆」区分）",
            ImportSemanticCopy.PHASE,
            importPhaseText(ImportTaskState.Running.Phase.INDEX, semanticIndexing = true),
        )
        assertEquals("正在整理", importPhaseText(ImportTaskState.Running.Phase.INDEX, semanticIndexing = false))
        assertEquals(
            "正在整理成记忆",
            importPhaseText(ImportTaskState.Running.Phase.CONSOLIDATE, semanticIndexing = true),
        )
    }

    @Test
    fun `embed choice defaults on but only shows when service ready`() {
        val state = DeepSeekImportUiState()
        assertTrue("「顺便按意思找旧事」默认开", state.embedOnImport)
        assertFalse("没配置「找旧事」前不显示该行", state.embedReady)
        assertFalse(state.semanticIndexing)
    }
}
