package cc.pulsez.app.importer

import cc.pulsez.app.data.ExportService
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `BackupParser.parseBackup` 的字段级容错纯 JVM 测试。
 *
 * 检查空串、JSON `null` 和缺失字段是否归一为 `null`；`memory.validTo` 的 0 哨兵是否只在
 * 解析时转为 `null`；数字或字符串类型错误是否回落默认值，而 JSON 语法错误或根值非对象是否抛出。
 */
class BackupParserDocumentTest {

    private fun parse(text: String): BackupDocument = BackupParser.parseBackup(text)

    /** 最小合法备份骨架（schema=3，形状与导出结构一致）。 */
    private fun backup(
        character: String = """{"id":"c1","name":"阿淮","persona":"人设","greeting":"","userName":"","styleSamples":null,"sourceType":"MANUAL","createdAt":1,"updatedAt":2}""",
        thread: String = """{"id":"t1","characterId":"c1","kind":"SINGLE","title":"","createdAt":1,"lastMessageAt":2,"messages":[],"segments":[],"memories":[],"members":[]}""",
        attachments: String = "[]",
        exportedAt: String = "123",
        skippedOrphanThreads: String = "0",
        importBatches: String? = null,
    ): String = """{"schema":3,"app":"pulsez","exportedAt":$exportedAt,"character":[$character],""" +
        """"thread":[$thread],"attachments":$attachments,"skippedOrphanThreads":$skippedOrphanThreads""" +
        (importBatches?.let { ",\"importBatches\":$it" } ?: "") + "}"

    // ---------------------------------------------------------------- 空串 / null / 缺字段

    @Test
    fun `空串与 null 归一成 null`() {
        val document = parse(
            backup(
                character = """{"id":"c1","name":"阿淮","persona":"人设","greeting":"","userName":null,"styleSamples":"","sourceType":"","createdAt":1,"updatedAt":2}""",
                thread = """{"id":"t1","characterId":"c1","kind":"SINGLE","title":"","createdAt":1,"lastMessageAt":2,""" +
                    """"messages":[{"id":"m1","role":"user","content":"在吗","createdAt":1,"segmentId":""}],""" +
                    """"segments":[{"id":"s1","ordinal":0,"summary":"","batonReason":""}],""" +
                    """"memories":[{"kind":"FACT","key":"","value":"v","status":"ACTIVE","validFrom":1,"validTo":0,"characterId":"","happenedAt":null,"dueAt":null,"firstSeenAt":null,"lastSeenAt":null}]}""",
            ),
        )
        val character = document.characters.single()
        assertNull(character.greeting)
        assertNull(character.userName)
        assertNull(character.styleSamples)
        assertEquals("", character.sourceType)
        val thread = document.threads.single()
        assertNull(thread.title)
        assertNull(thread.messages.single().segmentSourceId)
        assertNull(thread.messages.single().speakerSourceId)
        assertNull(thread.segments.single().summary)
        assertNull(thread.segments.single().batonReason)
        val memory = thread.memories.single()
        assertNull(memory.key)
        assertNull(memory.characterSourceId)
        assertNull(memory.validTo)
        assertNull(memory.happenedAt)
        assertNull(memory.dueAt)
        assertNull(memory.firstSeenAt)
        assertNull(memory.lastSeenAt)
    }

    @Test
    fun `群聊 characterId 为 JSON null 时是 null`() {
        val document = parse(
            backup(
                thread = """{"id":"t1","characterId":null,"kind":"GROUP","title":null,"createdAt":1,"lastMessageAt":2,""" +
                    """"messages":[],"segments":[],"memories":[],"members":[{"characterId":"c1","ordinal":0,"joinedAt":1}]}""",
            ),
        )
        assertNull(document.threads.single().characterSourceId)
        assertEquals("GROUP", document.threads.single().kind)
    }

    // ---------------------------------------------------------------- 哨兵 / 数字

    @Test
    fun `memory validTo 的 0 哨兵转 null 正数保留`() {
        val thread = parse(
            backup(
                thread = """{"id":"t1","characterId":"c1","kind":"SINGLE","createdAt":1,"lastMessageAt":2,"messages":[],"segments":[],""" +
                    """"memories":[{"kind":"FACT","value":"v","validFrom":1,"validTo":0},{"kind":"EVENT","value":"v2","validFrom":1,"validTo":123}],""" +
                    """"members":[]}""",
            ),
        ).threads.single()
        assertNull(thread.memories[0].validTo)
        assertEquals(123L, thread.memories[1].validTo)
    }

    @Test
    fun `tokens 缺失为 null 有值为原值`() {
        val thread = parse(
            backup(
                thread = """{"id":"t1","characterId":"c1","kind":"SINGLE","createdAt":1,"lastMessageAt":2,""" +
                    """"messages":[{"id":"m1","role":"user","content":"a","createdAt":1,"segmentId":"s1"},{"id":"m2","role":"assistant","content":"b","createdAt":2,"segmentId":"s1","tokens":17}],""" +
                    """"segments":[],"memories":[],"members":[]}""",
            ),
        ).threads.single()
        assertNull(thread.messages[0].tokens)
        assertEquals(17, thread.messages[1].tokens)
    }

    @Test
    fun `数字字段类型错回落默认不抛`() {
        val document = parse(
            backup(
                exportedAt = """"不是数字"""",
                character = """{"id":"c1","name":"n","persona":"p","sourceType":"S","createdAt":"123","updatedAt":{"a":1}}""",
                thread = """{"id":"t1","characterId":"c1","kind":"SINGLE","createdAt":[1],"lastMessageAt":1.5,"messages":[{"id":"m1","role":"user","content":"a","createdAt":"x","segmentId":"s1","tokens":{"x":1}}],"segments":[{"id":"s1","ordinal":"first"}],"memories":[{"kind":"FACT","value":"v","validFrom":false,"confidence":"0.9","importance":[]}],"members":[]}""",
                skippedOrphanThreads = """"缺失"""",
            ),
        )
        assertNull(document.exportedAt)
        assertEquals(0, document.skippedOrphanThreads)
        assertEquals(0L, document.characters.single().createdAt)
        assertEquals(0L, document.characters.single().updatedAt)
        val thread = document.threads.single()
        assertEquals(0L, thread.createdAt)
        assertEquals(0L, thread.lastMessageAt)
        assertEquals(0L, thread.messages.single().createdAt)
        assertNull(thread.messages.single().tokens)
        // ordinal 缺/非数字 → Int.MAX_VALUE（排在有 ordinal 的后面）。
        assertEquals(Int.MAX_VALUE, thread.segments.single().ordinal)
        val memory = thread.memories.single()
        assertEquals(0L, memory.validFrom)
        assertNull(memory.confidence)
        assertNull(memory.importance)
    }

    @Test
    fun `kind 缺失回 SINGLE GROUP 保留`() {
        val document = parse(
            backup(
                thread = """{"id":"t1","characterId":"c1","createdAt":1,"lastMessageAt":2,"messages":[],"segments":[],"memories":[],"members":[]}""",
            ),
        )
        assertEquals("SINGLE", document.threads.single().kind)
        assertEquals(
            "GROUP",
            parse(
                backup(
                    thread = """{"id":"t1","characterId":null,"kind":"GROUP","createdAt":1,"lastMessageAt":2,"messages":[],"segments":[],"memories":[],"members":[]}""",
                ),
            ).threads.single().kind,
        )
        // 非 SINGLE/GROUP → SINGLE。
        assertEquals(
            "SINGLE",
            parse(
                backup(
                    thread = """{"id":"t1","characterId":"c1","kind":"WEIRD","createdAt":1,"lastMessageAt":2,"messages":[],"segments":[],"memories":[],"members":[]}""",
                ),
            ).threads.single().kind,
        )
    }

    @Test
    fun `旧备份活动时间缺失保持 null 新备份只读显式数字`() {
        val oldThread = parse(
            backup(
                thread = """{"id":"t1","characterId":"c1","kind":"SINGLE","createdAt":1,"lastMessageAt":999,"messages":[{"id":"m1","role":"user","content":"history","createdAt":999,"segmentId":"s1"}],"segments":[],"memories":[],"members":[]}""",
            ),
        ).threads.single()
        assertNull("旧备份的 lastMessageAt/消息时间不能推断真实活动", oldThread.lastSingleChatActivityAt)

        val modernThread = parse(
            backup(
                thread = """{"id":"t1","characterId":"c1","kind":"SINGLE","createdAt":1,"lastMessageAt":999,"lastSingleChatActivityAt":1234,"messages":[],"segments":[],"memories":[],"members":[]}""",
            ),
        ).threads.single()
        assertEquals(1234L, modernThread.lastSingleChatActivityAt)
    }

    @Test
    fun `speakerCharacterId 非字符串回 null`() {
        val thread = parse(
            backup(
                thread = """{"id":"t1","characterId":"c1","kind":"SINGLE","createdAt":1,"lastMessageAt":2,""" +
                    """"messages":[{"id":"m1","role":"assistant","content":"a","createdAt":1,"segmentId":"s1","speakerCharacterId":0},{"id":"m2","role":"assistant","content":"b","createdAt":2,"segmentId":"s1","speakerCharacterId":false},{"id":"m3","role":"assistant","content":"c","createdAt":3,"segmentId":"s1","speakerCharacterId":"c1"}],""" +
                    """"segments":[],"memories":[],"members":[]}""",
            ),
        ).threads.single()
        assertNull(thread.messages[0].speakerSourceId)
        assertNull(thread.messages[1].speakerSourceId)
        assertEquals("c1", thread.messages[2].speakerSourceId)
    }

    // ---------------------------------------------------------------- 异常与统计

    @Test
    fun `JSON 语法坏抛 SerializationException`() {
        // JSON 解析器自己抛的类型（kotlinx.serialization 的语法错误），调用方按 Throwable 兜底。
        assertThrows(kotlinx.serialization.SerializationException::class.java) {
            BackupParser.parseBackup("{\"schema\":3,")
        }
    }

    @Test
    fun `根不是对象抛 IllegalArgumentException 且信息说明根类型`() {
        // 语法合法但根不是对象：parseBackup 必须先判根、给出可诊断的异常信息。
        listOf("[]", "\"字符串\"", "42").forEach { text ->
            val failure = assertThrows(IllegalArgumentException::class.java) {
                BackupParser.parseBackup(text)
            }
            assertTrue(
                "异常信息应说明「根不是 JSON 对象」，实际=${failure.message}",
                failure.message.orEmpty().contains("根不是 JSON 对象"),
            )
        }
    }

    @Test
    fun `附件条数与顶层跳过数原样读出`() {
        val document = parse(backup(attachments = """[{"messageId":"m1"},{"messageId":"m2"}]""", skippedOrphanThreads = "3"))
        assertEquals(2, document.attachmentCount)
        assertEquals(3, document.skippedOrphanThreads)
        assertEquals(3, document.schema)
    }

    @Test
    fun `导入批次元数据可选且字段保留`() {
        val batch = """[{"id":"b1","fingerprint":"archive.zip:123","zipName":"archive.zip","createdAt":9,"status":"CANCELLED","postProcessStatus":"PENDING","groups":[{"id":"g1","targetCharacterId":"c1","targetThreadId":"t1","isNewCharacter":false,"name":"阿淮","persona":null,"status":"SUCCESS","memoryUndoSafe":true,"beforeLastMessageAt":2,"beforeLastExtractAt":1,"beforeCharacterUpdatedAt":3,"importLastMessageAt":5,"importMessageCount":2,"errorCode":null}],"windows":[{"sourceSessionId":"s1","entryName":"chat_sessions/s1.json","title":"窗口一","sourceStartedAt":4,"messageCount":2,"damageCode":null,"groupId":"g1","status":"IMPORTED","errorCode":null}],"sources":[{"id":"src1","targetCharacterId":"c1","targetThreadId":"t1","sourceSessionId":"s1","title":"窗口一","sourceStartedAt":4,"messageCount":2,"segmentId":"seg1","indexStatus":"DONE"}]}]"""
        val document = parse(backup(importBatches = batch))
        val imported = document.importBatches.single()
        assertEquals("b1", imported.sourceId)
        assertEquals("CANCELLED", imported.status)
        assertEquals("archive.zip:123", imported.fingerprint)
        val group = imported.groups.single()
        assertEquals("c1", group.targetCharacterSourceId)
        assertEquals("t1", group.targetThreadSourceId)
        assertEquals(true, group.memoryUndoSafe)
        assertEquals(2, group.importMessageCount)
        assertEquals("g1", imported.windows.single().groupSourceId)
        assertEquals("src1", imported.sources.single().sourceId)
        assertEquals("seg1", imported.sources.single().segmentSourceId)

        // Schema-1/2/3 backups made before this optional field continue to restore with no batches.
        assertTrue(parse(backup()).importBatches.isEmpty())
        assertEquals(
            BackupInspection.Rejected(BackupIssue.DAMAGED),
            BackupParser.inspect(backup(importBatches = "{}"), Source.FILE),
        )
    }

    @Test
    fun `SUPPORTED_SCHEMA 与导出 schema 一致`() {
        // 其他用例覆盖旧格式识别；本测试只复核字段解析，不改变导出。
        assertEquals(ExportService.SCHEMA, BackupParser.SUPPORTED_SCHEMA)
    }

    @Test
    fun `对 inspect 已放行的真实导出不抛异常`() {
        // 空导出（0 角色 / 0 会话）形状仍可解析成空文档。
        val empty = """{"schema":3,"app":"pulsez","exportedAt":1,"character":[],"thread":[],"attachments":[],"skippedOrphanThreads":0}"""
        assertEquals(BackupInspection.Preview::class.java, BackupParser.inspect(empty, Source.FILE)::class.java)
        val document = parse(empty)
        assertTrue(document.characters.isEmpty())
        assertTrue(document.threads.isEmpty())
    }

    @Test
    fun `文档只做形状容错 不因为 schema 数值拒绝`() {
        // schema/上限校验是 inspect 的唯一职责；parseBackup 不出现第二份判据。
        val document = parse(Json.parseToJsonElement(backup()).jsonObject.let { it.toString() })
        assertEquals(3, document.schema)
    }
}
