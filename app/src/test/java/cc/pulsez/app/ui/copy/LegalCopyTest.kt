package cc.pulsez.app.ui.copy

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import cc.pulsez.app.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 协议版本 2 与摘要一致性（ 10， E /）。
 *
 * 可执行口径：
 * - 摘要常量**唯一来源**是 [LegalCopy.AGREEMENT_DIGEST]；Robolectric 读，
 * 断言摘要**恰好出现一次**（版本行不得重复摘要），不只是「出现过」；
 * - 版本行逐字是「版本 2 · 2026 年 11 月」；
 * -「随时删除全部数据」补的保留句逐字；
 * - 常量非空、不含禁用词。
 *
 * 为什么不用「读源码文本」的测试：`GateScreen` 是否引用同一常量、是否复制第二份字面，
 * 属人工核对的代码审查项（ 明确禁止源码文本测试——那种断言脆且测不到真行为）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class LegalCopyTest {

    companion object {
        /** 禁用词（协议是用户可见文案，同样适用）。 */
        private val TECH_BANNED: List<String> = listOf(
            "token", "上下文", "窗口", "向量", "embedding", "Embedding",
            "记忆检索", "关键词", "缓存", "TTS", "语音合成",
        )
        private val PRONOUN_BANNED: List<String> = listOf("她", "他")
    }

    private fun html(): String =
        ApplicationProvider.getApplicationContext<Context>()
            .resources
            .openRawResource(R.raw.agreement)
            .bufferedReader()
            .use { it.readText() }

    @Test
    fun digestIsVerbatimPerBrief() {
        // 摘要文案逐字一致：HTML 与门槛页必须用同一串字符，含标点。
        assertEquals(
            "这一版把三件事说明白了：密钥跟着服务商走（换服务商要重填）、" +
                "清空会删哪些写清楚了、没有新增权限。",
            LegalCopy.AGREEMENT_DIGEST,
        )
        assertTrue("摘要不能为空", LegalCopy.AGREEMENT_DIGEST.isNotBlank())
    }

    @Test
    fun digestAppearsExactlyOnceInAgreementHtml() {
        val text = html()
        val count = Regex(Regex.escape(LegalCopy.AGREEMENT_DIGEST)).findAll(text).count()
        assertEquals("协议里摘要必须恰好出现一次（版本行不得重复摘要）", 1, count)
    }

    @Test
    fun versionLineIsVersionTwo() {
        assertEquals("版本 2 · 2026 年 11 月", LegalCopy.VERSION_LINE)
        val text = html()
        assertTrue("协议版本行必须是「版本 2 · 2026 年 11 月」", text.contains(LegalCopy.VERSION_LINE))
        assertFalse("不能残留版本 1 的版本行", text.contains("版本 1 · 2026 年 11 月"))
    }

    @Test
    fun sectionFourKeepsAgreementRecord() {
        //：「随时删除全部数据」补「（协议同意记录会保留，避免重过门槛）」——逐字断言。
        assertTrue(
            "§4 必须逐字写明协议同意记录会保留",
            html().contains("（协议同意记录会保留，避免重过门槛）"),
        )
    }

    @Test
    fun agreementBodyExplainsTheThreeChanges() {
        // E：正文再补 ① 密钥跟着服务商走、换服务商要重填 ② 清空会删什么/留什么
        // ③ 本版没有新增权限；不删既有条款（既有权限/出网说明仍在）。
        val text = html()
        assertTrue("正文要写密钥跟着服务商走", text.contains("密钥跟着服务商走"))
        assertTrue("正文要写换服务商要重填", text.contains("换了服务商"))
        assertTrue("正文要写清空会删什么", text.contains("随时删除全部数据"))
        assertTrue("正文要写清空会留什么", text.contains("协议同意记录会保留"))
        assertTrue("正文要写本版没有新增权限", text.contains("这一版没有新增权限"))
        // 既有条款不能被删（只补不删）。
        assertTrue("既有出网说明仍在", text.contains("语音输入"))
        assertTrue("既有权限说明仍在", text.contains("只申请两个权限"))
        assertTrue("既有费用说明仍在", text.contains("费用"))
    }

    @Test
    fun digestAndVersionLineAvoidBannedWords() {
        listOf(
            "摘要" to LegalCopy.AGREEMENT_DIGEST,
            "版本行" to LegalCopy.VERSION_LINE,
        ).forEach { (name, text) ->
            TECH_BANNED.forEach { word ->
                assertFalse("「$name」出现技术词「$word」：$text", text.contains(word))
            }
            PRONOUN_BANNED.forEach { pronoun ->
                assertFalse("「$name」出现角色代词「$pronoun」（只许 TA）：$text", text.contains(pronoun))
            }
        }
    }
}
