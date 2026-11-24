package cc.pulsez.app.ui.copy

import cc.pulsez.app.data.repo.RestoreResult
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 恢复文案 + 「不得残留」+ 接线的源码扫描（ T6，纯 JVM）。
 *
 * 三件事：
 * 1. 新常量/函数**逐字**可测（数字一律走 `formatCount`，不硬编码千分位）；
 * 2. 文案纪律：`CopyPolicyTest.TECH_BANNED` 全扫 + 角色代词只 TA + 不出现「可能恢复不了」；
 * `restoreIndexMissing` 按 rev8 用「以后可能找不全」「可能会补上」如实说明局限但**零禁词**；
 * 3. 源码扫描：中间态 `PREVIEW_NOT_READY` 零残留、恢复三段文案真的被 UI 用上（不是只定义）、
 * `AppRoot` 调用点 +2 行、恢复仓库结构上零网络（`embedEnabled = false`）。
 */
class BackupRestoreCopyTest {

    private companion object {
        /** 禁用词（同 `CopyPolicyTest` 口径，**不开任何例外**）。 */
        val TECH_BANNED: List<String> = listOf(
            "token", "上下文", "窗口", "向量", "embedding", "缓存", "关键词", "记忆检索",
            "亲", "小可爱", "您好",
        )

        /** 角色代词只允许 TA。 */
        val PRONOUN_BANNED: List<String> = listOf("她", "他", "我们")

        /** 用户可见文案源码（cwd = `app/`，依据 `DeepSeekRealApiTest.kt:477`）。 */
        val BACKUP_COPY = File("src/main/java/cc/pulsez/app/ui/copy/BackupCopy.kt")
        val IMPORT_SCREEN = File("src/main/java/cc/pulsez/app/ui/importer/ImportScreen.kt")
        val APP_ROOT = File("src/main/java/cc/pulsez/app/ui/AppRoot.kt")
        val RESTORE_REPOSITORY = File("src/main/java/cc/pulsez/app/data/repo/BackupRestoreRepository.kt")

        fun restored(n: Int = 1) = RestoreResult.Restored(
            characterCount = 3,
            threadCount = 2,
            segmentCount = 6,
            messageCount = 128,
            memoryCount = 24,
            memberCount = 2,
            skippedOrphanThreads = n,
            ignoredCharacters = 0,
            ignoredMembers = 1,
            ignoredSpeakers = 1,
            ignoredMemories = 1,
            attachmentCount = 3,
            chunks = 40,
            indexSkipped = 2,
            firstCharacterId = "char-new",
        )
    }

    private val importScreenText: String by lazy { IMPORT_SCREEN.readText(Charsets.UTF_8) }
    private val appRootText: String by lazy { APP_ROOT.readText(Charsets.UTF_8) }
    private val restoreRepositoryText: String by lazy { RESTORE_REPOSITORY.readText(Charsets.UTF_8) }
    private val backupCopyText: String by lazy { BACKUP_COPY.readText(Charsets.UTF_8) }

    // ================================================================== 逐字常量

    @Test
    fun `恢复常量逐字`() {
        assertEquals("恢复", BackupCopy.RESTORE)
        assertEquals("恢复会新增一份 TA 和对话，不会覆盖现在的内容。", BackupCopy.RESTORE_NOTE)
        assertEquals("正在恢复……", BackupCopy.RESTORE_RUNNING)
        assertEquals("恢复好了", BackupCopy.RESTORE_DONE_TITLE)
        assertEquals("这份备份里没有能装回来的内容。", BackupCopy.RESTORE_DONE_EMPTY)
        assertEquals("没恢复成。手机上原来的数据没有动，再试一次？", BackupCopy.RESTORE_FAILED)
        assertEquals("再试一次", BackupCopy.RESTORE_RETRY)
        assertEquals("好", BackupCopy.RESTORE_OK)
        assertEquals("这些不会跟着恢复：", BackupCopy.NOT_RESTORED_TITLE)
        assertEquals("头像（还是用名字首字）", BackupCopy.NOT_RESTORED_AVATAR)
        assertEquals("图片和文件本身（当时发过图的消息只剩文字）", BackupCopy.NOT_RESTORED_FILES)
        assertEquals(
            "按意思找旧事（恢复后只能按原话找；要按意思找，去记忆面板点「让 TA 能按意思找旧事」）",
            BackupCopy.NOT_RESTORED_SEMANTIC,
        )
        assertEquals("已经删掉的消息和 TA", BackupCopy.NOT_RESTORED_DELETED)
        assertEquals("关系阶段、昵称和心情", BackupCopy.NOT_RESTORED_RELATION)
        assertEquals("用量和费用记录（恢复后从零开始记）", BackupCopy.NOT_RESTORED_USAGE)
        assertEquals("服务商地址和密钥（恢复后要重新填）", BackupCopy.NOT_RESTORED_SETTINGS)
        assertEquals("每条记忆当时是从哪句话记下的（记忆本身会回来）", BackupCopy.NOT_RESTORED_MEMORY_SOURCE)
        assertEquals("这一版能把 TA、对话和记忆装回这台手机。", BackupCopy.PREVIEW_READY)
    }

    @Test
    fun `恢复进度与导出时间`() {
        assertEquals("正在恢复……", BackupCopy.restoreProgress(0, 0))
        assertEquals("正在恢复……", BackupCopy.restoreProgress(0, -1))
        assertEquals(
            "正在恢复……（${BackupCopy.formatCount(3)}/${BackupCopy.formatCount(12)} 段对话）",
            BackupCopy.restoreProgress(3, 12),
        )
        assertTrue(BackupCopy.exportedAtText(1_700_000_000_000L).startsWith("导出时间："))
        assertFalse(BackupCopy.exportedAtText(1_700_000_000_000L).contains("不明"))
        assertEquals("导出时间：不明", BackupCopy.exportedAtText(null))
        assertEquals("导出时间：不明", BackupCopy.exportedAtText(0L))
    }

    @Test
    fun `结果计数文案用运行期千分位`() {
        assertEquals(
            "装回来 ${BackupCopy.formatCount(3)} 个 TA、" +
                "${BackupCopy.formatCount(2)} 段对话、" +
                "${BackupCopy.formatCount(128)} 条消息、" +
                "${BackupCopy.formatCount(24)} 条记忆。",
            BackupCopy.restoreDoneCounts(restored()),
        )
        assertEquals("15,000", BackupCopy.formatCount(15_000))
    }

    @Test
    fun `跳过与忽略与附件文案`() {
        assertEquals(
            "有 ${BackupCopy.formatCount(1)} 段对话的 TA 已经不在备份里，没有装回来。",
            BackupCopy.restoreSkippedOrphans(1),
        )
        assertEquals(
            "有 ${BackupCopy.formatCount(2)} 条内容找不到原来属于哪个 TA，已经略过。",
            BackupCopy.restoreIgnoredRefs(2),
        )
        assertEquals(
            "备份里有 ${BackupCopy.formatCount(3)} 张图，图本身不在备份里，装不回来。",
            BackupCopy.restoreFilesNotRestored(3),
        )
        assertEquals(
            "备份里有 ${BackupCopy.formatCount(3)} 张图；图本身不会跟着恢复。",
            BackupCopy.previewFilesNotRestored(3),
        )
    }

    // ================================================================== indexSkipped 块数文案

    @Test
    fun `restoreIndexMissing 按块数逐字锁死`() {
        assertEquals(
            "有 1 处旧事这次没整理好，以后可能找不全。",
            BackupCopy.restoreIndexMissing(1).substringBefore("再恢复一次"),
        )
        assertEquals(
            "有 3 处旧事这次没整理好，以后可能找不全。",
            BackupCopy.restoreIndexMissing(3).substringBefore("再恢复一次"),
        )
        val text = BackupCopy.restoreIndexMissing(3)
        listOf("再恢复一次", "会新增副本", "可能会补上", "不会覆盖").forEach { phrase ->
            assertTrue("restoreIndexMissing 必须含「$phrase」：$text", text.contains(phrase))
        }
        listOf("关键词", "索引", "补建", "面板").forEach { word ->
            assertFalse("restoreIndexMissing 不得出现「$word」：$text", text.contains(word))
        }
        // n 的语义 = 块数：测试用块数调用、文案里的数字随之变化。
        assertTrue(BackupCopy.restoreIndexMissing(1500).contains(BackupCopy.formatCount(1500)))
    }

    // ================================================================== 禁词 / 代词

    @Test
    fun `全部恢复文案零禁词且只用 TA`() {
        val texts = buildList {
            addAll(
                listOf(
                    BackupCopy.PREVIEW_READY,
                    BackupCopy.RESTORE,
                    BackupCopy.RESTORE_NOTE,
                    BackupCopy.RESTORE_RUNNING,
                    BackupCopy.RESTORE_DONE_TITLE,
                    BackupCopy.RESTORE_DONE_EMPTY,
                    BackupCopy.RESTORE_FAILED,
                    BackupCopy.RESTORE_RETRY,
                    BackupCopy.RESTORE_OK,
                    BackupCopy.NOT_RESTORED_TITLE,
                    BackupCopy.NOT_RESTORED_AVATAR,
                    BackupCopy.NOT_RESTORED_FILES,
                    BackupCopy.NOT_RESTORED_SEMANTIC,
                    BackupCopy.NOT_RESTORED_DELETED,
                    BackupCopy.NOT_RESTORED_RELATION,
                    BackupCopy.NOT_RESTORED_USAGE,
                    BackupCopy.NOT_RESTORED_SETTINGS,
                    BackupCopy.NOT_RESTORED_MEMORY_SOURCE,
                    BackupCopy.restoreDoneCounts(restored()),
                    BackupCopy.restoreSkippedOrphans(2),
                    BackupCopy.restoreIgnoredRefs(2),
                    BackupCopy.restoreFilesNotRestored(2),
                    BackupCopy.previewFilesNotRestored(2),
                    BackupCopy.restoreIndexMissing(2),
                    BackupCopy.restoreProgress(1, 3),
                    BackupCopy.exportedAtText(null),
                ),
            )
        }
        texts.forEach { text ->
            TECH_BANNED.forEach { word ->
                assertFalse("恢复文案出现技术词「$word」：$text", text.contains(word))
            }
            PRONOUN_BANNED.forEach { pronoun ->
                assertFalse("恢复文案出现角色代词「$pronoun」（只许 TA）：$text", text.contains(pronoun))
            }
            assertFalse("不得出现推卸式不确定：「可能恢复不了」：$text", text.contains("可能恢复不了"))
        }
    }

    // ================================================================== 不得残留 + UI 真的用上

    private fun braceBlockOf(text: String, anchor: String): String {
        val start = text.indexOf(anchor)
        assertTrue("源码里找不到区块锚点：$anchor", start >= 0)
        var depth = 0
        var seen = false
        var index = start
        while (index < text.length) {
            when (text[index]) {
                '{' -> {
                    depth += 1
                    seen = true
                }
                '}' -> {
                    depth -= 1
                    if (seen && depth == 0) return text.substring(start, index + 1)
                }
            }
            index += 1
        }
        throw AssertionError("区块没有闭合：$anchor")
    }

    /** `ImportScreen(` 调用区块（括号配平，到闭合 `)`）。 */
    private fun importScreenCallBlock(): String {
        val start = appRootText.indexOf("ImportScreen(")
        assertTrue("AppRoot 里找不到 ImportScreen( 调用点", start >= 0)
        var depth = 0
        var index = start
        while (index < appRootText.length) {
            when (appRootText[index]) {
                '(' -> depth += 1
                ')' -> {
                    depth -= 1
                    if (depth == 0) return appRootText.substring(start, index + 1)
                }
            }
            index += 1
        }
        throw AssertionError("ImportScreen( 调用没有闭合")
    }

    @Test
    fun `源码非空前置`() {
        assertTrue("BackupCopy.kt 源码为空", backupCopyText.isNotBlank())
        assertTrue("ImportScreen.kt 源码为空", importScreenText.isNotBlank())
        assertTrue("AppRoot.kt 源码为空", appRootText.isNotBlank())
        assertTrue("BackupRestoreRepository.kt 源码为空", restoreRepositoryText.isNotBlank())
    }

    @Test
    fun `中间态文案零残留`() {
        assertFalse("BackupCopy 不得再含 PREVIEW_NOT_READY", backupCopyText.contains("PREVIEW_NOT_READY"))
        assertFalse("ImportScreen 不得再含 PREVIEW_NOT_READY", importScreenText.contains("PREVIEW_NOT_READY"))
        assertTrue("ImportScreen 必须使用 state.restore", importScreenText.contains("state.restore"))
        assertTrue("ImportScreen 必须有 onRestore 参数", importScreenText.contains("onRestore"))
        assertTrue("ImportScreen 必须有 onRestoreFinished 参数", importScreenText.contains("onRestoreFinished"))
        assertTrue("ImportScreen 必须用 RESTORE_DONE_TITLE", importScreenText.contains("RESTORE_DONE_TITLE"))
    }

    @Test
    fun `备份预览区块 按钮集合与不恢复清单都用上`() {
        val block = braceBlockOf(importScreenText, "private fun BackupPreviewStep(")
        assertTrue("预览区块必须含「恢复」按钮", block.contains("BackupCopy.RESTORE"))
        assertTrue("预览区块必须含「取消」", block.contains("BackupCopy.CANCEL"))
        assertFalse("预览区块不得含「换一个文件」（那是拒绝态的出口）", block.contains("PICK_ANOTHER"))
        assertTrue(block.contains("BackupCopy.RESTORE_NOTE"))
        assertTrue(block.contains("BackupCopy.NOT_RESTORED_TITLE"))
        listOf(
            "NOT_RESTORED_AVATAR", "NOT_RESTORED_FILES", "NOT_RESTORED_SEMANTIC", "NOT_RESTORED_DELETED",
            "NOT_RESTORED_RELATION", "NOT_RESTORED_USAGE", "NOT_RESTORED_SETTINGS", "NOT_RESTORED_MEMORY_SOURCE",
        ).forEach { name ->
            assertTrue("预览区块必须展示「不会恢复」项 $name", block.contains("BackupCopy.$name"))
        }
        assertTrue("预览区块必须展示附件提示", block.contains("previewFilesNotRestored"))
        assertTrue("预览区块必须展示导出时孤儿跳过数", block.contains("previewSkippedOrphans"))
        assertTrue("预览区块必须展示导出时间", block.contains("exportedAtText"))
    }

    @Test
    fun `恢复结果区块 计数与逐条提示都用上`() {
        // 结果区块边界钉在两个具体函数名：预览/执行/结果三段都在 `BackupPreviewStep` 函数体内，
        // 其中必须有 Running/Done/Failed 三个分支的渲染（不是只定义常量）。
        val block = braceBlockOf(importScreenText, "private fun BackupPreviewStep(")
        listOf("RestoreUi.Running", "RestoreUi.Done", "RestoreUi.Failed").forEach { branch ->
            assertTrue("三段渲染必须都落在 BackupPreviewStep 函数体内：缺 $branch", block.contains(branch))
        }
        listOf(
            "RESTORE_DONE_TITLE",
            "restoreDoneCounts",
            "restoreSkippedOrphans",
            "restoreIgnoredRefs",
            "restoreFilesNotRestored",
            "restoreIndexMissing",
            "RESTORE_FAILED",
            "RESTORE_RETRY",
        ).forEach { name ->
            assertTrue("结果区块必须渲染 $name", block.contains(name))
        }
    }

    @Test
    fun `AppRoot 调用点只加恢复两行`() {
        val block = importScreenCallBlock()
        assertEquals(
            "ImportScreen( 调用区块内 onRestore = vm::restore 应恰好 1 次",
            1,
            Regex("onRestore\\s*=\\s*vm::restore").findAll(block).count(),
        )
        assertEquals(
            "ImportScreen( 调用区块内 onRestoreFinished 应恰好 1 次",
            1,
            Regex("onRestoreFinished\\s*=").findAll(block).count(),
        )
        assertTrue("「好」必须回首页：必须有 vm.resetToEdit()", block.contains("vm.resetToEdit()"))
        assertTrue("「好」必须回首页：必须有 nav.back()", block.contains("nav.back()"))
        // 既有两个备份出口保留。
        assertEquals(1, Regex("onPickAnotherFile\\s*=").findAll(block).count())
        assertEquals(1, Regex("onCancelBackup\\s*=").findAll(block).count())
        assertTrue("onPickFile 仍不得包 resetToEdit", !Regex("onPickFile\\s*=\\s*\\{[^}]*resetToEdit").containsMatchIn(block))
    }

    @Test
    fun `恢复仓库结构上零网络`() {
        assertTrue(
            "恢复必须硬编码 embedEnabled = false",
            restoreRepositoryText.contains("embedEnabled = false"),
        )
        assertFalse("恢复不得出现 embedEnabled = true", restoreRepositoryText.contains("embedEnabled = true"))
        assertFalse("恢复不得调用向量端口 embed(", restoreRepositoryText.contains("embed("))
        assertFalse("恢复不得接 LLM", restoreRepositoryText.contains("LlmClient"))
        assertFalse("恢复不得接 EmbeddingClient", restoreRepositoryText.contains("EmbeddingClient"))
        // 单事务：唯一 db.withTransaction 调用，且阶段顺序写死。
        assertEquals(
            "恢复必须只有一个 db.withTransaction（整包一个事务）",
            1,
            Regex("db\\.withTransaction\\s*\\{").findAll(restoreRepositoryText).count(),
        )
        val order = listOf(
            "RestoreStage.CHARACTER", "RestoreStage.THREAD", "RestoreStage.SEGMENT",
            "RestoreStage.MESSAGE", "RestoreStage.MEMORY", "RestoreStage.MEMBER",
        )
        val positions = order.map { restoreRepositoryText.indexOf(it) }
        positions.forEachIndexed { index, position -> assertTrue("阶段 $index 必须存在", position >= 0) }
        assertEquals("阶段顺序必须固定", positions.sorted(), positions)
    }
}
