package cc.pulsez.app.mem

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

/**
 * [MemoryExtractionPrompt] 的六字段白名单与原文格式化测试。
 *
 * 对应文档：03（抽取 prompt 硬约束）。
 */
class MemoryExtractionPromptTest {

    // MemTime 按设备时区格式化：时间戳按同一口径构造，
    // 断言里的 "" 才在任意机器时区下都成立。
    private val timestamp: Long =
        LocalDate.of(2026, 11, 18).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()

    @Test
    fun systemPromptContainsSixFieldsAndHardRules() {
        val system = MemoryExtractionPrompt.buildSystem()
        listOf("facts", "events", "loops", "relation", "boundaries", "prefs").forEach { field ->
            assertTrue("缺少顶层字段 $field", system.contains(field))
        }
        // 每条 v ≤ 200 字 / 只写用户明说 / conf 0.6 门槛 / 单层 JSON
        assertTrue(system.contains("200"))
        assertTrue(system.contains("0.6"))
        assertTrue(system.contains("用户明说"))
        assertTrue(system.contains("单层 JSON"))
        assertTrue(system.contains("不要把角色说的话写成用户事实"))
        assertFalse("系统提示不应出现 markdown 代码块", system.contains("```"))
    }

    @Test
    fun userPromptContainsMessagesRolesAndTimestamps() {
        val user = MemoryExtractionPrompt.buildUser(
            listOf(
                MemMessage.user("我周五要面试", timestamp),
                MemMessage.assistant("那我把闹钟给你调到八点", timestamp + 60_000L),
            ),
        )
        assertTrue(user.contains("我周五要面试"))
        assertTrue(user.contains("那我把闹钟给你调到八点"))
        assertTrue(user.contains("2026-11-18"))
        assertTrue(user.contains("用户："))
        assertTrue(user.contains("assistant"))
        // 六字段骨架
        assertTrue(user.contains("\"facts\""))
        assertTrue(user.contains("\"prefs\""))
    }

    @Test
    fun userPromptHandlesUnknownTimestampAndEmptyShard() {
        val user = MemoryExtractionPrompt.buildUser(listOf(MemMessage.user("没有时间戳的消息")))
        assertTrue(user.contains("时间未知"))
        assertTrue(user.contains("没有时间戳的消息"))

        val empty = MemoryExtractionPrompt.buildUser(emptyList())
        assertTrue(empty.contains("没有消息"))
    }
}
