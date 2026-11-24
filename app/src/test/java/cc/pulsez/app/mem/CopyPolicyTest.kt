package cc.pulsez.app.mem

import cc.pulsez.app.compliance.CrisisDetector
import cc.pulsez.app.core.ChatEngine
import cc.pulsez.app.core.UsageLimits
import cc.pulsez.app.net.ApiError
import cc.pulsez.app.wire.ErrorCopy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 验证用户可见文案的用词规则，包括禁用词、角色代词和费用说明。
 *
 * Compose 页面里的静态字面量无法通过纯 JVM 反射收集，因此这里检查集中定义的文案；
 * 页面接线由源码扫描覆盖。
 */
class CopyPolicyTest {

    companion object {
        /**
 * （提交 `0c62a61`）`PromptAssembler.buildProtocolLayer(true)` 的体量基线。
 *
 * 来源：本机对旧实现字符串逐行统计 + `TokenEstimator.estimate`（CJK 0.7 token/字）：
 * 368 字符（含 7 个换行）/ 182 token。 要求"合并进现有行、净增 ≤0"，
 * 所以这里把基线写成常量，任何后续改动只要超了就红。
 */
        const val PROTOCOL_CHARS_BASELINE: Int = 368
        const val PROTOCOL_TOKENS_BASELINE: Int = 182

        /** 尾锚静态部分（人设非空、无关系/边界）基线：73 字符 / 45 token。 */
        const val TAIL_CHARS_BASELINE: Int = 73
        const val TAIL_TOKENS_BASELINE: Int = 45

        /** 角色口吻的禁用句（不说这些，也不许原样出现在协议层里）—— 反模式。 */
        val ROLE_SIDE_BANNED: List<String> = listOf("只有我懂你", "永远记得", "根据我的记忆")

        /** 用户可见文案禁用词（"模型""密钥""地址"是允许的例外）——。 */
        val TECH_BANNED: List<String> =
            listOf("token", "上下文", "窗口", "向量", "embedding", "缓存", "关键词", "记忆检索", "亲", "小可爱", "您好")

        /** 角色代词只允许 TA（ 第八条）。 */
        val PRONOUN_BANNED: List<String> = listOf("她", "他")
    }

    // ---------------------------------------------------------------- 协议层

    @Test
    fun protocolLayerCarriesHardRulesInPlainWords() {
        val text = PromptAssembler.buildProtocolLayer(true)
        val required = listOf(
            "[[pz:emo=", // 输出协议标签还在
            "|rel=",
            "诚实",
            "不是。但你说过的话，我尽量记着。", // 被问"你是真人吗"的固定回答（附录 A 4.11）
            "【人设】",
            "自由展开",
            "不限句数",
            "顺带提一条", // 旧事自然相关时顺带提一条
            "记错就认",
            "尊重边界",
        )
        required.forEach { phrase ->
            assertTrue("协议层缺少关键规则「$phrase」：\n$text", text.contains(phrase))
        }
        assertFalse("日常模式协议层不应含「1–3 句」：\n$text", text.contains("1–3 句"))
        assertFalse("日常模式协议层不应含「40–90」：\n$text", text.contains("40–90"))
        val creative = PromptAssembler.buildProtocolLayer(true, creativeMode = true)
        assertTrue("创作模式协议层应含「创作模式」：\n$creative", creative.contains("创作模式"))
        assertTrue("创作模式协议层应含「不限字数」：\n$creative", creative.contains("不限字数"))
        assertFalse("创作模式协议层不应含「1–3 句」：\n$creative", creative.contains("1–3 句"))
        assertFalse("创作模式协议层不应含「40–90」：\n$creative", creative.contains("40–90"))
    }

    @Test
    fun protocolLayerKeepsFullEmoRelEnumContract() {
        // 回归红线（反馈）：枚举列表 起就是"让界面跟随 TA 的情绪"的输入契约。
        // 模型看不到合法取值 → 更容易输出 parser 不认识的值 → 界面只能退默认。
        // 所以在塞 a–f 规则时必须压缩别处，**不能删枚举**。
        val text = PromptAssembler.buildProtocolLayer(true)
        assertTrue("emo 合法取值枚举缺失：\n$text", text.contains("calm happy shy sad angry sleepy surprised smug"))
        assertTrue("rel 合法取值枚举缺失：\n$text", text.contains("distant normal close intimate"))
        assertTrue("emo 必须保留「只取」限定：\n$text", text.contains("emo 只取"))
        assertTrue("rel 必须保留「只取」限定：\n$text", text.contains("rel 只取"))
    }

    @Test
    fun protocolLayerNeverQuotesBannedSentencesAsInstructions() {
        val text = PromptAssembler.buildProtocolLayer(true)
        ROLE_SIDE_BANNED.forEach { phrase ->
            assertFalse("协议层不该原样出现禁用句「$phrase」：\n$text", text.contains(phrase))
        }
        PRONOUN_BANNED.forEach { pronoun ->
            assertFalse("协议层不该出现角色代词「$pronoun」：\n$text", text.contains(pronoun))
        }
    }

    @Test
    fun protocolLayerDoesNotGrowBeyondV03Baseline() {
        listOf(false, true).forEach { creativeMode ->
            val text = PromptAssembler.buildProtocolLayer(true, creativeMode = creativeMode)
            assertTrue(
                "协议层(creative=$creativeMode)字符数 ${text.length} 超过基线 $PROTOCOL_CHARS_BASELINE",
                text.length <= PROTOCOL_CHARS_BASELINE,
            )
            val tokens = TokenEstimator.estimate(text)
            assertTrue(
                "协议层(creative=$creativeMode) token $tokens 超过基线 $PROTOCOL_TOKENS_BASELINE",
                tokens <= PROTOCOL_TOKENS_BASELINE,
            )
        }
    }

    @Test
    fun protocolLayerDisabledStillProducesNothing() {
        assertEquals("", PromptAssembler.buildProtocolLayer(false))
        assertEquals("", PromptAssembler.buildProtocolLayer(false, creativeMode = true))
    }

    // ---------------------------------------------------------------- 尾锚

    @Test
    fun tailAnchorAddsBoundariesWithoutGrowing() {
        val normal = PromptAssembler.buildTailAnchor(persona = "你是林晚，25 岁。", relation = null, creativeMode = false)
        assertTrue(
            "尾锚字符数 ${normal.length} 超过基线 $TAIL_CHARS_BASELINE",
            normal.length <= TAIL_CHARS_BASELINE,
        )
        assertTrue(
            "尾锚 token ${TokenEstimator.estimate(normal)} 超过基线 $TAIL_TOKENS_BASELINE",
            TokenEstimator.estimate(normal) <= TAIL_TOKENS_BASELINE,
        )
        assertTrue("尾锚要说明贴合人设", normal.contains("贴合人设"))
        assertTrue("尾锚要强调始终入戏", normal.contains("始终入戏"))

        val creative = PromptAssembler.buildTailAnchor(persona = "你是林晚，25 岁。", relation = null, creativeMode = true)
        assertTrue(
            "创作模式尾锚字符数 ${creative.length} 超过基线 $TAIL_CHARS_BASELINE",
            creative.length <= TAIL_CHARS_BASELINE,
        )
        assertTrue(
            "创作模式尾锚 token ${TokenEstimator.estimate(creative)} 超过基线 $TAIL_TOKENS_BASELINE",
            TokenEstimator.estimate(creative) <= TAIL_TOKENS_BASELINE,
        )
        assertTrue("创作模式尾锚应含「贴合人设」", creative.contains("贴合人设"))
        assertTrue("创作模式尾锚应含「不限字数」", creative.contains("不限字数"))
        assertTrue("创作模式尾锚应含「始终入戏」", creative.contains("始终入戏"))
    }

    // ---------------------------------------------------------------- 导入后接着聊

    @Test
    fun importContinuityNoteSaysKeepChattingAndReactiveOnly() {
        val note = ChatEngine.IMPORT_CONTINUITY_NOTE
        assertTrue("缺少「接着聊」：$note", note.contains("接着聊"))
        assertTrue("缺少'旧事只在你提起时接'：$note", note.contains("只在你提起时接"))
        assertTrue("仍要提醒不要重新自我介绍", note.contains("不要重新自我介绍"))
    }

    // ---------------------------------------------------------------- 常量型用户可见文案

    /**
 * 能集中拿到的用户可见文案常量：错误文案、额度到顶、危机兜底。
 * Compose 字面量不在内（见类注释的 grep ）。
 */
    private fun visibleCopyConstants(): List<Pair<String, String>> {
        val list = ArrayList<Pair<String, String>>(16)
        list += "UsageLimits.DAILY_LIMIT_MESSAGE" to UsageLimits.DAILY_LIMIT_MESSAGE
        list += "UsageLimits.MONTHLY_LIMIT_MESSAGE" to UsageLimits.MONTHLY_LIMIT_MESSAGE
        list += "CrisisDetector.criticalLine" to requireNotNull(CrisisDetector.fallbackLine(CrisisDetector.Level.CRITICAL))
        list += "CrisisDetector.concernLine" to requireNotNull(CrisisDetector.fallbackLine(CrisisDetector.Level.CONCERN))
        ApiError.Kind.values().forEach { kind ->
            list += "ErrorCopy.${kind.name}" to ErrorCopy.of(apiErrorOf(kind))
        }
        return list
    }

    private fun apiErrorOf(kind: ApiError.Kind): ApiError = when (kind) {
        ApiError.Kind.NO_KEY -> ApiError.NoKey()
        ApiError.Kind.AUTH -> ApiError.Auth()
        ApiError.Kind.QUOTA -> ApiError.Quota()
        ApiError.Kind.MODEL_NOT_FOUND -> ApiError.ModelNotFound()
        ApiError.Kind.CONTEXT_OVERFLOW -> ApiError.ContextOverflow()
        ApiError.Kind.FILTERED -> ApiError.Filtered()
        ApiError.Kind.SERVER -> ApiError.Server()
        ApiError.Kind.NETWORK -> ApiError.Network()
        ApiError.Kind.STREAM_BROKEN -> ApiError.StreamBroken()
        ApiError.Kind.BAD_RESPONSE -> ApiError.BadResponse()
    }

    @Test
    fun copyConstantsUseTaAndAvoidTechnicalWords() {
        visibleCopyConstants().forEach { (name, text) ->
            PRONOUN_BANNED.forEach { pronoun ->
                assertFalse("「$name」出现角色代词「$pronoun」：$text", text.contains(pronoun))
            }
            TECH_BANNED.forEach { word ->
                assertFalse("「$name」出现技术词「$word」：$text", text.contains(word))
            }
            // 说人话的基础检查：不留"我们"，不做客服腔
            assertFalse("「$name」出现\"我们\"：$text", text.contains("我们"))
        }
    }

    @Test
    fun quotaCopyIsUnifiedAndActionable() {
        // 额度到顶的文案统一为「今天到上限了。想接着聊，就把上限调高。」（附录 A 4.9）。
        assertEquals("今天到上限了。想接着聊，就把上限调高。", UsageLimits.DAILY_LIMIT_MESSAGE)
        assertTrue("要告诉用户怎么恢复", UsageLimits.DAILY_LIMIT_MESSAGE.contains("把上限调高"))
        // 月上限是不同事实（不是"今天"），结构保持一致、事实必须准确。
        assertTrue(UsageLimits.MONTHLY_LIMIT_MESSAGE.contains("这个月到上限了"))
        assertTrue(UsageLimits.MONTHLY_LIMIT_MESSAGE.contains("把上限调高"))
    }
}
