package cc.pulsez.app.ui.copy

import cc.pulsez.app.data.ExportOverLimit
import cc.pulsez.app.importer.BackupIssue
import cc.pulsez.app.importer.BackupParser
import cc.pulsez.app.importer.BackupPreview
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 备份相关文案回归测试。
 *
 * 纯 JVM。三件事：
 * 1. 常量/函数**逐字**可测（导出五种回执、备份两态、孤儿跳过）；
 * 2. 数字**全部由常量运行期拼**：测试期望用 [BackupCopy.formatCount] + `BackupParser` 常量，
 * 不硬编码千分位；源码扫描 `BackupCopy.kt` 无第二份数字字面；
 * 3. 文案纪律：禁词/角色代词 TA/确定性表述（不出现「可能恢复不了」）。
 */
class BackupCopyTest {

    private companion object {
        /** 禁用词（同 `CopyPolicyTest` 口径）。 */
        val TECH_BANNED: List<String> = listOf(
            "token", "上下文", "窗口", "向量", "embedding", "缓存", "关键词", "记忆检索",
            "亲", "小可爱", "您好",
        )

        /** 角色代词只允许 TA。 */
        val PRONOUN_BANNED: List<String> = listOf("她", "他", "我们")

        /** 用户可见文案源码（cwd = `app/`，依据 `DeepSeekRealApiTest.kt:477`）。 */
        val BACKUP_COPY_SOURCE: File = File("src/main/java/cc/pulsez/app/ui/copy/BackupCopy.kt")
        val BACKUP_PARSER_SOURCE: File = File("src/main/java/cc/pulsez/app/importer/BackupParser.kt")

        /** 设置页源码：证明「导出备份」行用常量、不再硬编码 `JSON`。 */
        val SETTINGS_SCREEN_SOURCE: File =
            File("src/main/java/cc/pulsez/app/ui/settings/SettingsScreen.kt")

        fun preview(skipped: Int = 0) = BackupPreview(
            schema = 3,
            exportedAt = 1L,
            characterCount = 3,
            threadCount = 2,
            messageCount = 128,
            attachmentCount = 0,
            skippedOrphanThreads = skipped,
        )
    }

    // ---------------------------------------------------------------- 常量逐字

    @Test
    fun `备份预览与出口常量逐字`() {
        assertEquals("这是一份 PulseZ 备份", BackupCopy.PREVIEW_TITLE)
        assertEquals("这份备份读不了", BackupCopy.REJECT_TITLE)
        assertEquals("换一个文件", BackupCopy.PICK_ANOTHER)
        assertEquals("取消", BackupCopy.CANCEL)
    }

    @Test
    fun `五种导出回执常量逐字`() {
        assertEquals(
            "导出好了。图片本体和头像不会跟着备份走；用量、花费和服务商配置也不在备份里。",
            BackupCopy.EXPORT_SUCCESS,
        )
        assertEquals("没导出成功。别用这个没写完的文件，再试一次。", BackupCopy.EXPORT_WRITE_FAILED)
        assertEquals("没导出成功。这次没读出数据，再试一次。", BackupCopy.EXPORT_DATA_FAILED)
        assertEquals(
            "这个文件太大了（这一版支持到 ${BackupParser.FILE_MAX_MB} MB）。",
            BackupCopy.READ_FILE_TOO_LARGE,
        )
        assertEquals("没读到这个文件。换一个再试？", BackupCopy.READ_FILE_FAILED)
    }

    // ---------------------------------------------------------------- 设置页入口

    @Test
    fun `设置页导出备份行 文案逐字且覆盖三问`() {
        assertEquals("导出备份", BackupCopy.SETTINGS_TITLE)
        assertEquals(
            "把 TA、对话和记忆存成一个文件，换手机或重装后能装回来。" +
                "不导出也能用，只是这些就带不走了。" +
                "图片本体和头像、用量和花费、服务商配置不在里面。",
            BackupCopy.SETTINGS_DESCRIPTION,
        )
        val text = BackupCopy.SETTINGS_DESCRIPTION
        // 这是什么：把 TA、对话和记忆存成一个文件。
        assertTrue("必须说清「存成一个文件」：$text", text.contains("存成一个文件"))
        // 要不要用：换手机或重装后能装回来。
        assertTrue("必须说清「换手机或重装后能装回来」：$text", text.contains("换手机或重装后能装回来"))
        // 不用会怎样：不导出也能用，只是这些带不走。
        assertTrue("必须回答「不导出也能用」：$text", text.contains("不导出也能用"))
        assertTrue("必须回答不导出的后果「带不走」：$text", text.contains("带不走"))
        // 范围排除项与导出回执 [EXPORT_SUCCESS] / 协议 同口径，逐项断言防再次漏项。
        listOf("图片本体和头像", "用量和花费", "服务商配置").forEach { excluded ->
            assertTrue("范围排除项必须与导出回执同口径，缺「$excluded」：$text", text.contains(excluded))
        }
    }

    @Test
    fun `设置页导出备份行 不再显示 JSON 且标题与说明来自常量`() {
        val source = SETTINGS_SCREEN_SOURCE.readText(Charsets.UTF_8)
        assertTrue("读不到 SettingsScreen.kt：${SETTINGS_SCREEN_SOURCE.absolutePath}", source.isNotBlank())
        assertTrue("行标题必须来自 BackupCopy.SETTINGS_TITLE", source.contains("BackupCopy.SETTINGS_TITLE"))
        assertTrue(
            "行下说明必须来自 BackupCopy.SETTINGS_DESCRIPTION",
            source.contains("BackupCopy.SETTINGS_DESCRIPTION"),
        )
        assertFalse("设置页不得再硬编码「JSON」技术缩写", source.contains("\"JSON\""))
        assertFalse(
            "「导出备份」标题只许在 BackupCopy 里定义一处（防两处文案分叉）",
            source.contains("\"导出备份\""),
        )
    }

    // ---------------------------------------------------------------- 计数 / 孤儿 / 超限

    @Test
    fun `previewCounts 用运行期千分位`() {
        assertEquals(
            "里面有 ${BackupCopy.formatCount(3)} 个 TA、" +
                "${BackupCopy.formatCount(2)} 段对话、${BackupCopy.formatCount(128)} 条消息。",
            BackupCopy.previewCounts(preview()),
        )
        assertEquals("15,000", BackupCopy.formatCount(15_000))
    }

    @Test
    fun `孤儿跳过回执 导出与预览两个口径一致`() {
        val export = BackupCopy.exportSkippedOrphans(1)
        assertEquals(
            "导出好了，但有 ${BackupCopy.formatCount(1)} 段对话因为角色已删除，没有进备份。" +
                "它们还在这台手机上。图片和头像也不在备份里。",
            export,
        )
        assertTrue(export.contains("1 段对话"))
        assertTrue(export.contains("角色已删除"))
        assertTrue(export.contains("还在这台手机上"))
        assertTrue(export.contains("图片和头像也不在备份里"))

        val previewText = BackupCopy.previewSkippedOrphans(2)
        assertEquals(
            "导出这份备份时，有 ${BackupCopy.formatCount(2)} 段对话因为角色已删除，没有进这份备份。",
            previewText,
        )
        assertTrue(previewText.contains("导出这份备份时"))
        assertTrue(previewText.contains("没有进这份备份"))
        assertFalse("不得出现会读成「这次导入没有导出」的歧义句", previewText.contains("这次没有导出"))
    }

    @Test
    fun `exportOverLimit 四条确定性文案 数字由常量拼`() {
        assertEquals(
            "导出好了。不过这份备份超过 ${BackupParser.FILE_MAX_MB} MB，这一版装不回来。",
            BackupCopy.exportOverLimit(ExportOverLimit.BYTES),
        )
        assertEquals(
            "导出好了。不过消息超过 ${BackupCopy.formatCount(BackupParser.MAX_MESSAGES)} 条，这一版装不回来。",
            BackupCopy.exportOverLimit(ExportOverLimit.MESSAGES),
        )
        assertEquals(
            "导出好了。不过对话超过 ${BackupCopy.formatCount(BackupParser.MAX_THREADS)} 段，这一版装不回来。",
            BackupCopy.exportOverLimit(ExportOverLimit.THREADS),
        )
        assertEquals(
            "导出好了。不过 TA 超过 ${BackupCopy.formatCount(BackupParser.MAX_CHARACTERS)} 个，这一版装不回来。",
            BackupCopy.exportOverLimit(ExportOverLimit.CHARACTERS),
        )
        ExportOverLimit.values().forEach { reason ->
            val text = BackupCopy.exportOverLimit(reason)
            assertTrue("超限文案要确定性（不写「可能」）：$text", !text.contains("可能"))
            assertTrue("超限文案要说清装不回来：$text", text.contains("这一版装不回来"))
            assertFalse("skipped=0 不得出现孤儿追加句：$text", text.contains("另有"))
        }
    }

    @Test
    fun `超限与孤儿同时成立 同一句里同时含两种事实`() {
        val text = BackupCopy.exportOverLimit(ExportOverLimit.MESSAGES, skippedOrphanThreads = 1)
        assertTrue("必须含超限原因：$text", text.contains("消息超过"))
        assertTrue("必须含孤儿追加句：$text", text.contains("另有"))
        assertTrue(text.contains("${BackupCopy.formatCount(1)} 段对话"))
        assertTrue(text.contains("角色已删除"))
    }

    @Test
    fun `issueText 六条互不相同且带确定性原因`() {
        val texts = BackupIssue.values().map { BackupCopy.issueText(it) }
        assertEquals("六条原因都要有文案", 6, texts.size)
        assertEquals("六条文案必须互不相同", 6, texts.distinct().size)
        texts.forEach { text ->
            assertTrue("不能为空：$text", text.isNotBlank())
            assertTrue("文案必须确定性、带下一步：$text", !text.contains("可能"))
        }
        assertTrue(BackupCopy.issueText(BackupIssue.DAMAGED).contains("换一份备份"))
        assertTrue(BackupCopy.issueText(BackupIssue.TOO_NEW).contains("更新 App"))
        // 上限三条都要带由常量推导出的数字。
        assertTrue(BackupCopy.issueText(BackupIssue.TOO_LARGE).contains("${BackupParser.FILE_MAX_MB} MB"))
        assertTrue(
            BackupCopy.issueText(BackupIssue.TOO_MANY_MESSAGES)
                .contains(BackupCopy.formatCount(BackupParser.MAX_MESSAGES)),
        )
        assertTrue(
            BackupCopy.issueText(BackupIssue.TOO_MANY_THREADS)
                .contains(BackupCopy.formatCount(BackupParser.MAX_THREADS)),
        )
        assertTrue(
            BackupCopy.issueText(BackupIssue.TOO_MANY_CHARACTERS)
                .contains(BackupCopy.formatCount(BackupParser.MAX_CHARACTERS)),
        )
    }

    // ---------------------------------------------------------------- 文案纪律

    @Test
    fun `全部备份文案 无禁词 且角色代词只用 TA`() {
        val texts = buildList {
            addAll(
                listOf(
                    BackupCopy.PREVIEW_TITLE,
                    BackupCopy.REJECT_TITLE,
                    BackupCopy.PICK_ANOTHER,
                    BackupCopy.CANCEL,
                    BackupCopy.EXPORT_SUCCESS,
                    BackupCopy.EXPORT_WRITE_FAILED,
                    BackupCopy.EXPORT_DATA_FAILED,
                    BackupCopy.READ_FILE_TOO_LARGE,
                    BackupCopy.READ_FILE_FAILED,
                    BackupCopy.SETTINGS_TITLE,
                    BackupCopy.SETTINGS_DESCRIPTION,
                    BackupCopy.previewCounts(preview(skipped = 1)),
                    BackupCopy.previewSkippedOrphans(1),
                    BackupCopy.exportSkippedOrphans(1),
                ),
            )
            BackupIssue.values().forEach { add(BackupCopy.issueText(it)) }
            ExportOverLimit.values().forEach { add(BackupCopy.exportOverLimit(it, skippedOrphanThreads = 1)) }
        }
        texts.forEach { text ->
            TECH_BANNED.forEach { word ->
                assertFalse("备份文案出现技术词「$word」：$text", text.contains(word))
            }
            PRONOUN_BANNED.forEach { pronoun ->
                assertFalse("备份文案出现角色代词「$pronoun」（只许 TA）：$text", text.contains(pronoun))
            }
        }
    }

    // ---------------------------------------------------------------- 源码扫描：数字单一定义

    @Test
    fun `BackupCopy 源码不含第二份数字字面`() {
        val source = BACKUP_COPY_SOURCE.readText(Charsets.UTF_8)
        assertTrue("读不到文案源码（cwd 应为 app/）：${BACKUP_COPY_SOURCE.absolutePath}", source.isNotBlank())
        listOf("15,000", "500 段", "500 个", "16 MB").forEach { literal ->
            assertFalse("BackupCopy.kt 不得出现数字字面「$literal」（应由常量/formatCount 拼出）", source.contains(literal))
        }
    }

    @Test
    fun `BackupParser 常量各只声明一次 且 MB 与字节不变量成立`() {
        val source = BACKUP_PARSER_SOURCE.readText(Charsets.UTF_8)
        assertTrue("读不到 BackupParser 源码：${BACKUP_PARSER_SOURCE.absolutePath}", source.isNotBlank())
        listOf(
            "FILE_MAX_BYTES",
            "FILE_MAX_MB",
            "MAX_MESSAGES",
            "MAX_THREADS",
            "MAX_CHARACTERS",
            "SUPPORTED_SCHEMA",
        ).forEach { name ->
            val declarations = Regex("const\\s+val\\s+$name\\b").findAll(source).count()
            assertEquals("常量 $name 必须恰好一处声明", 1, declarations)
        }
        assertEquals(
            BackupParser.FILE_MAX_MB.toLong() * 1024 * 1024,
            BackupParser.FILE_MAX_BYTES,
        )
    }
}
