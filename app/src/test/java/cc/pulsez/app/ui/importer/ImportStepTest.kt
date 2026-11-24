package cc.pulsez.app.ui.importer

import cc.pulsez.app.importer.BackupInspection
import cc.pulsez.app.importer.BackupIssue
import cc.pulsez.app.importer.BackupPreview
import cc.pulsez.app.importer.Format
import cc.pulsez.app.importer.ParsedImport
import cc.pulsez.app.importer.Speaker
import cc.pulsez.app.ui.copy.BackupCopy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 导入页纯状态函数测试。
 *
 * 纯 JVM：不依赖 Compose/Android。锁住步骤优先级、标题映射、两态互斥与退出清理清单；
 * 「EDIT 步点『导入文件』不清 raw」是调用点/源码扫描项，本测试不假装覆盖。
 */
class ImportStepTest {

    private fun preview(skipped: Int = 0) = BackupPreview(
        schema = 3,
        exportedAt = 1L,
        characterCount = 2,
        threadCount = 1,
        messageCount = 3,
        attachmentCount = 4,
        skippedOrphanThreads = skipped,
    )

    private fun parsed() = ParsedImport(
        name = "阿淮",
        persona = "温和",
        greeting = null,
        styleSamples = emptyList(),
        turns = emptyList(),
        format = Format.TEXT,
        warnings = listOf("提示"),
    )

    private fun dirtyState() = ImportUiState(
        raw = "原文",
        parsed = parsed(),
        backup = preview(),
        backupRejected = BackupIssue.DAMAGED,
        name = "名字",
        persona = "人设",
        speakerSide = Speaker.USER,
        warnings = listOf("旧提示"),
        busy = true,
        createdId = "char-old",
        error = "旧错误",
    )

    // ---------------------------------------------------------------- 步骤优先级

    @Test
    fun `importStep 优先级 backup 高于 rejected 高于 parsed 高于 EDIT`() {
        assertEquals(ImportStep.EDIT, importStep(ImportUiState()))
        assertEquals(ImportStep.PARSE_PREVIEW, importStep(ImportUiState(parsed = parsed())))
        assertEquals(
            ImportStep.BACKUP_REJECTED,
            importStep(ImportUiState(parsed = parsed(), backupRejected = BackupIssue.DAMAGED)),
        )
        // 两者共存时 backup 优先（正常流程互斥不会共存；这里锁优先级本身）。
        assertEquals(
            ImportStep.BACKUP_PREVIEW,
            importStep(ImportUiState(backup = preview(), backupRejected = BackupIssue.DAMAGED, parsed = parsed())),
        )
    }

    @Test
    fun `importTitle 四态映射 备份两态取 BackupCopy`() {
        assertEquals("新建角色", importTitle(ImportUiState()))
        assertEquals("看看对不对", importTitle(ImportUiState(parsed = parsed())))
        assertEquals(BackupCopy.PREVIEW_TITLE, importTitle(ImportUiState(backup = preview())))
        assertEquals(BackupCopy.REJECT_TITLE, importTitle(ImportUiState(backupRejected = BackupIssue.TOO_NEW)))
    }

    // ---------------------------------------------------------------- applyInspection 互斥与清理

    @Test
    fun `Preview 结果 设置 backup 清 rejected 与草稿`() {
        val out = applyInspection(dirtyState(), BackupInspection.Preview(preview(skipped = 1)))
        assertEquals(ImportStep.BACKUP_PREVIEW, importStep(out))
        assertEquals(preview(skipped = 1), out.backup)
        assertNull("两态永不共存", out.backupRejected)
        assertEquals("备份原文不得进输入框", "", out.raw)
        assertNull(out.parsed)
        assertEquals("", out.name)
        assertEquals("", out.persona)
        assertEquals(Speaker.UNKNOWN, out.speakerSide)
        assertTrue(out.warnings.isEmpty())
        assertNull(out.error)
    }

    @Test
    fun `Rejected 结果 设置 rejected 清 backup 与草稿`() {
        val out = applyInspection(dirtyState(), BackupInspection.Rejected(BackupIssue.TOO_LARGE))
        assertEquals(ImportStep.BACKUP_REJECTED, importStep(out))
        assertEquals(BackupIssue.TOO_LARGE, out.backupRejected)
        assertNull("两态永不共存", out.backup)
        assertEquals("", out.raw)
        assertNull(out.parsed)
        assertEquals("", out.name)
        assertEquals("", out.persona)
        assertNull(out.error)
    }

    @Test
    fun `Normal 结果 两个备份字段都清`() {
        val out = applyInspection(dirtyState(), BackupInspection.Normal)
        assertNull(out.backup)
        assertNull(out.backupRejected)
        // 普通路径不动 raw/parsed 本身（由 VM 写 raw 再解析）。
        assertEquals("原文", out.raw)
        assertNotNull(out.parsed)
    }

    // ---------------------------------------------------------------- clearedForEdit 退出清理

    @Test
    fun `clearedForEdit 清草稿 备份两态 busy createdId`() {
        val out = clearedForEdit(dirtyState())
        assertEquals(ImportUiState(), out)
        assertEquals("", out.raw)
        assertNull(out.parsed)
        assertNull(out.backup)
        assertNull(out.backupRejected)
        assertEquals("", out.name)
        assertEquals("", out.persona)
        assertEquals(Speaker.UNKNOWN, out.speakerSide)
        assertTrue(out.warnings.isEmpty())
        assertEquals("必须复位 busy 防重入", false, out.busy)
        assertNull("必须清 createdId 防旧 id 重导航", out.createdId)
        assertNull(out.error)
    }
}
