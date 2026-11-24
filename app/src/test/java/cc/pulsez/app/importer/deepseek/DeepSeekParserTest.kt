package cc.pulsez.app.importer.deepseek

import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * DeepSeek v3 导出解析器单测： 映射表逐行 + 项。
 *
 * 全部内联 JSON 字符串、纯 JVM、不联网、不碰 ZIP 与数据库。
 * 正文用 `IMPORTED-*` 唯一标记，便于后续落库/抽取隔离断言引用。
 */
class DeepSeekParserTest {

    /** 固定"当前时间"，让未来时间拉回可断言（不依赖测试机器时钟）。 */
    private val now: Long = Instant.parse("2026-11-20T00:00:00.000Z").toEpochMilli()

    private fun ts(iso: String): Long = Instant.parse(iso).toEpochMilli()

    private fun codeOf(block: () -> Unit): String =
        assertThrows(DeepSeekParseException::class.java) { block() }.code

    private fun parse(
        json: String,
        expectedId: String,
        fallbackUpdatedAt: Long? = null,
    ): ParsedDeepSeekSession =
        DeepSeekParser.parseSession(
            text = json,
            expectedSessionId = expectedId,
            fallbackUpdatedAt = fallbackUpdatedAt,
            now = now,
        )

    // ---- 主链路 ----

    @Test
    fun `正常单分支按时间序产出 turn`() {
        val json = """
            {
              "id": "sess-normal",
              "title": "关于搬家的一次长谈",
              "pinned": false,
              "current_message_id": 3,
              "inserted_at": "2026-11-02T09:00:00.000Z",
              "updated_at": "2026-11-02T09:02:00.000Z",
              "messages": [
                {
                  "id": 1, "parent_id": null, "role": "USER", "status": "FINISHED",
                  "inserted_at": "2026-11-02T09:00:00.000Z",
                  "fragments": [ { "type": "REQUEST", "content": "IMPORTED-USER-42 我想把房子退了" } ]
                },
                {
                  "id": 2, "parent_id": 1, "role": "ASSISTANT", "status": "FINISHED",
                  "inserted_at": "2026-11-02T09:01:00.000Z",
                  "fragments": [
                    { "type": "THINK", "content": "IMPORTED-THINK-42", "elapsed_secs": 3.2 },
                    { "type": "RESPONSE", "content": "IMPORTED-ASSISTANT-42 先看合同里的违约金" }
                  ]
                },
                {
                  "id": 3, "parent_id": 2, "role": "USER", "status": "FINISHED",
                  "inserted_at": "2026-11-02T09:02:00.000Z",
                  "fragments": [ { "type": "REQUEST", "content": "IMPORTED-USER-43 好" } ]
                }
              ]
            }
        """.trimIndent()

        val parsed = parse(json, "sess-normal")

        assertEquals("sess-normal", parsed.sourceSessionId)
        assertEquals("关于搬家的一次长谈", parsed.title)
        assertEquals(ts("2026-11-02T09:00:00.000Z"), parsed.insertedAt)
        assertEquals(ts("2026-11-02T09:02:00.000Z"), parsed.updatedAt)
        assertEquals(3, parsed.turns.size)
        assertEquals(DeepSeekTurn.ROLE_USER, parsed.turns[0].role)
        assertEquals("IMPORTED-USER-42 我想把房子退了", parsed.turns[0].text)
        assertEquals(ts("2026-11-02T09:00:00.000Z"), parsed.turns[0].at)
        assertEquals(DeepSeekTurn.ROLE_ASSISTANT, parsed.turns[1].role)
        assertEquals("IMPORTED-ASSISTANT-42 先看合同里的违约金", parsed.turns[1].text)
        assertEquals(ts("2026-11-02T09:01:00.000Z"), parsed.turns[1].at)
        assertEquals("IMPORTED-USER-43 好", parsed.turns[2].text)
        // 源消息 id 要原样带出（幂等键：deepseek:v3:<characterId>:<sessionId>:<sourceMessageId>）
        assertEquals(listOf(1L, 2L, 3L), parsed.turns.map { it.sourceMessageId })
        assertTrue("THINK 等丢弃类型的正文不能进结果", parsed.turns.none { it.text.contains("IMPORTED-THINK") })
        assertTrue(parsed.issues.isEmpty())
    }

    @Test
    fun `preview retains title time count and damage without exposing message text`() {
        val startedAt = ts("2026-11-02T09:00:00.000Z")
        val json = """{"id":"preview-source","title":"Synthetic title","inserted_at":"2026-11-02T09:00:00.000Z","messages":[{"id":1,"role":"USER","status":"FINISHED","inserted_at":"2026-11-02T09:00:00.000Z","fragments":[{"type":"REQUEST","content":"SYNTHETIC-PRIVATE-BODY"}]}]}"""
        val preview = DeepSeekParser.previewSession(json, "preview-source")

        assertEquals("Synthetic title", preview.title)
        assertEquals(startedAt, preview.sourceStartedAt)
        assertEquals(1, preview.messageCount)
        assertEquals(listOf(1L), preview.sourceMessageIds)
        assertNull(preview.damageCode)
        assertFalse(preview.toString().contains("SYNTHETIC-PRIVATE-BODY"))

        val damaged = DeepSeekParser.previewSession("not-json", "damaged-source", fallbackStartedAt = startedAt)
        assertNull(damaged.title)
        assertEquals(startedAt, damaged.sourceStartedAt)
        assertEquals(0, damaged.messageCount)
        assertTrue(damaged.sourceMessageIds.isEmpty())
        assertEquals(DeepSeekParseException.MALFORMED_JSON, damaged.damageCode)
    }

    @Test
    fun `last message time is max source time among imported messages`() {
        val importedAt = ts("2020-03-04T05:06:07Z")
        val json = """
            {
              "id":"last-message-source",
              "inserted_at":"2019-01-01T00:00:00Z",
              "updated_at":"2040-01-01T00:00:00Z",
              "current_message_id":2,
              "messages":[
                {"id":1,"parent_id":null,"role":"USER","status":"FINISHED",
                 "inserted_at":"2020-03-04T05:06:07Z",
                 "fragments":[{"type":"REQUEST","content":"SYNTHETIC-IMPORTED-USER"}]},
                {"id":2,"parent_id":1,"role":"ASSISTANT","status":"FAILED",
                 "inserted_at":"2025-08-09T10:11:12Z",
                 "fragments":[{"type":"RESPONSE","content":"SYNTHETIC-NOT-IMPORTED"}]}
              ]
            }
        """.trimIndent()

        val parsed = DeepSeekParser.parseSession(
            text = json,
            expectedSessionId = "last-message-source",
            fallbackUpdatedAt = ts("2035-01-01T00:00:00Z"),
            now = now,
        )
        val preview = DeepSeekParser.previewSession(
            text = json,
            expectedSessionId = "last-message-source",
            fallbackUpdatedAt = ts("2035-01-01T00:00:00Z"),
            fallbackStartedAt = ts("2034-01-01T00:00:00Z"),
        )

        assertEquals(1, parsed.turns.size)
        assertEquals(importedAt, parsed.lastMessageAt)
        assertEquals(importedAt, preview.lastMessageAt)
        assertNull(preview.damageCode)
    }

    @Test
    fun `invalid message source time stays unknown despite session and index fallback dates`() {
        val json = """
            {
              "id":"unknown-last-message-source",
              "inserted_at":"bad-session-date",
              "current_message_id":1,
              "messages":[
                {"id":1,"parent_id":null,"role":"USER","status":"FINISHED",
                 "inserted_at":"not-a-date",
                 "fragments":[{"type":"REQUEST","content":"SYNTHETIC-BODY"}]}
              ]
            }
        """.trimIndent()
        val fallback = ts("2030-01-01T00:00:00Z")

        val preview = DeepSeekParser.previewSession(
            text = json,
            expectedSessionId = "unknown-last-message-source",
            fallbackUpdatedAt = fallback,
            fallbackStartedAt = fallback,
        )

        assertNull(preview.lastMessageAt)
        assertEquals(DeepSeekParseException.BAD_TIMESTAMP, preview.damageCode)
        assertEquals(fallback, preview.sourceStartedAt)
    }

    @Test
    fun `last message time stays the valid source timestamp when stored turn time is clamped`() {
        val sourceAt = ts("2099-01-01T00:00:00Z")
        val json = """
            {
              "id":"future-source-time",
              "inserted_at":"2099-01-01T00:00:00Z",
              "current_message_id":1,
              "messages":[
                {"id":1,"parent_id":null,"role":"USER","status":"FINISHED",
                 "inserted_at":"2099-01-01T00:00:00Z",
                 "fragments":[{"type":"REQUEST","content":"SYNTHETIC-BODY"}]}
              ]
            }
        """.trimIndent()

        val parsed = parse(json, "future-source-time")

        assertEquals(now - 1, parsed.turns.single().at)
        assertEquals(sourceAt, parsed.lastMessageAt)
    }

    @Test
    fun `多分支只导 current_message_id 回溯链`() {
        val json = """
            {
              "id": "sess-branch",
              "current_message_id": 4,
              "inserted_at": "2026-11-02T09:00:00.000Z",
              "messages": [
                { "id": 1, "parent_id": null, "role": "USER", "status": "FINISHED",
                  "inserted_at": "2026-11-02T09:00:00.000Z",
                  "fragments": [ { "type": "REQUEST", "content": "IMPORTED-ROOT" } ] },
                { "id": 2, "parent_id": 1, "role": "ASSISTANT", "status": "FINISHED",
                  "inserted_at": "2026-11-02T09:00:10.000Z",
                  "fragments": [ { "type": "RESPONSE", "content": "IMPORTED-KEPT-A" } ] },
                { "id": 3, "parent_id": 1, "role": "ASSISTANT", "status": "FINISHED",
                  "inserted_at": "2026-11-02T09:00:20.000Z",
                  "fragments": [ { "type": "RESPONSE", "content": "IMPORTED-BRANCH-DROPPED" } ] },
                { "id": 4, "parent_id": 2, "role": "USER", "status": "FINISHED",
                  "inserted_at": "2026-11-02T09:00:30.000Z",
                  "fragments": [ { "type": "REQUEST", "content": "IMPORTED-KEPT-B" } ] }
              ]
            }
        """.trimIndent()

        val parsed = parse(json, "sess-branch")

        assertEquals(3, parsed.turns.size)
        assertEquals(listOf("IMPORTED-ROOT", "IMPORTED-KEPT-A", "IMPORTED-KEPT-B"), parsed.turns.map { it.text })
        // 只带当前链的源消息 id：分支消息 3 不出现
        assertEquals(listOf(1L, 2L, 4L), parsed.turns.map { it.sourceMessageId })
        assertFalse(parsed.turns.any { it.text.contains("IMPORTED-BRANCH-DROPPED") })
    }

    @Test
    fun `current_message_id 缺失回落最大 id 且 updated_at 用 inserted_at 兜底`() {
        val json = """
            {
              "id": "sess-nocurrent",
              "inserted_at": "2026-11-02T09:00:00.000Z",
              "messages": [
                { "id": 1, "parent_id": null, "role": "USER", "status": "FINISHED",
                  "inserted_at": "2026-11-02T09:00:00.000Z",
                  "fragments": [ { "type": "REQUEST", "content": "IMPORTED-FIRST" } ] },
                { "id": 2, "parent_id": 1, "role": "ASSISTANT", "status": "FINISHED",
                  "inserted_at": "2026-11-02T09:00:10.000Z",
                  "fragments": [ { "type": "RESPONSE", "content": "IMPORTED-MIDDLE" } ] },
                { "id": 3, "parent_id": 2, "role": "USER", "status": "FINISHED",
                  "inserted_at": "2026-11-02T09:00:20.000Z",
                  "fragments": [ { "type": "REQUEST", "content": "IMPORTED-LAST" } ] }
              ]
            }
        """.trimIndent()

        val parsed = parse(json, "sess-nocurrent")

        assertEquals(3, parsed.turns.size)
        assertEquals("IMPORTED-LAST", parsed.turns.last().text)
        assertNull(parsed.title)
        assertEquals(parsed.insertedAt, parsed.updatedAt)
    }

    // ---- 未知字段 / 未知 fragment ----

    @Test
    fun `未知字段与未知 fragment 不崩且正文不进结果`() {
        val json = """
            {
              "id": "sess-unknown",
              "export_source": "future-version",
              "model": "deepseek-chat",
              "current_message_id": 2,
              "inserted_at": "2026-11-02T09:00:00.000Z",
              "messages": [
                { "id": 1, "parent_id": null, "role": "USER", "status": "FINISHED",
                  "extra": { "nested": [1, 2, 3] },
                  "inserted_at": "2026-11-02T09:00:00.000Z",
                  "fragments": [ { "type": "REQUEST", "content": "IMPORTED-KNOWN", "unknown_field": true } ] },
                { "id": 2, "parent_id": 1, "role": "ASSISTANT", "status": "FINISHED",
                  "inserted_at": "2026-11-02T09:00:10.000Z",
                  "fragments": [
                    { "type": "FUTURE_KIND", "content": "IMPORTED-UNKNOWN-BODY" },
                    { "type": "RESPONSE", "content": "IMPORTED-ASSISTANT-OK" }
                  ] }
              ]
            }
        """.trimIndent()

        val parsed = parse(json, "sess-unknown")

        assertEquals(listOf("IMPORTED-KNOWN", "IMPORTED-ASSISTANT-OK"), parsed.turns.map { it.text })
        assertTrue(parsed.turns.none { it.text.contains("IMPORTED-UNKNOWN-BODY") })
        assertTrue(
            parsed.issues.any {
                it.code == DeepSeekIssueCode.UNKNOWN_FRAGMENT && it.messageIndex == 1
            },
        )
    }

    @Test
    fun `TOOL_SEARCH FILE TEMPLATE_RESPONSE 等只丢正文并计 issue`() {
        val json = """
            {
              "id": "sess-fragments",
              "current_message_id": 3,
              "inserted_at": "2026-11-02T09:00:00.000Z",
              "messages": [
                { "id": 1, "parent_id": null, "role": "USER", "status": "FINISHED",
                  "inserted_at": "2026-11-02T09:00:00.000Z",
                  "fragments": [ { "type": "REQUEST", "content": "IMPORTED-USER-OK" } ] },
                { "id": 2, "parent_id": 1, "role": "ASSISTANT", "status": "FINISHED",
                  "inserted_at": "2026-11-02T09:00:10.000Z",
                  "fragments": [
                    { "type": "THINK", "content": "IMPORTED-THINK" },
                    { "type": "TIP", "content": "IMPORTED-TIP" },
                    { "type": "CONTENT_RISK", "content": "IMPORTED-RISK" },
                    { "type": "PARTIAL_FILE_READ", "content": "IMPORTED-PARTIAL" },
                    { "type": "TOOL_SEARCH", "queries": ["IMPORTED-TOOL"], "status": "FINISHED",
                      "results": [ { "url": "https://example.com/a", "title": "IMPORTED-TOOL-TITLE" } ] },
                    { "type": "TEMPLATE_RESPONSE", "content": "IMPORTED-TEMPLATE" },
                    { "type": "RESPONSE", "content": "IMPORTED-RESPONSE-OK" }
                  ] },
                { "id": 3, "parent_id": 2, "role": "USER", "status": "FINISHED",
                  "inserted_at": "2026-11-02T09:01:00.000Z",
                  "fragments": [
                    { "type": "FILE", "files": [
                      { "id": "img-1", "file_name": "IMPORTED-合同.jpg", "file_size": 182334,
                        "is_image": true, "export_path": "chat_sessions/sess-fragments_files/合同.jpg" }
                    ] }
                  ] }
              ]
            }
        """.trimIndent()

        val parsed = parse(json, "sess-fragments")

        assertEquals(2, parsed.turns.size)
        assertEquals("IMPORTED-USER-OK", parsed.turns[0].text)
        assertEquals("IMPORTED-RESPONSE-OK", parsed.turns[1].text)
        // 只有 TEMPLATE_RESPONSE 与 FILE 计 issue；THINK/TIP/TOOL_*/PARTIAL_FILE_READ 静默丢弃。
        assertEquals(2, parsed.issues.size)
        assertTrue(
            parsed.issues.any {
                it.code == DeepSeekIssueCode.TEMPLATE_RESPONSE && it.messageIndex == 1
            },
        )
        assertTrue(
            parsed.issues.any {
                it.code == DeepSeekIssueCode.FILE_DROPPED && it.messageIndex == 2
            },
        )
        // issue 只含原因码与下标，不能泄露正文/标题。
        assertFalse(parsed.issues.toString().contains("IMPORTED"))
        assertFalse(parsed.turns.joinToString("\n") { it.text }.contains("IMPORTED-TEMPLATE"))
        assertFalse(parsed.turns.joinToString("\n") { it.text }.contains("IMPORTED-合同"))
    }

    // ---- 消息状态 ----

    @Test
    fun `INCOMPLETE 与 WIP 保留 FAILED 与 CONTENT_FILTER 整条丢弃`() {
        val json = """
            {
              "id": "sess-status",
              "current_message_id": 4,
              "inserted_at": "2026-11-02T09:00:00.000Z",
              "messages": [
                { "id": 1, "parent_id": null, "role": "USER", "status": "INCOMPLETE",
                  "inserted_at": "2026-11-02T09:00:00.000Z",
                  "fragments": [ { "type": "REQUEST", "content": "IMPORTED-INCOMPLETE" } ] },
                { "id": 2, "parent_id": 1, "role": "ASSISTANT", "status": "WIP",
                  "inserted_at": "2026-11-02T09:00:10.000Z",
                  "fragments": [ { "type": "RESPONSE", "content": "IMPORTED-WIP" } ] },
                { "id": 3, "parent_id": 2, "role": "USER", "status": "FAILED",
                  "inserted_at": "2026-11-02T09:00:20.000Z",
                  "fragments": [ { "type": "REQUEST", "content": "IMPORTED-FAILED" } ] },
                { "id": 4, "parent_id": 3, "role": "ASSISTANT", "status": "CONTENT_FILTER",
                  "inserted_at": "2026-11-02T09:00:30.000Z",
                  "fragments": [ { "type": "RESPONSE", "content": "IMPORTED-FILTERED" } ] }
              ]
            }
        """.trimIndent()

        val parsed = parse(json, "sess-status")

        assertEquals(listOf("IMPORTED-INCOMPLETE", "IMPORTED-WIP"), parsed.turns.map { it.text })
        assertEquals(
            listOf(
                DeepSeekIssueCode.STATUS_UNSUPPORTED to 2,
                DeepSeekIssueCode.STATUS_UNSUPPORTED to 3,
            ),
            parsed.issues.map { it.code to it.messageIndex },
        )
    }

    // ---- 时间 ----

    @Test
    fun `非单调时间戳按 inserted_at id 排序后钳制为严格递增`() {
        val json = """
            {
              "id": "sess-clamp",
              "current_message_id": 3,
              "inserted_at": "2026-11-02T09:00:00.000Z",
              "messages": [
                { "id": 1, "parent_id": null, "role": "USER", "status": "FINISHED",
                  "inserted_at": "2026-11-02T09:00:00.000Z",
                  "fragments": [ { "type": "REQUEST", "content": "IMPORTED-T1" } ] },
                { "id": 2, "parent_id": 1, "role": "ASSISTANT", "status": "FINISHED",
                  "inserted_at": "2026-11-02T09:00:00.000Z",
                  "fragments": [ { "type": "RESPONSE", "content": "IMPORTED-T2" } ] },
                { "id": 3, "parent_id": 2, "role": "USER", "status": "FINISHED",
                  "inserted_at": "2026-11-02T08:59:00.000Z",
                  "fragments": [ { "type": "REQUEST", "content": "IMPORTED-T3" } ] }
              ]
            }
        """.trimIndent()

        val parsed = parse(json, "sess-clamp")

        // (inserted_at, id) 升序：id3(08:59) → id1(09:00) → id2(09:00，撞车被 +1s)
        assertEquals(listOf("IMPORTED-T3", "IMPORTED-T1", "IMPORTED-T2"), parsed.turns.map { it.text })
        val base = ts("2026-11-02T09:00:00.000Z")
        assertEquals(listOf(base - 60_000, base, base + 1_000), parsed.turns.map { it.at })
        assertTrue(parsed.turns.zipWithNext().all { (a, b) -> a.at < b.at })
    }

    @Test
    fun `全部时间在未来时整体平移回 now 之前且保持递增`() {
        val json = """
            {
              "id": "sess-future",
              "current_message_id": 3,
              "inserted_at": "2099-01-01T00:00:00.000Z",
              "messages": [
                { "id": 1, "parent_id": null, "role": "USER", "status": "FINISHED",
                  "inserted_at": "2099-01-01T00:00:00.000Z",
                  "fragments": [ { "type": "REQUEST", "content": "IMPORTED-F1" } ] },
                { "id": 2, "parent_id": 1, "role": "ASSISTANT", "status": "FINISHED",
                  "inserted_at": "2099-01-01T00:00:10.000Z",
                  "fragments": [ { "type": "RESPONSE", "content": "IMPORTED-F2" } ] },
                { "id": 3, "parent_id": 2, "role": "USER", "status": "FINISHED",
                  "inserted_at": "2099-01-01T00:00:20.000Z",
                  "fragments": [ { "type": "REQUEST", "content": "IMPORTED-F3" } ] }
              ]
            }
        """.trimIndent()

        val parsed = parse(json, "sess-future")

        assertEquals(3, parsed.turns.size)
        assertTrue(parsed.turns.all { it.at < now })
        assertEquals(now - 1, parsed.turns.last().at)
        assertTrue(parsed.turns.zipWithNext().all { (a, b) -> a.at < b.at })
        assertEquals(10_000L, parsed.turns[1].at - parsed.turns[0].at)
        assertEquals(10_000L, parsed.turns[2].at - parsed.turns[1].at)
    }

    @Test
    fun `会话坏时间戳抛 BAD_TIMESTAMP`() {
        val json = """
            {
              "id": "sess-badtime",
              "inserted_at": "2026-13-01T00:00:00.000Z",
              "messages": [
                { "id": 1, "parent_id": null, "role": "USER", "status": "FINISHED",
                  "inserted_at": "2026-11-02T09:00:00.000Z",
                  "fragments": [ { "type": "REQUEST", "content": "IMPORTED-OK" } ] }
              ]
            }
        """.trimIndent()

        assertEquals(DeepSeekParseException.BAD_TIMESTAMP, codeOf { parse(json, "sess-badtime") })
    }

    @Test
    fun `消息坏时间戳整段会话抛 BAD_TIMESTAMP`() {
        val json = """
            {
              "id": "sess-badmsgtime",
              "inserted_at": "2026-11-02T09:00:00.000Z",
              "messages": [
                { "id": 1, "parent_id": null, "role": "USER", "status": "FINISHED",
                  "inserted_at": "2026-11-02T09:00:00.000Z",
                  "fragments": [ { "type": "REQUEST", "content": "IMPORTED-OK" } ] },
                { "id": 2, "parent_id": 1, "role": "ASSISTANT", "status": "FINISHED",
                  "inserted_at": "昨天下午",
                  "fragments": [ { "type": "RESPONSE", "content": "IMPORTED-BAD" } ] }
              ]
            }
        """.trimIndent()

        assertEquals(DeepSeekParseException.BAD_TIMESTAMP, codeOf { parse(json, "sess-badmsgtime") })
    }

    @Test
    fun `会话 updated_at 缺失用 fallback 兜底`() {
        val json = """
            {
              "id": "sess-fb",
              "inserted_at": "2026-11-02T09:00:00.000Z",
              "messages": [
                { "id": 1, "parent_id": null, "role": "USER", "status": "FINISHED",
                  "inserted_at": "2026-11-02T09:00:00.000Z",
                  "fragments": [ { "type": "REQUEST", "content": "IMPORTED-FALLBACK" } ] }
              ]
            }
        """.trimIndent()
        val fallback = ts("2026-11-19T00:00:00.000Z")

        val withFallback = parse(json, "sess-fb", fallbackUpdatedAt = fallback)
        assertEquals(ts("2026-11-02T09:00:00.000Z"), withFallback.insertedAt)
        assertEquals(fallback, withFallback.updatedAt)

        val withoutFallback = parse(json, "sess-fb")
        assertEquals(withoutFallback.insertedAt, withoutFallback.updatedAt)
    }

    // ---- 错误码 / 容错 ----

    @Test
    fun `session id 与文件名不符抛 SESSION_ID_MISMATCH`() {
        val json = """
            {
              "id": "sess-real",
              "inserted_at": "2026-11-02T09:00:00.000Z",
              "messages": [
                { "id": 1, "parent_id": null, "role": "USER", "status": "FINISHED",
                  "inserted_at": "2026-11-02T09:00:00.000Z",
                  "fragments": [ { "type": "REQUEST", "content": "IMPORTED-X" } ] }
              ]
            }
        """.trimIndent()

        assertEquals(
            DeepSeekParseException.SESSION_ID_MISMATCH,
            codeOf { parse(json, "sess-other") },
        )
        assertEquals(
            DeepSeekParseException.SESSION_ID_MISMATCH,
            codeOf { parse("""{"inserted_at":"2026-11-02T09:00:00.000Z","messages":[]}""", "sess-real") },
        )
    }

    @Test
    fun `parent 链断裂退化为按 id 升序并记 issue`() {
        val json = """
            {
              "id": "sess-broken",
              "current_message_id": 3,
              "inserted_at": "2026-11-02T09:00:00.000Z",
              "messages": [
                { "id": 1, "parent_id": null, "role": "USER", "status": "FINISHED",
                  "inserted_at": "2026-11-02T09:00:00.000Z",
                  "fragments": [ { "type": "REQUEST", "content": "IMPORTED-B1" } ] },
                { "id": 2, "parent_id": 1, "role": "ASSISTANT", "status": "FINISHED",
                  "inserted_at": "2026-11-02T09:00:10.000Z",
                  "fragments": [ { "type": "RESPONSE", "content": "IMPORTED-B2" } ] },
                { "id": 3, "parent_id": 99, "role": "USER", "status": "FINISHED",
                  "inserted_at": "2026-11-02T09:00:20.000Z",
                  "fragments": [ { "type": "REQUEST", "content": "IMPORTED-B3" } ] }
              ]
            }
        """.trimIndent()

        val parsed = parse(json, "sess-broken")

        assertEquals(listOf("IMPORTED-B1", "IMPORTED-B2", "IMPORTED-B3"), parsed.turns.map { it.text })
        assertTrue(
            parsed.issues.any {
                it.code == DeepSeekIssueCode.BRANCH_BROKEN && it.messageIndex == 2
            },
        )
    }

    @Test
    fun `根不是 USER 记 issue 但保留`() {
        val json = """
            {
              "id": "sess-root",
              "current_message_id": 2,
              "inserted_at": "2026-11-02T09:00:00.000Z",
              "messages": [
                { "id": 1, "parent_id": null, "role": "ASSISTANT", "status": "FINISHED",
                  "inserted_at": "2026-11-02T09:00:00.000Z",
                  "fragments": [ { "type": "RESPONSE", "content": "IMPORTED-ROOT-ASSISTANT" } ] },
                { "id": 2, "parent_id": 1, "role": "USER", "status": "FINISHED",
                  "inserted_at": "2026-11-02T09:00:10.000Z",
                  "fragments": [ { "type": "REQUEST", "content": "IMPORTED-AFTER" } ] }
              ]
            }
        """.trimIndent()

        val parsed = parse(json, "sess-root")

        assertEquals(2, parsed.turns.size)
        assertEquals("IMPORTED-ROOT-ASSISTANT", parsed.turns[0].text)
        assertTrue(
            parsed.issues.any {
                it.code == DeepSeekIssueCode.ROOT_NOT_USER && it.messageIndex == 0
            },
        )
    }

    @Test
    fun `0 条可导入消息抛 EMPTY_SESSION`() {
        val noMessages = """
            {"id":"sess-empty","inserted_at":"2026-11-02T09:00:00.000Z","messages":[]}
        """.trimIndent()
        assertEquals(DeepSeekParseException.EMPTY_SESSION, codeOf { parse(noMessages, "sess-empty") })

        val thinkOnly = """
            {
              "id": "sess-think-only",
              "inserted_at": "2026-11-02T09:00:00.000Z",
              "messages": [
                { "id": 1, "parent_id": null, "role": "ASSISTANT", "status": "FINISHED",
                  "inserted_at": "2026-11-02T09:00:00.000Z",
                  "fragments": [ { "type": "THINK", "content": "IMPORTED-THINK-ONLY" } ] }
              ]
            }
        """.trimIndent()
        assertEquals(
            DeepSeekParseException.EMPTY_SESSION,
            codeOf { parse(thinkOnly, "sess-think-only") },
        )
    }

    @Test
    fun `同一消息多个文本 fragment 用换行连接`() {
        val json = """
            {
              "id": "sess-join",
              "current_message_id": 2,
              "inserted_at": "2026-11-02T09:00:00.000Z",
              "messages": [
                { "id": 1, "parent_id": null, "role": "USER", "status": "FINISHED",
                  "inserted_at": "2026-11-02T09:00:00.000Z",
                  "fragments": [ { "type": "REQUEST", "content": "IMPORTED-Q" } ] },
                { "id": 2, "parent_id": 1, "role": "ASSISTANT", "status": "FINISHED",
                  "inserted_at": "2026-11-02T09:00:10.000Z",
                  "fragments": [
                    { "type": "RESPONSE", "content": "IMPORTED-RESP-A" },
                    { "type": "RESPONSE", "content": "IMPORTED-RESP-B" }
                  ] }
              ]
            }
        """.trimIndent()

        val parsed = parse(json, "sess-join")

        assertEquals("IMPORTED-RESP-A\nIMPORTED-RESP-B", parsed.turns[1].text)
    }

    // ---- manifest ----

    @Test
    fun `manifest 接受数字 3 与字符串 3`() {
        assertEquals(
            3,
            DeepSeekParser.parseManifest("""{"export_format_version":3}""").exportFormatVersion,
        )
        assertEquals(
            3,
            DeepSeekParser.parseManifest("""{"export_format_version":"3"}""").exportFormatVersion,
        )
    }

    @Test
    fun `manifest 拒绝版本 2 缺失与非 JSON`() {
        assertEquals(
            DeepSeekParseException.VERSION_UNSUPPORTED,
            codeOf { DeepSeekParser.parseManifest("""{"export_format_version":2}""") },
        )
        assertEquals(
            DeepSeekParseException.VERSION_UNSUPPORTED,
            codeOf { DeepSeekParser.parseManifest("""{"export_format_version":"2"}""") },
        )
        assertEquals(
            DeepSeekParseException.VERSION_UNSUPPORTED,
            codeOf { DeepSeekParser.parseManifest("""{}""") },
        )
        assertEquals(
            DeepSeekParseException.MALFORMED_JSON,
            codeOf { DeepSeekParser.parseManifest("not a json") },
        )
    }

    // ---- index ----

    @Test
    fun `index 坏行跳过且坏时间字段为 null`() {
        val json = """
            [
              { "id": "sess-a", "inserted_at": "2026-11-02T09:00:00.000Z",
                "updated_at": "2026-11-03T09:00:00.000Z" },
              42,
              { "title": "没有 id 的坏行" },
              { "id": "sess-b", "inserted_at": "坏时间", "updated_at": null },
              null
            ]
        """.trimIndent()

        val rows = DeepSeekParser.parseIndex(json)

        assertEquals(2, rows.size)
        assertEquals("sess-a", rows[0].id)
        assertEquals(ts("2026-11-02T09:00:00.000Z"), rows[0].insertedAt)
        assertEquals(ts("2026-11-03T09:00:00.000Z"), rows[0].updatedAt)
        assertEquals("sess-b", rows[1].id)
        assertNull(rows[1].insertedAt)
        assertNull(rows[1].updatedAt)
    }

    @Test
    fun `index 顶层不是数组抛 MALFORMED_JSON`() {
        assertEquals(
            DeepSeekParseException.MALFORMED_JSON,
            codeOf { DeepSeekParser.parseIndex("""{"id":"sess-a"}""") },
        )
    }
}
