package cc.pulsez.app.ui.importer

import cc.pulsez.app.importer.deepseek.DeepSeekParser
import cc.pulsez.app.importer.deepseek.DeepSeekSessionPreview
import java.time.Instant
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ImportPreviewRulesTest {
    private fun at(iso: String): Long = Instant.parse(iso).toEpochMilli()

    private fun preview(
        id: String,
        lastMessageAt: Long?,
        damageCode: String? = null,
        looksLikeToolNoise: Boolean = false,
    ) = DeepSeekSessionPreview(
        entryName = "chat_sessions/$id.json",
        sourceSessionId = id,
        title = id,
        sourceStartedAt = null,
        messageCount = if (damageCode == null) 1 else 0,
        damageCode = damageCode,
        lastMessageAt = lastMessageAt,
        looksLikeToolNoise = looksLikeToolNoise,
    )

    @Test
    fun `older than a year uses local calendar time and a strict boundary`() {
        val zone = ZoneId.of("Asia/Shanghai")
        val now = Instant.parse("2025-02-28T04:15:00Z") // 12:15 local time
        val cutoff = Instant.parse("2024-02-28T04:15:00Z")

        assertEquals(cutoff, oneYearBeforeLocalTime(now, zone))
        assertTrue(isOlderThanOneYear(cutoff.minusMillis(1).toEpochMilli(), now, zone))
        assertFalse("刚好到边界不算超过一年", isOlderThanOneYear(cutoff.toEpochMilli(), now, zone))
        assertFalse(isOlderThanOneYear(cutoff.plusMillis(1).toEpochMilli(), now, zone))
        assertFalse(isOlderThanOneYear(null, now, zone))
    }

    @Test
    fun `calendar subtraction resolves leap day to previous valid date`() {
        val now = Instant.parse("2024-02-29T12:00:00Z")
        assertEquals(
            Instant.parse("2023-02-28T12:00:00Z"),
            oneYearBeforeLocalTime(now, ZoneId.of("UTC")),
        )
    }

    @Test
    fun `default keeps all previews and current order while filters and date sorts are explicit`() {
        val now = Instant.parse("2026-11-23T00:00:00Z")
        val zone = ZoneId.of("UTC")
        val original = listOf(
            preview("unknown-first", null),
            preview("recent", at("2026-11-22T00:00:00Z")),
            preview("old", at("2024-01-01T00:00:00Z")),
            preview("damaged", null, damageCode = "BAD_TIMESTAMP"),
        )

        assertEquals(ImportPreviewDateFilter.ALL, DEFAULT_IMPORT_PREVIEW_DATE_FILTER)
        assertEquals(ImportPreviewDateSort.SOURCE_ORDER, DEFAULT_IMPORT_PREVIEW_DATE_SORT)
        assertEquals(
            listOf("unknown-first", "recent", "old", "damaged"),
            filterAndSortImportPreviews(original, now, zone).map { it.sourceSessionId },
        )
        assertEquals(
            listOf("old"),
            filterAndSortImportPreviews(
                original,
                now,
                zone,
                filter = ImportPreviewDateFilter.OLDER_THAN_ONE_YEAR,
            ).map { it.sourceSessionId },
        )
        assertEquals(
            listOf("unknown-first", "damaged"),
            filterAndSortImportPreviews(
                original,
                now,
                zone,
                filter = ImportPreviewDateFilter.UNKNOWN,
            ).map { it.sourceSessionId },
        )
        assertEquals(
            listOf("recent", "old", "unknown-first", "damaged"),
            filterAndSortImportPreviews(
                original,
                now,
                zone,
                sort = ImportPreviewDateSort.LAST_MESSAGE_NEWEST_FIRST,
            ).map { it.sourceSessionId },
        )
        assertEquals(
            listOf("old", "recent", "unknown-first", "damaged"),
            filterAndSortImportPreviews(
                original,
                now,
                zone,
                sort = ImportPreviewDateSort.LAST_MESSAGE_OLDEST_FIRST,
            ).map { it.sourceSessionId },
        )
        assertEquals("日期未知", formatImportLastMessageDate(null, zone))
        assertEquals(
            "2026年11月22日",
            formatImportLastMessageDate(at("2026-11-22T00:00:00Z"), zone),
        )
    }

    @Test
    fun `bulk assignment changes selected source ids and preserves other groups`() {
        val existing = linkedMapOf(
            "already-in-group-a" to "group-a",
            "selected-and-skipped" to null,
            "already-in-group-b" to "group-b",
        )

        val assigned = assignmentsAfterBatchChange(
            current = existing,
            selectedSourceIds = setOf("selected-and-skipped"),
            groupId = "group-a",
        )
        assertEquals("group-a", assigned["selected-and-skipped"])
        assertEquals("group-a", assigned["already-in-group-a"])
        assertEquals("group-b", assigned["already-in-group-b"])
        assertEquals("selected-and-skipped" to null, existing.entries.single { it.key == "selected-and-skipped" }.toPair())

        val skipSelected = assignmentsAfterBatchChange(
            current = assigned,
            selectedSourceIds = setOf("selected-and-skipped"),
            groupId = null,
        )
        assertTrue(skipSelected.containsKey("selected-and-skipped"))
        assertEquals(null, skipSelected["selected-and-skipped"])
        assertEquals("group-a", skipSelected["already-in-group-a"])
        assertEquals("group-b", skipSelected["already-in-group-b"])
    }

    @Test
    fun `bulk selection excludes damaged previews but keeps undated readable previews`() {
        val previews = listOf(
            preview("readable-unknown", null),
            preview("damaged-unknown", null, damageCode = "BAD_TIMESTAMP"),
            preview("ambiguous-duplicate", at("2026-01-01T00:00:00Z")),
            preview("ambiguous-duplicate", null, damageCode = "BAD_TIMESTAMP"),
            preview("readable-duplicate", at("2026-01-02T00:00:00Z")),
            preview("readable-duplicate", at("2026-01-03T00:00:00Z")),
        )

        assertEquals(setOf("readable-unknown"), assignablePreviewIds(previews))
    }

    @Test
    fun `seven day boundary keeps exact cutoff active and puts earlier valid chats in older bucket`() {
        val zone = ZoneId.of("Asia/Shanghai")
        val now = Instant.parse("2026-11-24T04:00:00Z")
        val cutoff = Instant.parse("2026-11-17T04:00:00Z")

        assertEquals(cutoff, sevenDaysBeforeLocalTime(now, zone))
        assertTrue(isWithinLastSevenDays(cutoff.toEpochMilli(), now, zone))
        assertFalse(isWithinLastSevenDays(cutoff.minusMillis(1).toEpochMilli(), now, zone))

        val buckets = bucketImportPreviews(
            previews = listOf(
                preview("active-boundary", cutoff.toEpochMilli()),
                preview("active-noise", cutoff.plusMillis(1000).toEpochMilli(), looksLikeToolNoise = true),
                preview("older-1", cutoff.minusMillis(1).toEpochMilli()),
                preview("unknown-1", null),
                preview("damaged-1", cutoff.toEpochMilli(), damageCode = "BAD_TIMESTAMP"),
            ),
            now = now,
            zone = zone,
        )

        assertFalse(buckets.usedRecentFallback)
        assertEquals(listOf("active-boundary"), buckets.expandedActive.map { it.sourceSessionId })
        assertEquals(listOf("older-1"), buckets.olderThanSevenDays.map { it.sourceSessionId })
        assertEquals(listOf("active-noise"), buckets.toolNoise.map { it.sourceSessionId })
        assertEquals(listOf("unknown-1", "damaged-1"), buckets.unknownOrDamaged.map { it.sourceSessionId })
    }

    @Test
    fun `zero active chats within seven days promotes top five most recent valid chats`() {
        val zone = ZoneId.of("UTC")
        val now = Instant.parse("2026-11-24T00:00:00Z")
        val previews = (1..7).map { day ->
            preview("old-$day", at("2026-10-0${day}T12:00:00Z"))
        } + preview("noise-recent", at("2026-11-23T12:00:00Z"), looksLikeToolNoise = true)

        val buckets = bucketImportPreviews(previews, now, zone)

        assertTrue(buckets.usedRecentFallback)
        assertEquals(
            listOf("old-7", "old-6", "old-5", "old-4", "old-3"),
            buckets.expandedActive.map { it.sourceSessionId },
        )
        assertEquals(listOf("old-1", "old-2"), buckets.olderThanSevenDays.map { it.sourceSessionId })
        assertEquals(listOf("noise-recent"), buckets.toolNoise.map { it.sourceSessionId })
    }

    @Test
    fun `preview extracts bounded snippets and tool noise flags without leaking body in toString`() {
        val longUser = "用户问".repeat(120) // 360 chars
        val longA1 = "第一句答复".repeat(50) // 250 chars
        val longA2 = "最后一句答复".repeat(50) // 300 chars
        val normalJson = """
            {
              "id": "normal-sess",
              "title": "日常闲聊",
              "current_message_id": 4,
              "inserted_at": "2026-11-20T10:00:00Z",
              "messages": [
                {"id": 1, "parent_id": null, "role": "USER", "status": "FINISHED", "inserted_at": "2026-11-20T10:00:00Z", "fragments": [{"type": "REQUEST", "content": "$longUser"}]},
                {"id": 2, "parent_id": 1, "role": "ASSISTANT", "status": "FINISHED", "inserted_at": "2026-11-20T10:01:00Z", "fragments": [{"type": "RESPONSE", "content": "$longA1"}]},
                {"id": 3, "parent_id": 2, "role": "USER", "status": "FINISHED", "inserted_at": "2026-11-20T10:02:00Z", "fragments": [{"type": "REQUEST", "content": "继续说说"}]},
                {"id": 4, "parent_id": 3, "role": "ASSISTANT", "status": "FINISHED", "inserted_at": "2026-11-20T10:03:00Z", "fragments": [{"type": "RESPONSE", "content": "$longA2"}]}
              ]
            }
        """.trimIndent()

        val normal = DeepSeekParser.previewSession(normalJson, "normal-sess")
        assertFalse(normal.looksLikeToolNoise)
        assertEquals(280, normal.firstUserSnippet.length)
        assertEquals(160, normal.firstAssistantSnippet.length)
        assertEquals(160, normal.lastAssistantSnippet.length)
        assertTrue(normal.lastAssistantSnippet.startsWith("最后一句答复"))
        assertFalse(normal.toString().contains("用户问"))
        assertFalse(normal.toString().contains("最后一句答复"))

        val shortJson = """
            {
              "id": "short-noise",
              "current_message_id": 2,
              "inserted_at": "2026-11-20T10:00:00Z",
              "messages": [
                {"id": 1, "parent_id": null, "role": "USER", "status": "FINISHED", "inserted_at": "2026-11-20T10:00:00Z", "fragments": [{"type": "REQUEST", "content": "查个词"}]},
                {"id": 2, "parent_id": 1, "role": "ASSISTANT", "status": "FINISHED", "inserted_at": "2026-11-20T10:01:00Z", "fragments": [{"type": "RESPONSE", "content": "好的。"}]}
              ]
            }
        """.trimIndent()
        assertTrue(DeepSeekParser.previewSession(shortJson, "short-noise").looksLikeToolNoise)

        val codeBlock = "```kotlin\n" + "val x = 1234567890\n".repeat(15) + "```"
        val codeJson = """
            {
              "id": "code-noise",
              "current_message_id": 3,
              "inserted_at": "2026-11-20T10:00:00Z",
              "messages": [
                {"id": 1, "parent_id": null, "role": "USER", "status": "FINISHED", "inserted_at": "2026-11-20T10:00:00Z", "fragments": [{"type": "REQUEST", "content": "帮我写段代码用于校验参数格式是否正确，长度要超过一百字才不会被当成极短问答，这样才能单独测代码块占比规则。"}]},
                {"id": 2, "parent_id": 1, "role": "ASSISTANT", "status": "FINISHED", "inserted_at": "2026-11-20T10:01:00Z", "fragments": [{"type": "RESPONSE", "content": ${kotlinx.serialization.json.JsonPrimitive(codeBlock)}}]},
                {"id": 3, "parent_id": 2, "role": "USER", "status": "FINISHED", "inserted_at": "2026-11-20T10:02:00Z", "fragments": [{"type": "REQUEST", "content": "谢谢"}]}
              ]
            }
        """.trimIndent()
        assertTrue(DeepSeekParser.previewSession(codeJson, "code-noise").looksLikeToolNoise)
    }

    @Test
    fun `wp2 copy avoids forbidden tech words amounts single char ellipsis and gendered pronouns`() {
        val texts = listOf(
            ImportAutoClusterCopy.BUTTON,
            ImportAutoClusterCopy.RUNNING,
            ImportAutoClusterCopy.HINT,
            ImportAutoClusterCopy.NEED_AI_HINT,
            ImportAwakenCopy.TITLE,
            ImportAwakenCopy.HINT,
        )
        val forbidden = listOf(
            "token", "上下文", "窗口", "向量", "LLM", "API", "Embedding", "JSON", "分片",
            "她", "他", "¥", "几毛", "几分", "块钱",
        )
        texts.forEach { text ->
            forbidden.forEach { word ->
                assertFalse("文案不应包含「$word」：$text", text.contains(word, ignoreCase = true))
            }
            assertFalse(
                "文案不应包含单字省略号：$text",
                text.replace("……", "").contains("…"),
            )
        }
        assertTrue(ImportAutoClusterCopy.HINT.contains("花费不多，具体以服务商账单为准"))
        assertTrue(ImportAwakenCopy.HINT.contains("花费不多，具体以服务商账单为准"))
        assertTrue(ImportAutoClusterCopy.RUNNING.contains("……"))
    }
}
