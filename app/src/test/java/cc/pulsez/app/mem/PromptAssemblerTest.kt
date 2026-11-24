package cc.pulsez.app.mem

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

/**
 * [PromptAssembler] 的五段布局、封顶与缓存前缀断言。
 *
 * 对应文档：03、10。
 */
class PromptAssemblerTest {

    private val baseTime = 1_700_000_000_000L

    // MemTime 按设备时区解释/展示日期：期望 epoch 用同一口径构造，
    // 测试不再依赖"跑测机器的默认时区恰好是 UTC"。
    private fun date(iso: String): Long =
        LocalDate.parse(iso).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()

    private fun sampleMemory(): MemoryTable = MemoryTable(
        facts = listOf(
            Fact(key = "称呼", value = "她喜欢被叫「阿淮」"),
            Fact(key = "城市", value = "在杭州做设计"),
        ),
        events = listOf(Event(value = "说周五有一场面试", happenedAt = date("2026-11-18"), importance = 4)),
        loops = listOf(Loop(value = "面试结果还没告诉她", dueAt = date("2026-11-21"))),
        relation = Relation(stage = "暧昧", nicknameByMe = "阿淮", nicknameForMe = "小鹿", mood = "轻松"),
        boundaries = listOf(Boundary(value = "不要主动提前男友")),
        prefs = listOf(Pref(value = "喜欢雨天，讨厌被叫全名")),
    )

    private fun baseInput(): AssembleInput = AssembleInput(
        personaLayer = "你是林晚，25 岁，在杭州做设计，说话有点懒。",
        styleSamples = listOf(
            MemMessage.user("今天好累"),
            MemMessage.assistant("那先别撑着了，去躺会儿。"),
        ),
        protocolEnabled = true,
        chronicle = "你们第一次聊天是在一个雨天。",
        history = listOf(
            MemMessage.user("在吗", baseTime),
            MemMessage.assistant("在的，怎么啦", baseTime + 1_000L),
        ),
        memory = sampleMemory(),
        recall = "【你们过去聊过，以下是原文摘录。】\n(11-18) 她说：周五要去面试，有点紧张。",
        userMessage = "你还记得我那个面试吗？",
        budget = 300_000,
    )

    @Test
    fun fiveSegmentsInDocumentedOrder() {
        val input = baseInput()
        val messages = PromptAssembler.assemble(input)
        assertEquals(6, messages.size)

        // ① 协议层 + 人设层 + 语气样本
        assertEquals(MemMessage.ROLE_SYSTEM, messages[0].role)
        assertTrue(messages[0].content.contains("[[pz:emo="))
        assertTrue(messages[0].content.contains("|rel="))
        assertTrue(messages[0].content.contains("人设"))
        assertTrue(messages[0].content.contains("语气样本"))
        assertTrue(messages[0].content.indexOf("输出协议") < messages[0].content.indexOf("【人设】"))

        // ② 编年史
        assertEquals(MemMessage.ROLE_SYSTEM, messages[1].role)
        assertTrue(messages[1].content.contains("编年史"))
        assertTrue(messages[1].content.contains("雨天"))

        // ③ 历史原文原样保留
        assertEquals(MemMessage.ROLE_USER, messages[2].role)
        assertEquals("在吗", messages[2].content)
        assertEquals(MemMessage.ROLE_ASSISTANT, messages[3].role)
        assertEquals("在的，怎么啦", messages[3].content)

        // ④ 临场块：记忆表 + 召回 + 尾锚
        val ephemeral = messages[messages.size - 2]
        assertEquals(MemMessage.ROLE_SYSTEM, ephemeral.role)
        assertTrue(ephemeral.content.contains("【记忆】"))
        assertTrue(ephemeral.content.contains("不要主动提前男友"))
        assertTrue(ephemeral.content.contains("面试结果还没告诉她"))
        assertTrue(ephemeral.content.contains("阶段=暧昧"))
        assertTrue(ephemeral.content.contains("11-18"))
        assertTrue(ephemeral.content.contains("尾锚"))

        // ⑤ 最新用户消息
        assertEquals(MemMessage.ROLE_USER, messages.last().role)
        assertEquals(input.userMessage, messages.last().content)
    }

    @Test
    fun protocolLayerCanBeDisabled() {
        assertEquals("", PromptAssembler.buildProtocolLayer(false))
        val enabled = PromptAssembler.buildProtocolLayer(true)
        assertTrue(enabled.contains("[[pz:emo="))
        assertTrue(enabled.contains("诚实"))
        assertTrue(enabled.contains("【人设】"))
        assertTrue(enabled.contains("自由展开"))
        assertTrue(enabled.contains("不限句数"))
        assertTrue(enabled.contains("顺带提一条"))
        assertTrue(enabled.contains("尊重边界"))
        assertFalse(enabled.contains("1–3 句"))

        val messages = PromptAssembler.assemble(baseInput().copy(protocolEnabled = false))
        assertFalse(messages[0].content.contains("[[pz:"))
        assertTrue(messages[0].content.contains("人设"))
        assertEquals(MemMessage.ROLE_SYSTEM, messages[0].role)
    }

    @Test
    fun creativeModePropagatesToProtocolAndTailAnchor() {
        val messages = PromptAssembler.assemble(baseInput().copy(creativeMode = true))
        val first = messages[0].content
        val ephemeral = messages[messages.size - 2].content
        assertTrue(first.contains("创作模式"))
        assertTrue(first.contains("不限字数"))
        assertFalse(first.contains("1–3 句"))
        assertTrue(ephemeral.contains("尾锚"))
        assertTrue(ephemeral.contains("不限字数"))
        assertTrue(ephemeral.contains("始终入戏"))
    }

    @Test
    fun historyIsNeverTrimmedInTheMiddle() {
        val history = (1..50).map { index ->
            MemMessage.user("历史消息 $index", baseTime + index * 1_000L)
        }
        val messages = PromptAssembler.assemble(baseInput().copy(history = history))
        // ① 之后紧跟 50 条历史原文，一条不少且顺序不变
        val historySlice = messages.subList(2, 2 + 50)
        assertEquals(history.map { it.content }, historySlice.map { it.content })
    }

    @Test
    fun ephemeralLayerCappedAt1500Tokens() {
        val bigMemory = MemoryTable(
            facts = (1..200).map { index ->
                Fact(key = "key$index", value = "这是一条比较长的事实描述，用来把临场块撑到上限。$index")
            },
            events = (1..100).map { index -> Event(value = "事件 $index：今天发生了一些值得记住的事情") },
            loops = (1..20).map { index -> Loop(value = "未完结线索 $index：还在等结果") },
            relation = Relation(stage = "暧昧", nicknameByMe = "阿淮", nicknameForMe = "小鹿"),
            boundaries = (1..20).map { index -> Boundary(value = "边界 $index：不要主动提这件事") },
            prefs = (1..60).map { index -> Pref(value = "偏好 $index：喜欢安静的地方") },
        )
        val longRecall = "【你们过去聊过】".repeat(100) + "她说：那次面试后来呢？".repeat(200)
        val messages = PromptAssembler.assemble(
            baseInput().copy(memory = bigMemory, recall = longRecall),
        )
        val ephemeral = messages[messages.size - 2]
        val tokens = TokenEstimator.estimateMessages(listOf(ephemeral))
        assertTrue("④ 段 $tokens token 超过 1500", tokens <= PromptAssembler.EPHEMERAL_LAYER_MAX_TOKENS)
    }

    @Test
    fun userMessageKeepsLongMessageAndCapsAt16000Tokens() {
        val mediumLongMessage = "这是一句很长的用户消息，里面有很多细节和情绪。".repeat(300)
        val mediumMessages = PromptAssembler.assemble(baseInput().copy(userMessage = mediumLongMessage))
        val last = mediumMessages.last()
        assertEquals(MemMessage.ROLE_USER, last.role)
        assertEquals(mediumLongMessage, last.content)

        val extremeMessage = "这是一句很长的用户消息，里面有很多细节和情绪。".repeat(1200)
        val cappedMessages = PromptAssembler.assemble(baseInput().copy(userMessage = extremeMessage))
        val cappedLast = cappedMessages.last()
        assertEquals(MemMessage.ROLE_USER, cappedLast.role)
        assertTrue(TokenEstimator.estimateMessages(listOf(cappedLast)) <= PromptAssembler.USER_MESSAGE_MAX_TOKENS)
        assertTrue(cappedLast.content.length < extremeMessage.length)
    }

    @Test
    fun renderMemoryKeepsHighPrioritySectionsAndCaps() {
        val table = MemoryTable(
            facts = (1..100).map { index -> Fact(key = "key$index", value = "事实 $index：内容稍微长一点用来占位") },
            events = listOf(Event(value = "说周五有一场面试", happenedAt = date("2026-11-18"), importance = 5)),
            loops = listOf(Loop(value = "面试结果还没告诉她", dueAt = date("2026-11-21"))),
            relation = Relation(stage = "暧昧", nicknameByMe = "阿淮", nicknameForMe = "小鹿", mood = "轻松"),
            boundaries = listOf(Boundary(value = "不要主动提前男友"), Boundary(value = "不要问她体重")),
            prefs = listOf(Pref(value = "喜欢雨天")),
        )
        val text = PromptAssembler.renderMemory(table)
        assertTrue(TokenEstimator.estimate(text) <= PromptAssembler.MEMORY_RENDER_TOKENS)
        // 边界 / 线索 / 关系必须保住
        assertTrue(text.contains("不要主动提前男友"))
        assertTrue(text.contains("不要问她体重"))
        assertTrue(text.contains("面试结果还没告诉她"))
        assertTrue(text.contains("阶段=暧昧"))
        // loops 只出现在"未完结线索"里，不能在"其它"里重复（03）
        var occurrences = 0
        var cursor = text.indexOf("面试结果还没告诉她")
        while (cursor >= 0) {
            occurrences++
            cursor = text.indexOf("面试结果还没告诉她", cursor + 1)
        }
        assertEquals(1, occurrences)
        // 其余条目最多 40 条
        val otherLines = text.lines().count { it.startsWith("- [") }
        assertTrue("其余条目行数 $otherLines 超过 40", otherLines <= PromptAssembler.MEMORY_REST_ITEMS)
        assertTrue(otherLines > 0)
    }

    @Test
    fun renderMemoryHonoursHardCap() {
        val table = sampleMemory()
        for (cap in intArrayOf(80, 150, 300, 900)) {
            val text = PromptAssembler.renderMemory(table, cap)
            assertTrue("cap=$cap 实际=${TokenEstimator.estimate(text)}", TokenEstimator.estimate(text) <= cap)
        }
        assertEquals("", PromptAssembler.renderMemory(table, 8))
    }

    @Test
    fun tailAnchorCappedAndCarriesKeyRules() {
        val anchor = PromptAssembler.buildTailAnchor(
            persona = "你是林晚，25 岁，在杭州做设计。",
            relation = Relation(stage = "暧昧", nicknameByMe = "阿淮", nicknameForMe = "小鹿"),
            boundaries = listOf(
                Boundary(value = "不要主动提前男友"),
                Boundary(value = "不要问她体重"),
                Boundary(value = "不要提她家人"),
                Boundary(value = "第四条边界不应出现"),
            ),
        )
        assertTrue(TokenEstimator.estimate(anchor) <= PromptAssembler.TAIL_ANCHOR_MAX_TOKENS)
        assertTrue(anchor.contains("贴合人设"))
        assertTrue(anchor.contains("始终入戏"))
        assertTrue(anchor.contains("关系阶段=暧昧"))
        assertTrue(anchor.contains("TA 叫你「阿淮」"))
        assertTrue(anchor.contains("不要主动提前男友"))
        assertFalse(anchor.contains("第四条边界不应出现"))
    }

    @Test
    fun cachePrefixAtLeast95PercentAcrossRounds() {
        val filler = "我们今天聊了很多事情，关于工作、天气、晚饭和周末的安排。"
        val history = (1..300).map { index ->
            val content = filler.repeat(11) + "（第 $index 条）"
            val createdAt = baseTime + index * 60_000L
            if (index % 2 == 0) {
                MemMessage.assistant(content, createdAt)
            } else {
                MemMessage.user(content, createdAt)
            }
        }
        val chronicle = "更早之前，你们在一个雨天认识，后来一起吃过两次饭。".repeat(20)
        val roundN = PromptAssembler.assemble(
            baseInput().copy(
                chronicle = chronicle,
                history = history,
                userMessage = "那我们周末去西湖走走？",
                recall = "【你们过去聊过】".repeat(20),
            ),
        )
        val roundN1 = PromptAssembler.assemble(
            baseInput().copy(
                chronicle = chronicle,
                history = history + listOf(
                    MemMessage.user("那我们周末去西湖走走？", baseTime + 301 * 60_000L),
                    MemMessage.assistant("好啊，几点？", baseTime + 302 * 60_000L),
                ),
                userMessage = "早上九点可以吗？",
                recall = "【你们过去聊过】".repeat(20),
            ),
        )

        val total = TokenEstimator.estimateMessages(roundN)
        val prefix = PromptAssembler.commonPrefixTokens(roundN, roundN1)
        val ratio = prefix.toDouble() / total.toDouble()
        assertTrue(
            "公共前缀 $prefix / 总长 $total = $ratio，低于 95%",
            ratio >= 0.95,
        )
        // 分叉点必须落在 ④（前缀应覆盖 ①②③，但不能覆盖 ④⑤）
        assertTrue(prefix < total)
    }

    @Test
    fun commonPrefixStopsAtFirstDifferingMessage() {
        val a = listOf(
            MemMessage.system("同一段 system"),
            MemMessage.user("同样的用户消息"),
        )
        val b = listOf(
            MemMessage.system("同一段 system"),
            MemMessage.user("同样的用户消息"),
            MemMessage.assistant("她多了一句"),
        )
        val c = listOf(
            MemMessage.system("同一段 system"),
            MemMessage.user("不同的用户消息"),
        )
        assertEquals(TokenEstimator.estimateMessages(a), PromptAssembler.commonPrefixTokens(a, b))
        val mismatchedPrefix = PromptAssembler.commonPrefixTokens(a, c)
        assertTrue(mismatchedPrefix < TokenEstimator.estimateMessages(a))
        assertTrue(mismatchedPrefix > 0)
    }

    @Test
    fun emptyChronicleIsOmitted() {
        val messages = PromptAssembler.assemble(baseInput().copy(chronicle = null))
        assertFalse(messages[1].content.contains("编年史"))
        assertEquals(MemMessage.ROLE_USER, messages[1].role)
    }

    @Test
    fun emptyMemoryAndRecallOmitsEphemeralLayer() {
        val noMemoryMessages = PromptAssembler.assemble(
            AssembleInput(
                personaLayer = "你是林晚。",
                chronicle = null,
                history = listOf(
                    MemMessage.user("早安", baseTime),
                    MemMessage.assistant("早安呀", baseTime + 1_000L),
                ),
                memory = null,
                recall = null,
                userMessage = "今天去哪玩？",
            ),
        )
        assertEquals(4, noMemoryMessages.size)
        assertEquals(MemMessage.ROLE_SYSTEM, noMemoryMessages[0].role)
        assertEquals("早安", noMemoryMessages[1].content)
        assertEquals("早安呀", noMemoryMessages[2].content)
        assertEquals("今天去哪玩？", noMemoryMessages[3].content)
        assertTrue(noMemoryMessages.none { it.content.contains("【临场块】") })

        val blankTableMessages = PromptAssembler.assemble(
            AssembleInput(
                personaLayer = "你是林晚。",
                memory = MemoryTable(
                    facts = listOf(Fact(key = "city", value = "旧城市", status = MemoryStatus.SUPERSEDED, validTo = 10L)),
                    relation = Relation(stage = " ", nicknameByMe = null, nicknameForMe = "", mood = null),
                ),
                recall = "   ",
                userMessage = "在干嘛",
            ),
        )
        assertEquals(2, blankTableMessages.size)
        assertEquals("", PromptAssembler.renderMemory(MemoryTable()))
    }

    @Test
    fun ephemeralLayerIsPresentWhenAnyMemoryRelationBoundaryOrRecallExists() {
        val withRelationOnly = PromptAssembler.assemble(
            AssembleInput(
                personaLayer = "你是林晚。",
                memory = MemoryTable(relation = Relation(nicknameByMe = "小满")),
                recall = null,
                userMessage = "在吗",
            ),
        )
        assertEquals(3, withRelationOnly.size)
        assertEquals(MemMessage.ROLE_SYSTEM, withRelationOnly[1].role)
        assertTrue(withRelationOnly[1].content.contains("【临场块】"))
        assertTrue(withRelationOnly[1].content.contains("TA 叫你「小满」"))
        assertTrue(withRelationOnly[1].content.contains("【尾锚·保持角色】"))

        val withBoundaryOnly = PromptAssembler.assemble(
            AssembleInput(
                personaLayer = "你是林晚。",
                memory = MemoryTable(boundaries = listOf(Boundary(value = "不提前任"))),
                userMessage = "在吗",
            ),
        )
        assertEquals(3, withBoundaryOnly.size)
        assertTrue(withBoundaryOnly[1].content.contains("最硬的边界：不提前任"))

        val withRecallOnly = PromptAssembler.assemble(
            AssembleInput(
                personaLayer = "你是林晚。",
                memory = null,
                recall = "【你们过去聊过，以下是原文摘录。】\n" + "周末约好去公园散步顺便喝咖啡聊天。".repeat(3),
                userMessage = "走吗",
            ),
        )
        assertEquals(3, withRecallOnly.size)
        assertTrue(withRecallOnly[1].content.contains("【临场块】"))
        assertTrue(withRecallOnly[1].content.contains("周末约好去公园散步"))
        assertTrue(withRecallOnly[1].content.contains("【尾锚·保持角色】"))
    }

    @Test
    fun styleSamplesKeepSingleCleanPrefixWithoutNarratorDoublePrefix() {
        val messages = PromptAssembler.assemble(
            AssembleInput(
                personaLayer = "你是阿淮。",
                styleSamples = listOf(
                    MemMessage.assistant("别慌，慢慢说。"),
                    MemMessage.system("TA 的语气示例：我在呢。"),
                ),
                userMessage = "你好",
            ),
        )
        val first = messages.first().content
        assertTrue(first.contains("TA：别慌，慢慢说。"))
        assertTrue(first.contains("TA：我在呢。"))
        assertFalse(first.contains("旁白：TA 的语气示例："))
    }
}
