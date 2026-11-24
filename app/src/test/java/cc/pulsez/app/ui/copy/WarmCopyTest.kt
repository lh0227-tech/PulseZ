package cc.pulsez.app.ui.copy

import cc.pulsez.app.net.ApiError
import cc.pulsez.app.ui.chat.ChatViewModel
import cc.pulsez.app.ui.importer.importConsentText
import cc.pulsez.app.ui.memory.MemoryCopy
import cc.pulsez.app.ui.settings.WindowTierCopy
import cc.pulsez.app.ui.threads.HomeCopy
import cc.pulsez.app.wire.ErrorCopy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 文案修复回归（纯 JVM，不碰 Android / Compose / Room）。
 *
 *
 * - 用户 反馈（首页「还没有人记得你」太冷、对女性用户不友好）；
 * - （逐条口径，本测试按该文件逐字锁定）；
 * -、。
 *
 * 覆盖范围：首页三状态 + 空态 + 角色卡、上下文溢出错误、回复失败占位、记忆分组、
 * 两处付费确认（去内部单位「片」）、设置四档值文案与行下说明。
 * 不在本测试内：Compose 页面里的静态字面量只能 grep （见）；
 * 合规逐字稿（危机兜底 / 创作模式）由 `CreativeModeCopyTest`、`CrisisDetectorTest` 单独锁定，本修复不动。
 */
class WarmCopyTest {

    companion object {
        /**
 * 禁用词（"模型""密钥""地址"是配置页允许的例外，不在此列）。
 *
 * 追加内部单位「片」：付费确认屏不许出现（ 三·）。
 */
        private val TECH_BANNED: List<String> = listOf(
            "token", "上下文", "窗口", "向量", "embedding", "Embedding",
            "记忆检索", "记忆系统", "关键词", "缓存", "片",
        )

        /** 角色代词只允许 TA。 */
        private val PRONOUN_BANNED: List<String> = listOf("她", "他")

        /** 本次修复前的旧文案与客服腔：用户可见处不允许再出现。 */
        private val OLD_OR_COLD_BANNED: List<String> = listOf(
            "还没有人记得你",
            "还在认识你",
            "这里还没有人",
            "没发出去",
            "TA 喜欢的和你讨厌的",
            "记忆检索服务",
            "你填写的接口",
            "小伙伴",
            "小可爱",
            "亲",
            "您好",
            "我们",
        )

        /** 本改过的全部用户可见文案（名称 → 文案），供批量断言。 */
        private fun allChangedCopy(): List<Pair<String, String>> = listOf(
            "首页·无角色" to HomeCopy.caption(false, 0).orEmpty(),
            "首页·0 记忆" to requireNotNull(HomeCopy.caption(true, 0)),
            "首页·单角色有记忆" to requireNotNull(HomeCopy.caption(true, 1)),
            "首页·多角色有记忆" to requireNotNull(HomeCopy.caption(true, 3)),
            "首页·空态标题" to HomeCopy.EMPTY_TITLE,
            "角色卡·0 记忆" to HomeCopy.CARD_NO_MEMORY,
            "错误·内容超长" to ErrorCopy.CONTEXT_OVERFLOW_TEXT,
            "聊天·回复失败占位" to ChatViewModel.REPLY_FAILED_PLACEHOLDER,
            "记忆分组·喜好" to MemoryCopy.GROUP_PREF,
            "记忆面板·付费确认" to MemoryCopy.consentText(1.2, 3),
            "导入页·付费确认" to importConsentText("¥128"),
            "设置·档位名 1" to WindowTierCopy.TIER_SAVE_MORE,
            "设置·档位名 2" to WindowTierCopy.TIER_SAVE,
            "设置·档位名 3" to WindowTierCopy.TIER_DEFAULT,
            "设置·档位名 4" to WindowTierCopy.TIER_MAX,
            "设置·档位说明" to WindowTierCopy.DESCRIPTION,
        )
    }

    // ------------------------------------------------------- 首页三状态（§二）

    @Test
    fun `home caption has exactly three states and hides itself without characters`() {
        // 状态 A：无角色 → 不显示副标题（只留 PulseZ + 设置图标）。
        assertNull("无角色时首页顶部不该有副标题", HomeCopy.caption(hasCharacters = false, memoryCharacterCount = 0))
        assertNull(
            "无角色时即使数字脏了也不显示",
            HomeCopy.caption(hasCharacters = false, memoryCharacterCount = 3),
        )

        // 状态 B：有角色、0 记忆 → 暖句 + 下一步（不播报「没人记得你」）。
        assertEquals(
            "TA 还没记住什么。聊上几句，就会慢慢记住你。",
            HomeCopy.caption(hasCharacters = true, memoryCharacterCount = 0),
        )

        // 状态 C：有记忆 → 单角色 / 多角色两种口径。
        assertEquals(
            "TA 记得你们聊过的事",
            HomeCopy.caption(hasCharacters = true, memoryCharacterCount = 1),
        )
        assertEquals(
            "3 个 TA 记得你们聊过的事",
            HomeCopy.caption(hasCharacters = true, memoryCharacterCount = 3),
        )
        // 不把「被记得」说成「记得你」：主语是「你们聊过的事」。
        // 注意：正确句「TA 记得你们聊过的事」里「记得你们」本身以「记得你」开头，
        // 所以不能用 contains("记得你") 粗判；这里只断言「旧口径短语不存在」+ 新主语在。
        val hasMemory = requireNotNull(HomeCopy.caption(hasCharacters = true, memoryCharacterCount = 1))
        assertTrue("主语要是「你们聊过的事」：$hasMemory", hasMemory.contains("你们聊过的事"))
        assertFalse("旧的「还没有人记得你」口径不能回来：$hasMemory", hasMemory.contains("还没有人记得你"))
        assertFalse("旧的关系进度承诺「还在认识你」不能回来：$hasMemory", hasMemory.contains("还在认识你"))
    }

    @Test
    fun `empty state and zero-memory card use the agreed wording verbatim`() {
        // 空态标题：不再说「这里还没有人」（同屏第二处「没有人」已消掉），正文保留不动。
        assertEquals("还没有 TA。", HomeCopy.EMPTY_TITLE)
        // 角色卡 0 记忆：纯状态句，不承诺「还在认识你」的关系进度。
        assertEquals("还没记住什么", HomeCopy.CARD_NO_MEMORY)
    }

    // ------------------------------------------------------- 错误 / 失败提示

    @Test
    fun `context overflow says the fact and gives two ways out`() {
        assertEquals(
            "这次带的内容太多，没装下。把上一句说短一点，或者先删几条很早的消息再试？",
            ErrorCopy.CONTEXT_OVERFLOW_TEXT,
        )
        // 原文承诺了并不存在的「我整理一下记忆再试」；新句必须是用户能自己做的两步。
        assertTrue(ErrorCopy.CONTEXT_OVERFLOW_TEXT.contains("说短一点"))
        assertTrue(ErrorCopy.CONTEXT_OVERFLOW_TEXT.contains("删几条"))
        assertFalse("不能出现 App 第一人称「我」", ErrorCopy.CONTEXT_OVERFLOW_TEXT.contains("我"))
        // 映射确实接到了新常量（`ApiError.Kind.CONTEXT_OVERFLOW → ErrorCopy.of`）。
        assertEquals(ErrorCopy.CONTEXT_OVERFLOW_TEXT, ErrorCopy.of(ApiError.ContextOverflow()))
    }

    @Test
    fun `reply failure placeholder blames the reply and not the user message`() {
        assertEquals("TA 没回上来。", ChatViewModel.REPLY_FAILED_PLACEHOLDER)
        // 用户消息已落库，失败的是 TA 的回复：「没发出去」与事实不符。
        assertFalse(ChatViewModel.REPLY_FAILED_PLACEHOLDER.contains("没发出去"))
        assertTrue(ChatViewModel.REPLY_FAILED_PLACEHOLDER.contains("TA"))
    }

    // ------------------------------------------------------- 记忆面板

    @Test
    fun `memory pref group makes the user the subject`() {
        // PREF 的语义是「用户明确说过的喜好与厌恶」（MemoryExtractionPrompt.kt:30），
        // 标题不能写成「TA 喜欢的」+「你讨厌的」。
        assertEquals("你喜欢和不喜欢的事", MemoryCopy.GROUP_PREF)
        assertFalse(MemoryCopy.GROUP_PREF.contains("讨厌"))
        assertFalse(MemoryCopy.GROUP_PREF.contains("TA"))
    }

    @Test
    fun `both fee confirmations drop the internal shard unit`() {
        // 记忆面板（数字口径：只允许 字/条/时间/钱）。
        assertEquals("整理成记忆大概 ¥1.20，继续吗？", MemoryCopy.consentText(1.2, 3))
        assertEquals("整理成记忆大概 ¥128，继续吗？", MemoryCopy.consentText(128.4, 20))
        assertFalse("记忆面板付费确认不能出现「片」", MemoryCopy.consentText(1.2, 3).contains("片"))

        // 导入页（同一句话）。
        assertEquals("整理成记忆大概 ¥128，继续吗？", importConsentText("¥128"))
        assertEquals("整理成记忆大概 ¥1.20，继续吗？", importConsentText("¥1.20"))
        assertFalse("导入页付费确认不能出现「片」", importConsentText("¥1.20").contains("片"))
    }

    // ------------------------------------------------------- 设置「TA 记得多清楚」

    @Test
    fun `window tier labels answer how much instead of how cheap`() {
        // 四档人话（档位名）：值文案回答「记多清楚」，不答成本词。
        assertEquals("省得更多", WindowTierCopy.TIER_SAVE_MORE)
        assertEquals("省着用", WindowTierCopy.TIER_SAVE)
        assertEquals("默认", WindowTierCopy.TIER_DEFAULT)
        assertEquals("极致", WindowTierCopy.TIER_MAX)

        // 档位数值 → 值文案（当前设置行只循环 32k/64k/300k；非标准值按手填「极致」显示）。
        assertEquals("省得更多", WindowTierCopy.label(32_000))
        assertEquals("省着用", WindowTierCopy.label(64_000))
        assertEquals("默认", WindowTierCopy.label(300_000))
        assertEquals("极致", WindowTierCopy.label(512_000))

        // 不再用「最省 / 省一点」这类成本词做值文案。
        val labels = listOf(
            WindowTierCopy.TIER_SAVE_MORE,
            WindowTierCopy.TIER_SAVE,
            WindowTierCopy.TIER_DEFAULT,
            WindowTierCopy.TIER_MAX,
        )
        assertFalse("值文案不能是「最省」", labels.contains("最省"))
        assertFalse("值文案不能是「省一点」", labels.contains("省一点"))
        assertEquals("四档值文案不能重复", 4, labels.toSet().size)
    }

    @Test
    fun `window tier description explains cost and says not changing is fine`() {
        assertEquals(
            "点一下可切换「省得更多 / 省着用 / 默认」：档位越大，TA 越记得住最近的细节，每句也越贵。不改也行。",
            WindowTierCopy.DESCRIPTION,
        )
        // 第八条三问：说清是什么（记得住细节）/ 代价（更贵）/ 不用会怎样（不改也行）。
        assertTrue(WindowTierCopy.DESCRIPTION.contains("记得住最近的细节"))
        assertTrue(WindowTierCopy.DESCRIPTION.contains("越贵"))
        assertTrue(WindowTierCopy.DESCRIPTION.contains("不改也行"))
    }

    // ------------------------------------------------------- 批量纪律

    @Test
    fun `changed copy uses TA avoids technical words and old cold wording`() {
        allChangedCopy().forEach { (name, text) ->
            TECH_BANNED.forEach { word ->
                assertFalse("「$name」出现禁用词「$word」：$text", text.contains(word))
            }
            PRONOUN_BANNED.forEach { pronoun ->
                assertFalse("「$name」出现角色代词「$pronoun」（只许 TA）：$text", text.contains(pronoun))
            }
            OLD_OR_COLD_BANNED.forEach { phrase ->
                assertFalse("「$name」出现旧文案/客服腔「$phrase」：$text", text.contains(phrase))
            }
        }
    }

    @Test
    fun `home captions always name TA`() {
        // 首页三状态（B/C）必须以 TA 作主语，避免又滑回「有人/没人记得你」的叙事。
        requireNotNull(HomeCopy.caption(true, 0))
        requireNotNull(HomeCopy.caption(true, 1))
        requireNotNull(HomeCopy.caption(true, 3))
        assertTrue(HomeCopy.CAPTION_NO_MEMORY.contains("TA"))
        assertTrue(HomeCopy.CAPTION_ONE.contains("TA"))
        assertTrue(HomeCopy.captionMany(2).contains("TA"))
    }
}
