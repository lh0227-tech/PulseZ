package cc.pulsez.app.core

import cc.pulsez.app.mem.MemMessage
import cc.pulsez.app.net.ChatRequest
import cc.pulsez.app.net.Usage
import cc.pulsez.app.wire.Pricing
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 群聊「各自记忆」抽取的纯逻辑测试，保证记忆彼此隔离。
 *
 * 核心断言：A 的抽取 prompt 里只有「用户说过的话 + A 自己说过的话」，
 * **B 的台词一条都不能出现**（这是"别人的台词不进它记忆"的第一道闸）。
 *
 * 不连真机、不联网：抽取客户端与写入端全部是 Fake。
 */
class GroupMemoryExtractorTest {

    private fun message(
        role: String,
        content: String,
        speakerCharacterId: String? = null,
        createdAt: Long = 0L,
    ): GroupMessage = GroupMessage(
        role = role,
        content = content,
        speakerCharacterId = speakerCharacterId,
        createdAt = createdAt,
    )

    private fun historyWithOtherMember(): List<GroupMessage> = listOf(
        message(MemMessage.ROLE_USER, "我最近养了只猫，叫豆豆", createdAt = 1L),
        message(MemMessage.ROLE_ASSISTANT, "豆豆这名字好可爱", "a", createdAt = 2L),
        message(MemMessage.ROLE_ASSISTANT, "我家狗叫大黄，比你那只乖多了", "b", createdAt = 3L),
        message(MemMessage.ROLE_USER, "你们都喜欢小动物呀", createdAt = 4L),
        message(MemMessage.ROLE_ASSISTANT, "是呀，我最喜欢猫了", "a", createdAt = 5L),
    )

    // ------------------------------------------------------------------ 分片隔离

    @Test
    fun shardKeepsOnlyUserMessagesAndOwnLines() {
        val shard = GroupMemoryShard.select(historyWithOtherMember(), characterId = "a")

        assertEquals(
            listOf(
                "我最近养了只猫，叫豆豆",
                "豆豆这名字好可爱",
                "你们都喜欢小动物呀",
                "是呀，我最喜欢猫了",
            ),
            shard.map { it.content },
        )
        assertEquals(
            listOf(
                MemMessage.ROLE_USER,
                MemMessage.ROLE_ASSISTANT,
                MemMessage.ROLE_USER,
                MemMessage.ROLE_ASSISTANT,
            ),
            shard.map { it.role },
        )
        assertFalse("A 的分片里不能出现 B 的台词", shard.any { it.content.contains("大黄") })
        assertFalse("A 的分片里不能出现 B 的具体句子", shard.any { it.content == "我家狗叫大黄，比你那只乖多了" })
    }

    @Test
    fun otherCharactersShardIsIsolatedTheOtherWayRound() {
        val shard = GroupMemoryShard.select(historyWithOtherMember(), characterId = "b")
        val text = shard.joinToString("\n") { it.content }

        assertTrue(text.contains("我家狗叫大黄"))
        assertFalse("B 的分片里不能出现 A 的台词", text.contains("豆豆这名字好可爱"))
        assertFalse(text.contains("是呀，我最喜欢猫了"))
    }

    @Test
    fun shardIsCappedAtRecentTwelveMessages() {
        // 15 轮 = 30 条消息（用户 + A 各一条），窗口只取最近 12 条 → 6 用户 + 6 A。
        val history = buildList {
            repeat(15) { index ->
                add(message(MemMessage.ROLE_USER, "用户第${index + 1}句", createdAt = index * 2L + 1))
                add(message(MemMessage.ROLE_ASSISTANT, "阿甲第${index + 1}句", "a", createdAt = index * 2L + 2))
            }
        }
        val shard = GroupMemoryShard.select(history, characterId = "a")

        assertEquals(GroupMemoryShard.RECENT_MESSAGE_LIMIT, shard.size)
        assertEquals("用户第10句", shard.first().content)
        assertEquals("阿甲第15句", shard.last().content)
        assertFalse(shard.any { it.content.contains("第1句") || it.content.contains("第9句") })
    }

    @Test
    fun filterRunsBeforeWindowSoEightRoundsAreNotSqueezedOutByOthers() {
        // 8 轮 = 16 条消息；A 只在第 1 轮说过话。若先取最近 12 条，A 的素材会被挤出窗口。
        val history = buildList {
            repeat(8) { index ->
                add(message(MemMessage.ROLE_USER, "用户第${index + 1}轮", createdAt = index * 3L + 1))
                add(
                    message(
                        MemMessage.ROLE_ASSISTANT,
                        if (index == 0) "阿甲第1轮" else "阿乙第${index + 1}轮",
                        if (index == 0) "a" else "b",
                        createdAt = index * 3L + 2,
                    ),
                )
            }
        }
        val shard = GroupMemoryShard.select(history, characterId = "a")

        // 先过滤再取窗口：A 的 8 条用户消息 + 1 条自己的话全部保留（共 9 条 ≤ 12）。
        assertEquals(8, shard.count { it.role == MemMessage.ROLE_USER })
        assertEquals(1, shard.count { it.role == MemMessage.ROLE_ASSISTANT })
        assertTrue("第 1 轮（最早）的用户消息不能被别人的台词挤出窗口", shard.first().content == "用户第1轮")
        assertFalse(shard.any { it.content.contains("阿乙") })
    }

    // ------------------------------------------------------------------ 抽取 prompt

    @Test
    fun extractionPromptContainsUserAndOwnLinesButNotOtherMembers() = runBlocking {
        var capturedRequest: ChatRequest? = null
        var appliedPatch: String? = null
        val recorded = mutableListOf<Usage>()
        val patch = """{"facts":[{"k":"宠物","v":"用户养了一只叫豆豆的猫","conf":0.9,
            "valid_from":"2026-11-01"}],"events":[],"loops":[],"relation":{},"boundaries":[],"prefs":[]}"""
            .trimIndent()

        val extractor = GroupMemoryExtractor(
            completeJson = { request ->
                capturedRequest = request
                patch
            },
            applyPatch = { json -> appliedPatch = json },
            recordUsage = { usage -> recorded += usage },
        )
        val shard = GroupMemoryShard.select(historyWithOtherMember(), characterId = "a")
        val extracted = extractor.extract(model = "deepseek-flash", shard = shard)

        assertTrue(extracted)
        val request = capturedRequest
        assertTrue("必须真的发起了一次抽取调用", request != null)
        assertEquals("deepseek-flash", request!!.model)
        assertTrue("抽取走非流式 + JSON 模式", !request.stream && request.jsonMode)
        assertEquals(GroupMemoryExtractor.EXTRACTION_MAX_TOKENS, request.maxTokens)

        val systemPrompt = request.messages[0].content
        val userPrompt = request.messages[1].content
        assertTrue("system 必须是 MemoryExtractionPrompt.buildSystem()", systemPrompt.contains("记忆抽取器"))
        assertEquals(cc.pulsez.app.mem.MemoryExtractionPrompt.buildSystem(), systemPrompt)

        // 输入侧隔离：用户的话 + A 自己的话在；B 的台词不在。
        assertTrue(userPrompt.contains("我最近养了只猫，叫豆豆"))
        assertTrue(userPrompt.contains("豆豆这名字好可爱"))
        assertTrue(userPrompt.contains("是呀，我最喜欢猫了"))
        assertFalse("A 的抽取 prompt 不能出现 B 的台词", userPrompt.contains("大黄"))
        assertFalse(userPrompt.contains("我家狗叫"))

        // 写入侧：applyPatch 拿到模型原始 JSON，交给调用方绑定 ownerId = A。
        assertEquals(patch, appliedPatch)

        // 记账：估算 usage 非零且金额 > 0（估价口径见 GroupUsageEstimator）。
        assertEquals(1, recorded.size)
        assertTrue(recorded.single().promptTokens > 0)
        assertTrue(recorded.single().completionTokens > 0)
        assertTrue(Pricing.costOf("deepseek-flash", recorded.single()) > 0.0)
    }

    @Test
    fun emptyShardDoesNotCallModelOrWriteAnything() = runBlocking {
        var calls = 0
        val extractor = GroupMemoryExtractor(
            completeJson = { calls++; "{}" },
            applyPatch = { calls++ },
        )

        val result = extractor.extract(model = "deepseek-flash", shard = emptyList())

        assertFalse(result)
        assertEquals("空分片不能产生任何调用", 0, calls)
    }

    @Test
    fun extractorSurfaceKeepsSingleJsonCallPerRun() = runBlocking {
        var calls = 0
        var patches = 0
        val extractor = GroupMemoryExtractor(
            completeJson = { calls++; "{}" },
            applyPatch = { patches++ },
        )

        extractor.extract("deepseek-flash", GroupMemoryShard.select(historyWithOtherMember(), "a"))

        assertEquals("每 8 轮一次抽取 = 一次调用", 1, calls)
        assertEquals(1, patches)
    }
}
