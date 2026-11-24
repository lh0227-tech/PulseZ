package cc.pulsez.app.importer

import cc.pulsez.app.data.ExportService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `BackupParser.inspect` 识别判据（ 建立， /； 强化）。
 *
 * 纯 JVM（不碰 Android/Room/网络）。覆盖四层：
 * 1. **正例**：真备份（BOM/前导空白/旧 schema）→ Preview，计数正确；
 * 2. **反例**：形状命中后 app/schema/数组/JSON 坏 → 一律 Rejected，**绝不降级 Normal**；
 * 3. ** 强标识兜底**：以 `{` 开头且出现 `"app":"pulsez"` 的缺键/截断文本 →
 * Rejected(DAMAGED)，不再 Normal；普通 JSON/普通文本不误伤；完整备份仍 Preview；
 * 4. **残余锁定**：截断在 app 值之前 / 前缀 → Normal（本修法明确的接受残余，见 `BackupParser`
 * 类注释第 ③ 步；不许为了让这几条变绿而放松判据）。
 *
 * 大字符串用例（16.7M 与 6M 字符）在注释里标注内存/耗时，属 允许范围。
 */
class BackupParserInspectTest {

    private companion object {
        /** 造最小/可调备份 JSON（形状固定含四键；值可变）。 */
        fun backupJson(
            schemaLiteral: String = "3",
            appLiteral: String = "\"pulsez\"",
            characters: String = "[]",
            threads: String = "[]",
            attachments: String = "[]",
            exportedAt: String = "1700000000000",
            skippedOrphanThreads: String? = null,
        ): String = buildString {
            append("{\"schema\":").append(schemaLiteral)
            append(",\"app\":").append(appLiteral)
            append(",\"exportedAt\":").append(exportedAt)
            append(",\"character\":").append(characters)
            append(",\"thread\":").append(threads)
            append(",\"attachments\":").append(attachments)
            if (skippedOrphanThreads != null) {
                append(",\"skippedOrphanThreads\":").append(skippedOrphanThreads)
            }
            append("}")
        }

        fun issues(text: String, source: Source = Source.FILE): BackupIssue? =
            (BackupParser.inspect(text, source) as? BackupInspection.Rejected)?.issue
    }

    // ---------------------------------------------------------------- 正例

    @Test
    fun `真备份 带 BOM 与前导空白 也能认出来且计数正确`() {
        val json = backupJson(
            characters = "[{},{}]",
            threads = "[{\"messages\":[{},{},{}]}]",
            attachments = "[{},{}]",
            skippedOrphanThreads = "1",
        )
        listOf(json, "\uFEFF$json", "\n\t  $json").forEach { text ->
            val inspection = BackupParser.inspect(text, Source.FILE)
            val preview = (inspection as? BackupInspection.Preview)?.preview
            requireNotNull(preview) { "应识别为备份预览：${text.take(20)}" }
            assertEquals(3, preview.schema)
            assertEquals(1_700_000_000_000L, preview.exportedAt)
            assertEquals(2, preview.characterCount)
            assertEquals(1, preview.threadCount)
            assertEquals(3, preview.messageCount)
            assertEquals(2, preview.attachmentCount)
            assertEquals(1, preview.skippedOrphanThreads)
        }
    }

    @Test
    fun `旧备份 schema 1-2-3 且没有新增字段也能预览`() {
        listOf("1", "2", "3").forEach { schema ->
            val json = "{\"schema\":$schema,\"app\":\"pulsez\",\"character\":[],\"thread\":[]}"
            val preview = (BackupParser.inspect(json, Source.PASTE) as? BackupInspection.Preview)?.preview
            requireNotNull(preview) { "旧 schema=$schema 不应被拒" }
            assertEquals(schema.toInt(), preview.schema)
            assertNull("缺 exportedAt → null，不判损坏", preview.exportedAt)
            assertEquals("缺 skippedOrphanThreads → 0", 0, preview.skippedOrphanThreads)
            assertEquals(0, preview.attachmentCount)
        }
    }

    @Test
    fun `SUPPORTED_SCHEMA 与导出 SCHEMA 同源不漂移`() {
        assertEquals(ExportService.SCHEMA, BackupParser.SUPPORTED_SCHEMA)
    }

    // ---------------------------------------------------------------- 反例：形状命中后绝不降级

    @Test
    fun `app 值损坏 空串 数字 非字符串 都是 DAMAGED 不是 Normal`() {
        listOf("\"not-pulsez\"", "\"\"", "123", "{}", "true").forEach { appLiteral ->
            assertEquals("app=$appLiteral 必须 Rejected(DAMAGED)", BackupIssue.DAMAGED, issues(backupJson(appLiteral = appLiteral)))
        }
    }

    @Test
    fun `四键命中但 JSON 截断或语法坏 是 DAMAGED`() {
        val truncated = "{\"schema\":3,\"app\":\"pulsez\",\"character\":[],\"thread\":["
        assertEquals(BackupIssue.DAMAGED, issues(truncated))
        assertEquals(BackupIssue.DAMAGED, issues("{\"schema\":3,\"app\":\"pulsez\",\"character\":[],\"thread\":[]} trailing"))
    }

    @Test
    fun `schema 非整数或越界 的四种坏值`() {
        assertEquals(BackupIssue.DAMAGED, issues(backupJson(schemaLiteral = "\"3\"")))
        assertEquals(BackupIssue.DAMAGED, issues(backupJson(schemaLiteral = "3.5")))
        assertEquals(BackupIssue.DAMAGED, issues(backupJson(schemaLiteral = "null")))
        assertEquals(BackupIssue.DAMAGED, issues(backupJson(schemaLiteral = "0")))
        assertEquals(BackupIssue.TOO_NEW, issues(backupJson(schemaLiteral = "4")))
    }

    @Test
    fun `顶层数组形状坏 是 DAMAGED`() {
        assertEquals(BackupIssue.DAMAGED, issues(backupJson(characters = "{}")))
        assertEquals(BackupIssue.DAMAGED, issues(backupJson(threads = "{}")))
        assertEquals(BackupIssue.DAMAGED, issues(backupJson(attachments = "{}")))
        assertEquals(BackupIssue.DAMAGED, issues(backupJson(characters = "[1]")))
        assertEquals(BackupIssue.DAMAGED, issues(backupJson(threads = "[1]")))
        assertEquals(BackupIssue.DAMAGED, issues(backupJson(threads = "[{\"messages\":{}}]")))
    }

    @Test
    fun `超过角色 会话 消息上限 各自 Rejected 且原因互不相同`() {
        val tooManyCharacters = backupJson(characters = List(BackupParser.MAX_CHARACTERS + 1) { "{}" }.joinToString(",", "[", "]"))
        val tooManyThreads = backupJson(threads = List(BackupParser.MAX_THREADS + 1) { "{}" }.joinToString(",", "[", "]"))
        val tooManyMessages = backupJson(
            threads = "[{\"messages\":" +
                List(BackupParser.MAX_MESSAGES + 1) { "{}" }.joinToString(",", "[", "]") + "}]",
        )
        val causes = listOf(tooManyCharacters, tooManyThreads, tooManyMessages).map { requireNotNull(issues(it)) }
        assertEquals(
            listOf(BackupIssue.TOO_MANY_CHARACTERS, BackupIssue.TOO_MANY_THREADS, BackupIssue.TOO_MANY_MESSAGES),
            causes,
        )
        assertEquals("原因必须互不相同", 3, causes.distinct().size)
    }

    // ---------------------------------------------------------------- ：强标识兜底

    @Test
    fun `四键不齐但带 PulseZ 强标识 是 DAMAGED 不是 Normal`() {
        // 原始形态：`{"schema":3,"app":"pulsez","character":[...`——缺 thread、JSON 被截断。
        // （只认强特征 `"app":"pulsez"`，命中即 Rejected(DAMAGED)）。
        val truncated = "{\"schema\":3,\"app\":\"pulsez\",\"character\":[{\"id\":\"c1\",\"name\":\"阿淮\",\"persona\":\"温和\"}"
        assertEquals(BackupIssue.DAMAGED, issues(truncated))
        // 只缺一个键、JSON 完整：同样不得降级（`messages` 不是形状键，四键不齐）。
        val missingThread = "{\"schema\":3,\"app\":\"pulsez\",\"character\":[],\"messages\":[]}"
        assertEquals(BackupIssue.DAMAGED, issues(missingThread))
        // 更早的缺键形态：只有 schema+app、只有 app。
        assertEquals(BackupIssue.DAMAGED, issues("{\"schema\":3,\"app\":\"pulsez\"}"))
        assertEquals(BackupIssue.DAMAGED, issues("{\"app\":\"pulsez\"}"))
        // 三入口同判据（FILE/PASTE/SHARE 都不得退回 Normal）。
        listOf(Source.FILE, Source.PASTE, Source.SHARE).forEach { source ->
            assertEquals("source=$source 也必须拒绝", BackupIssue.DAMAGED, issues(truncated, source))
        }
    }

    @Test
    fun `强标识容忍空白 带 BOM 前导空白截断后一样拒绝`() {
        val variants = listOf(
            // `"app"` 与冒号、值与引号之间容忍任意空白（与 SHAPE_KEYS 同口径）。
            "{\"schema\":3,\"app\" : \"pulsez\",\"character\":[]}",
            "{\"app\"\n\t:\n\"pulsez\"}",
            // BOM + 前导空白 + 截断：去 BOM/trimStart 后仍要认出来（与第 ①② 步同读法）。
            "\uFEFF\n  {\"schema\":3,\"app\":\"pulsez\",\"character\":[",
        )
        variants.forEach { text ->
            assertEquals("强标识变体必须 DAMAGED：$text", BackupIssue.DAMAGED, issues(text))
        }
    }

    @Test
    fun `普通 JSON 与普通文本不误伤（不扩大拒绝面）`() {
        val normals = listOf(
            // 普通 JSON：`app` 不是 pulsez，也没有四键碎片 → Normal。
            "{\"name\":\"阿淮\",\"app\":\"other\",\"character\":[]}",
            "{\"app\":\"other\"}",
            // 有 `"app":"pulsez"` 字样，但不以 `{` 开头 → Normal（第 ② 步既有顺序不变）。
            "备份如下：{\"schema\":3,\"app\":\"pulsez\",\"character\":[],\"thread\":[]}",
            "对方说值应该是 \"app\":\"pulsez\"，我记下了。",
            // `app` 键在、值不是 pulsez（空串/大小写不同）：缺 thread 时仍按普通 JSON → Normal。
            "{\"schema\":3,\"app\":\"\",\"character\":[]}",
            "{\"schema\":3,\"app\":\"PulseZ\",\"character\":[]}",
        )
        normals.forEach { text ->
            assertEquals("不误伤：${text.take(20)}", BackupInspection.Normal, BackupParser.inspect(text, Source.FILE))
        }
    }

    @Test
    fun `带强标识的完整备份仍 Preview（兜底不扩大拒绝面）`() {
        val json = backupJson(
            characters = "[{\"name\":\"阿淮\"}]",
            threads = "[{\"messages\":[{},{}]}]",
        )
        val preview = (BackupParser.inspect(json, Source.FILE) as? BackupInspection.Preview)?.preview
        requireNotNull(preview) { "完整备份必须仍是 Preview" }
        assertEquals(3, preview.schema)
        assertEquals(1, preview.characterCount)
        assertEquals(1, preview.threadCount)
        assertEquals(2, preview.messageCount)
    }

    // ---------------------------------------------------------------- 误判/漏判残余锁定

    @Test
    fun `截断在 app 值之前 仍走 Normal（明确接受的残余）`() {
        // 强标识是完整的 `"app":"pulsez"`；在它出现之前截断就认不出是 PulseZ 备份。
        // 导出里 app 是第 2 个顶层字段（`data/ExportService.kt:273-278`），残余窗口只在文件头，
        // 但不清零——照实锁定；不许为了让这条变绿而放宽/收紧判据。
        val residuals = listOf(
            "{\"schema\":3,\"character\":[{\"id\":\"c1\",\"name\":\"阿淮\",\"persona\":\"温和\"}]", // 截在 app 键之前
            "{\"schema\":3,\"app\":", // 截在 app 值之前
            "{\"schema\":3,\"app\":\"pul", // 截在 app 值中间
            "{\"schema\":3,\"app\":\"pulsez", // 截在值末引号前
        )
        residuals.forEach { text ->
            assertEquals("残余必须仍为 Normal：$text", BackupInspection.Normal, BackupParser.inspect(text, Source.FILE))
        }
    }

    @Test
    fun `普通 JSON 里恰好出现强标识 按安全方向 DAMAGED（接受的误伤方向）`() {
        // 强标识是字面量判据（与 同一安全方向）：不是 PulseZ 备份、但以 `{` 开头且含
        // 完整 `"app":"pulsez"` 的普通 JSON 会被判「这份备份读不了」；比把原文当人设安全。
        // 不以 `{` 开头的普通文本不受影响（见上方「普通 JSON 与普通文本不误伤」用例）。
        assertEquals(BackupIssue.DAMAGED, issues("{\"note\":\"app 字段\",\"meta\":{\"app\":\"pulsez\"}}"))
    }

    @Test
    fun `形状命中但不是 PulseZ 备份的普通 JSON 是 DAMAGED 不是 Normal`() {
        // ：安全方向锁定——宁可显示「这份备份读不了」，也绝不当人设。
        val lookalike = "{\"schema\":{\"app\":1},\"app\":{\"character\":2},\"character\":{\"thread\":3},\"thread\":4}"
        assertEquals(BackupIssue.DAMAGED, issues(lookalike))
    }

    @Test
    fun `备份前带非空白前缀 走 Normal（明确接受的前缀漏判残余）`() {
        // ：不做前缀剥离（剥离会把「以说明文字开头的普通导入」误判成备份，风险更大）。
        assertEquals(BackupInspection.Normal, BackupParser.inspect("备份如下：\n" + backupJson(), Source.FILE))
    }

    // ---------------------------------------------------------------- 普通内容不解析

    @Test
    fun `普通角色卡 聊天 Markdown 纯文本 空串 都是 Normal`() {
        val normals = listOf(
            "{\"name\":\"阿淮\",\"personality\":\"温和\",\"first_mes\":\"在的\"}",
            "我：在吗\nTA：在的\n我：今天忙吗\nTA：还行",
            "---\nname: 阿淮\ndescription: 温和\n---\n正文",
            "一段普通人设，没有 JSON 形状。",
            "",
            "   \n\t ",
        )
        normals.forEach { text ->
            assertEquals("应为 Normal：${text.take(20)}", BackupInspection.Normal, BackupParser.inspect(text, Source.FILE))
        }
    }

    // ---------------------------------------------------------------- 非 FILE 的真实 UTF-8 字节上限

    @Test
    fun `非 FILE 形状命中且真实字节超限 先判 TOO_LARGE 不进 JSON 解析`() {
        // 形状四键都在文件头；尾部故意不是合法 JSON。若实现先解析，会得到 DAMAGED。
        // 内存：约 16.7M 字符 ≈ 33 MB（允许，已在用例内注明）。
        val head = "{\"schema\":3,\"app\":\"pulsez\",\"character\":[],\"thread\":[]"
        val text = head + "x".repeat(BackupParser.FILE_MAX_BYTES.toInt() - head.length + 8)
        assertTrue(text.length.toLong() > BackupParser.FILE_MAX_BYTES)
        assertEquals(BackupIssue.TOO_LARGE, issues(text, Source.PASTE))
        assertEquals(BackupIssue.TOO_LARGE, issues(text, Source.SHARE))
        // FILE 入口不在 inspect 里按字节再判：同文本的坏 JSON 结构应由 JSON 解析拒绝（DAMAGED）。
        assertEquals(BackupIssue.DAMAGED, issues(text, Source.FILE))
    }

    @Test
    fun `同一份约 6MB 合法备份 FILE 与 PASTE 都预览`() {
        // E7：真实 UTF-8 字节 < 16 MiB，不再按字符上界误拒。
        // 内存：约 6.3M 字符 ≈ 13 MB + PASTE 编码副本 6 MB（已注明）。
        val pad = "x".repeat(6 * 1024 * 1024)
        val json = "{\"schema\":3,\"app\":\"pulsez\",\"character\":[],\"thread\":[]," +
            "\"attachments\":[],\"pad\":\"$pad\"}"
        listOf(Source.FILE, Source.PASTE).forEach { source ->
            val preview = (BackupParser.inspect(json, source) as? BackupInspection.Preview)?.preview
            requireNotNull(preview) { "6MB 合法备份应预览（source=$source）" }
            assertEquals(3, preview.schema)
        }
    }
}
