package cc.pulsez.app.ui.copy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「清空所有数据」文案回归。
 *
 * 只测常量：Compose 页面里的字面量没法在纯 JVM 里收集（同 `ReadCopyTest`/`UpdateCopyTest`）。
 * 清单渲染的可滚动 + 有界高度（`heightIn(max = 360.dp).verticalScroll`）是代码审查项，
 * 不在纯 JVM 断言范围（见「调用点清单」与验收记录）。
 */
class ClearCopyTest {

    companion object {
        /** 禁用词（「密钥/地址」是配置页允许的技术词，不在此列）。 */
        private val TECH_BANNED: List<String> = listOf(
            "token", "上下文", "窗口", "向量", "embedding", "Embedding",
            "记忆检索", "关键词", "缓存", "TTS", "语音合成", "合成引擎",
        )

        /** 角色代词只允许 TA。 */
        private val PRONOUN_BANNED: List<String> = listOf("她", "他")

        /** 产品/合规额外禁用：绝对承诺、客服腔。 */
        private val PROMISE_BANNED: List<String> = listOf(
            "免费", "永久", "绝对", "本版未提供", "我们",
        )

        private fun allCopy(): List<Pair<String, String>> =
            listOf(
                "标题" to ClearCopy.TITLE,
                "引入句" to ClearCopy.INTRO,
                "会留小标题" to ClearCopy.KEEP_HEADING,
                "确认按钮" to ClearCopy.CONFIRM,
                "取消按钮" to ClearCopy.CANCEL,
                "失败标题" to ClearCopy.FAILED_TITLE,
                "失败正文" to ClearCopy.FAILED_MESSAGE,
                "重试按钮" to ClearCopy.RETRY,
                "先不管按钮" to ClearCopy.DISMISS,
            ) + ClearCopy.DELETE_ITEMS.mapIndexed { index, item ->
                "会删第 ${index + 1} 项" to item
            } + ClearCopy.KEEP_ITEMS.mapIndexed { index, item ->
                "会留第 ${index + 1} 项" to item
            }
    }

    @Test
    fun deleteListCoversEveryReviewItem() {
        // A 的「会删」最小完备集：18 项（第 ⑩ 项按实际行为写「拷进来的临时文件」）。
        assertEquals(18, ClearCopy.DELETE_ITEMS.size)
        assertEquals(1, ClearCopy.KEEP_ITEMS.size)
    }

    @Test
    fun everyListItemIsNonEmptyAndDistinct() {
        ClearCopy.DELETE_ITEMS.forEachIndexed { index, item ->
            assertTrue("会删第 ${index + 1} 项不能为空", item.isNotBlank())
        }
        ClearCopy.KEEP_ITEMS.forEachIndexed { index, item ->
            assertTrue("会留第 ${index + 1} 项不能为空", item.isNotBlank())
        }
        assertEquals(
            "「会删」清单不要出现重复项（用户会逐项核对）",
            ClearCopy.DELETE_ITEMS.size,
            ClearCopy.DELETE_ITEMS.distinct().size,
        )
    }

    @Test
    fun deleteListKeepsClearCopyForImportTempFiles() {
        // 导入只把 ZIP 拷进 cacheDir/import、不解压落盘，所以第 ⑩ 项必须写「拷进来」，
        // 不能写「解压出的」。
        assertTrue(
            "第 ⑩ 项要写「导入时拷进来的临时文件」",
            ClearCopy.DELETE_ITEMS.any { it.contains("拷进来") },
        )
        assertFalse(
            "不能写「导入时解压出的临时文件」（与实现不符）",
            ClearCopy.DELETE_ITEMS.any { it.contains("解压") },
        )
    }

    @Test
    fun keepListSaysAgreementRecordIsKept() {
        val keep = ClearCopy.KEEP_ITEMS.single()
        assertTrue("要写明保留协议同意记录", keep.contains("协议同意记录"))
        assertTrue("要说清不用重新过门槛", keep.contains("不用重新过门槛"))
    }

    @Test
    fun failureCopyIsVisibleAndActionable() {
        assertEquals("没清干净", ClearCopy.FAILED_TITLE)
        assertEquals("有几项没删掉，你可以再试一次。", ClearCopy.FAILED_MESSAGE)
        assertEquals("再试一次", ClearCopy.RETRY)
        assertEquals("先不管", ClearCopy.DISMISS)
        // 不得声称已全清/冒充成功（03 的核心缺陷）。
        assertFalse(ClearCopy.FAILED_MESSAGE.contains("已清"))
        assertFalse(ClearCopy.FAILED_MESSAGE.contains("成功"))
    }

    @Test
    fun copyAvoidsBannedWordsAndPromises() {
        allCopy().forEach { (name, text) ->
            TECH_BANNED.forEach { word ->
                assertFalse("「$name」出现技术词「$word」：$text", text.contains(word))
            }
            PRONOUN_BANNED.forEach { pronoun ->
                assertFalse("「$name」出现角色代词「$pronoun」（只许 TA）：$text", text.contains(pronoun))
            }
            PROMISE_BANNED.forEach { phrase ->
                assertFalse("「$name」出现不该有的说法「$phrase」：$text", text.contains(phrase))
            }
        }
    }
}
