package cc.pulsez.app.importer

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull

/**
 * PulseZ 备份文件识别与有界读取。
 *
 * 为什么要有这一层：导出的 `PulseZ-backup.json` 以前从文件/粘贴/分享三个入口进来时，
 * 只会走 `ImportPipeline` 的「角色卡 → Markdown → 聊天记录 → 纯文本」判别；备份里有 `"name"`，
 * 会被当成 JSON 角色卡，解析不出就整段当人设（`schema3=true / format=TEXT / persona=原始 JSON`）。
 * 本层的唯一职责是**在解析之前先回答「这是不是 PulseZ 备份、坏在哪」**：
 * - 纯 JVM、单次、无副作用：不写库、不联网、不读盘（文件读取由调用方用 [readFile] 完成）；
 * - 形状命中、或出现 PulseZ 强标识（[PULSEZ_APP_MARKER]）后，**绝不降级**为 `Normal`
 *   （缺键/截断/app/schema/数组/JSON 坏都是 [BackupIssue.DAMAGED]）；
 * - 只做形状与规模判断；字段级校验和恢复由后续流程负责。
 *
 * 判据顺序必须保持，以避免误判；同时检查 PulseZ 强标识：
 * 1. 去 BOM + 去前导空白；
 * 2. 不以 `{` 开头 → [BackupInspection.Normal]；
 * 3. 形状判据+ v0.5.3 强标识兜底：
 *    - 四个**键片段**（键名后接冒号，容忍空白；只判键名不判值）必须同时出现：
 *      `"schema":`、`"app":`、`"character":`、`"thread":`；四键齐 → 进入第 4 步；
 *    - 四键不齐，但出现 PulseZ 强标识（[PULSEZ_APP_MARKER]：`"app"` 的值逐字为 `pulsez`，
 *      容忍空白，即正则 `"app"\s*:\s*"pulsez"`）→ 直接 [BackupInspection.Rejected]
 *      （[BackupIssue.DAMAGED]）。**缺键/截断也不许退回 Normal**——带 PulseZ 标识的备份
 *      原文绝不能进入普通人设导入链，避免被误当作人设；
 *    - 四键不齐且无强标识 → [BackupInspection.Normal]（普通 JSON/普通文本，行为与 v0.5.2 一致）；
 *    - **剩余边界**：截断点落在强标识之前（导出的 `"app":"pulsez"` 是第 2 个顶层字段，
 *      见 `data/ExportService.kt:273-278`）或备份前带非空白前缀 → 强标识不出现 → 仍走 Normal，
 *      用户看到的仍是普通人设草稿页。该窗口已从「app 键之后任意截断」收窄到「文件头到 app 值」，
 *      但无法清零；测试照实锁定（`BackupParserInspectTest` 的 残余用例）。
 * 4. 形状命中后，第 5–10 步全部放进同一个 try/catch：OOM → TOO_LARGE，其它 Throwable → DAMAGED；
 * 5. 非 FILE 入口的真实 UTF-8 字节上限（先用字符数短路）；FILE 不再判大小（有界读已把关）；
 * 6. JSON 解析；不是对象 → DAMAGED；
 * 7. `app` 值不是 `"pulsez"` → DAMAGED（值坏必须拒绝，绝不能退回 Normal）；
 * 8. `schema` 非整数 → DAMAGED；> [SUPPORTED_SCHEMA] → TOO_NEW；< 1 → DAMAGED；
 * 9. 顶层 `character`/`thread` 不是数组、`attachments` 存在但不是数组 → DAMAGED；
 * 10. 数量上限（统计循环一超限立即返回）：角色 / 会话 / 消息；
 * 11. 全过 → [BackupInspection.Preview]。
 *
 * 常量依据（的四行推导）：
 * - 每条消息 JSON 实算：`id`(UUID 36) + `threadId`(36) + `role` + `createdAt`(13 位) +
 *   `segmentId`(36) + `tokens` + 正文；键名/引号/逗号/结构开销 ≈ 200 B（对应
 *   `data/ExportService.kt` 实际字段逐项），正文按 200 个中文字符 = 600 B（UTF-8）**假设**
 *   → ≈ 800 B/条；
 * - [MAX_MESSAGES] = 15,000 × 800 B = 12,000,000 B ≈ 11.44 MiB；非消息内容预留 2 MiB
 *   （角色/段/记忆/附件元数据）→ ≈ 13.44 MiB < 16 MiB，余量 ≈ 2.56 MiB（≈16%）；
 * - 若沿用 20,000：20,000 × 800 B ≈ 15.26 MiB + 2 MiB = 17.26 MiB **超过 16 MiB**（贴线），
 *   故下调到 15,000；
 * - 16 MiB（[FILE_MAX_BYTES]）是**本版自定承诺**，不是平台硬限制；未做低端机实测（残余），
 *   正文 200 字/条也是假设（残余）。
 *
 * 读取纪律：`OpenableColumns.SIZE` **只用于提前拒绝**，绝不用来决定"可以无界读"；
 * 非 FILE 的备份用 `text.encodeToByteArray().size` 判真实 UTF-8 字节，FILE 由 [readFile] 把关。
 */
object BackupParser {

    /** 单份备份允许的最大字节数（16 MiB；推导见类注释）。 */
    const val FILE_MAX_BYTES: Long = 16_777_216L

    /** 同上的 MiB 值，**只用于文案**；与 [FILE_MAX_BYTES] 的不变量由 init + 测试双锁。 */
    const val FILE_MAX_MB: Int = 16

    /** 单份备份允许的最大消息条数（推导见类注释：20,000 会贴线/超线）。 */
    const val MAX_MESSAGES: Int = 15_000

    /** 单份备份允许的最大会话（对话）数。 */
    const val MAX_THREADS: Int = 500

    /** 单份备份允许的最大角色数。 */
    const val MAX_CHARACTERS: Int = 500

    /**
     * 本版能认的导出 schema 上限。
     *
     * `importer` 不反向依赖 `data`，所以这里本地定义（不引用 `ExportService.SCHEMA`）；
     * 两处漂移由测试断言 `BackupParser.SUPPORTED_SCHEMA == ExportService.SCHEMA` 锁住。
     */
    const val SUPPORTED_SCHEMA: Int = 3

    /** 有界读的块大小（64 KiB，与 `ImportCache.copyStreamLimited` 同口径）。 */
    private const val READ_CHUNK_BYTES: Int = 64 * 1024

    /** 形状判据的四个键片段（键名后接冒号，容忍空白；不判值）。 */
    private val SHAPE_KEYS: List<Regex> = listOf(
        Regex("\"schema\"\\s*:"),
        Regex("\"app\"\\s*:"),
        Regex("\"character\"\\s*:"),
        Regex("\"thread\"\\s*:"),
    )

    /**
     * PulseZ 强标识（v0.5.3）：`"app"` 的值逐字为 `pulsez`，容忍空白。
     *
     * 只认强特征、不猜：值必须是逐字小写的 `pulsez`（导出侧固定写这个字面量，
     * `data/ExportService.kt:274`）；不比大小写、不做近似匹配，避免把普通 JSON 误判成备份。
     *
     * 用途：文本以 `{` 开头且命中本正则时，
     * 即使四形状键不齐 / JSON 被截断，也**绝不降级**为 [BackupInspection.Normal]，
     * 而是按 [BackupIssue.DAMAGED] 拒绝，避免截断的备份原文进入人设草稿。
     * 普通 JSON 里即使恰好出现 `"app":"pulsez"` 也会判为损坏；这是比将备份原文当作人设更安全的处理。
     */
    private val PULSEZ_APP_MARKER: Regex = Regex("\"app\"\\s*:\\s*\"pulsez\"")

    init {
        // 确保字节上限与 MB 常量一致；测试也有同口径断言。
        require(FILE_MAX_BYTES == FILE_MAX_MB.toLong() * 1024 * 1024) {
            "FILE_MAX_MB 与 FILE_MAX_BYTES 漂移"
        }
    }

    /**
     * 判断一段已经解码的文本是不是 PulseZ 备份。
     *
     * @param text 已解码文本（文件入口由 [CharsetDetector] 解一次；粘贴/分享直接用原串）
     * @param source 入口来源；只影响「非 FILE 才在形状命中后判真实字节上限」
     */
    fun inspect(text: String, source: Source): BackupInspection {
        // ① 去 BOM + 前导空白（复用 ImportText.withoutBom，否则 BOM 备份识别不到会被当人设）。
        val t = text.withoutBom().trimStart()
        // ② 不以 { 开头：普通文本/角色卡/聊天记录，一律 Normal（不做 JSON 解析）。
        //    这一条保持 v0.5.2 既有顺序：强标识/形状判据只对 `{` 开头的文本生效，普通文本不误伤。
        if (!t.startsWith("{")) return BackupInspection.Normal
        // ③ 形状判据+ v0.5.3 强标识兜底。
        //    四键「键:」片段不齐时，只要出现 PulseZ 强标识（`"app"` 值逐字为 `pulsez`）就**拒绝**，
        //    绝不退回 Normal：截断/缺键的备份原文一旦走 Normal，会进普通人设导入链成为草稿。
        //    只认强特征；命中后即返回 Rejected(DAMAGED)。
        if (!hasBackupShape(t)) {
            if (PULSEZ_APP_MARKER.containsMatchIn(t)) {
                return BackupInspection.Rejected(BackupIssue.DAMAGED)
            }
            // 四键不齐且无强标识：普通 JSON/普通文本 → Normal（行为与 v0.5.2 一致）。
            // 接受的残余（照实写清）：截断点落在 `"app":"pulsez"` 完整值**之前**（含截断在值中间）
            // 或备份前带非空白前缀时，强标识不出现 → 仍 Normal。导出里 app 是第 2 个顶层字段
            // （`data/ExportService.kt:273-278`），所以残余窗口只在文件头到 app 值之间，但不清零。
            return BackupInspection.Normal
        }
        // ④ 形状命中后不许降级：第 5–10 步整体兜异常。
        return try {
            inspectShapeMatched(t, text, source)
        } catch (e: OutOfMemoryError) {
            // 内存不够通常是「文件/文本太大」；按 TOO_LARGE 给用户一句确定性的话。
            BackupInspection.Rejected(BackupIssue.TOO_LARGE)
        } catch (t: Throwable) {
            // 深嵌套 JSON 的 StackOverflowError、encodeToByteArray 失败、计数异常等一律 DAMAGED。
            BackupInspection.Rejected(BackupIssue.DAMAGED)
        }
    }

    /**
     * 有界读：最多读 [FILE_MAX_BYTES] 字节。
     *
     * 语义：
     * - [size]（`OpenableColumns.SIZE`）**只作提前拒绝**：声明值超限直接 [BoundedReadResult.TooLarge]；
     *   缺失/谎报都不放宽实际字节上限；
     * - 读到上限 + 1 即 [BoundedReadResult.TooLarge]（不读完）；
     * - 空流 → `Ok(ByteArray(0))`；
     * - `IOException` → [BoundedReadResult.IoError]；
     * - **不关闭** [input]（调用方 `use`）。
     */
    fun readFile(size: Long?, input: InputStream): BoundedReadResult {
        if (size != null && size > FILE_MAX_BYTES) return BoundedReadResult.TooLarge
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(READ_CHUNK_BYTES)
        var total = 0L
        try {
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                if (read == 0) continue
                // 先判断再写：超限那一块不写进结果，与 ImportCache.copyStreamLimited 同口径。
                if (total + read > FILE_MAX_BYTES) return BoundedReadResult.TooLarge
                out.write(buffer, 0, read)
                total += read
            }
        } catch (e: IOException) {
            return BoundedReadResult.IoError(e)
        }
        return BoundedReadResult.Ok(out.toByteArray())
    }

    /**
     * 把已经通过 [inspect] 的备份文本读成**字段级内容**（v0.5.2 纯 JVM、无副作用）。
     *
     * 契约：
     * - **只做形状容错 + 空值归一**：不做 schema/上限校验（那是 [inspect] 的唯一职责，
     *   不允许出现第二份判据）、不写库、不联网；
     * - 对 `inspect` 已放行的输入**不抛异常**：任何字段类型不符都回落默认值
     *   （数字/字符串/数组/对象缺省或类型错 → 默认；元素不是对象就跳过该条）；
     * - 只有「JSON 语法坏」或「根不是对象」才抛异常（调用方 `ImportViewModel` 兜底成
     *   「恢复失败」，绝不把已判定的备份 Preview 降级成普通文本）；
     * - `""` / JSON `null` / 缺字段 → `null` **只在这里做一次**，恢复层不再判空串
     *   （避免两处口径）；`memory.validTo` 的 `0` 哨兵也**只在这里**转 `null`
     *   （依据：`data/ExportService.kt:196` 导出 `memory.validTo ?: 0L`）。
     * - 调用前必须先经 [inspect] 拿到 [BackupInspection.Preview]（本函数不重复判规模）。
     */
    fun parseBackup(text: String): BackupDocument {
        // 与 inspect 同一读法：去 BOM + 前导空白（BOM 备份 inspect 能认，解析也不能漏）。
        val root = Json.parseToJsonElement(text.withoutBom().trimStart()) as? JsonObject
            ?: throw IllegalArgumentException("备份根不是 JSON 对象")
        return BackupDocument(
            schema = root.intField("schema") ?: 0,
            exportedAt = root.longField("exportedAt")?.takeIf { it > 0 },
            // 缺失/非法 → 0；只做展示/统计，不参与恢复判据。
            skippedOrphanThreads = root.intField("skippedOrphanThreads") ?: 0,
            characters = root.objects("character").map { element ->
                BackupCharacter(
                    sourceId = element.stringField("id").orEmpty(),
                    // 空 → ""，恢复层兜底成 ImportRepository.DEFAULT_CHARACTER_NAME。
                    name = element.stringField("name").orEmpty(),
                    // 非可空列：导出原样写出，这里也原样读回（不 trim、不重算）。
                    persona = element.stringField("persona").orEmpty(),
                    greeting = element.stringField("greeting"),
                    userName = element.stringField("userName"),
                    styleSamples = element.stringField("styleSamples"),
                    sourceType = element.stringField("sourceType").orEmpty(),
                    createdAt = element.longField("createdAt") ?: 0,
                    updatedAt = element.longField("updatedAt") ?: 0,
                )
            },
            threads = root.objects("thread").map(::parseThread),
            attachmentCount = (root["attachments"] as? JsonArray)?.size ?: 0,
            importBatches = root.objects("importBatches").map(::parseImportBatch),
        )
    }

    // ---- 内部实现 ----

    /** 顶层数组字段 → 只保留对象元素（inspect 已保证都是对象；这里只做容错）。 */
    private fun JsonObject.objects(key: String): List<JsonObject> =
        (this[key] as? JsonArray)?.mapNotNull { it as? JsonObject }.orEmpty()

    private fun parseThread(element: JsonObject): BackupThread {
        // kind 归一：GROUP 保留；缺/其它（含类型错）→ SINGLE。
        val kind = if (element.stringField("kind") == "GROUP") "GROUP" else "SINGLE"
        return BackupThread(
            sourceId = element.stringField("id").orEmpty(),
            // 群聊导出 JSON null、单聊导出角色 id；缺失/空串 → null（恢复层按孤儿处理）。
            characterSourceId = element.stringField("characterId"),
            kind = kind,
            title = element.stringField("title"),
            createdAt = element.longField("createdAt") ?: 0,
            lastMessageAt = element.longField("lastMessageAt") ?: 0,
            // Missing in older backups means unknown; never infer live activity from message history.
            lastSingleChatActivityAt = element.longField("lastSingleChatActivityAt"),
            messages = element.objects("messages").map { message ->
                BackupMessage(
                    sourceId = message.stringField("id").orEmpty(),
                    // 老备份没有 segmentId → null（决定「合成段」分支 C）。
                    segmentSourceId = message.stringField("segmentId"),
                    role = message.stringField("role").orEmpty(),
                    content = message.stringField("content").orEmpty(),
                    createdAt = message.longField("createdAt") ?: 0,
                    // 缺 → null，恢复层用 TokenEstimator.estimate 兜底（ImportRepository.kt:399 同口径）。
                    tokens = message.intField("tokens"),
                    speakerSourceId = message.stringField("speakerCharacterId"),
                )
            },
            segments = element.objects("segments").map { segment ->
                BackupSegment(
                    sourceId = segment.stringField("id").orEmpty(),
                    // 缺/非数字 → Int.MAX_VALUE（排在有 ordinal 的后面）。
                    ordinal = segment.intField("ordinal") ?: Int.MAX_VALUE,
                    summary = segment.stringField("summary"),
                    firstMessageAt = segment.longField("firstMessageAt")?.takeIf { it > 0 },
                    lastMessageAt = segment.longField("lastMessageAt")?.takeIf { it > 0 },
                    tokenSum = segment.intField("tokenSum"),
                    // 导出值不保留语义（恢复层统一写 IMPORT C-3）。
                    batonReason = segment.stringField("batonReason"),
                )
            },
            memories = element.objects("memories").map { memory ->
                BackupMemory(
                    kind = memory.stringField("kind").orEmpty(),
                    key = memory.stringField("key"),
                    value = memory.stringField("value").orEmpty(),
                    status = memory.stringField("status").orEmpty(),
                    validFrom = memory.longField("validFrom") ?: 0,
                    // 0 哨兵/缺/非法 → null：TemporalEntry.isActive() 只认 validTo == null
                    // （mem/MemTypes.kt:109-110），写回 0 会让恢复的记忆全部被判失效。
                    validTo = memory.longField("validTo")?.takeIf { it > 0 },
                    characterSourceId = memory.stringField("characterId"),
                    confidence = memory.doubleField("confidence")?.toFloat(),
                    importance = memory.intField("importance"),
                    happenedAt = memory.longField("happenedAt"),
                    dueAt = memory.longField("dueAt"),
                    firstSeenAt = memory.longField("firstSeenAt"),
                    lastSeenAt = memory.longField("lastSeenAt"),
                )
            },
            members = element.objects("members").map { member ->
                BackupMember(
                    characterSourceId = member.stringField("characterId").orEmpty(),
                    ordinal = member.intField("ordinal") ?: 0,
                    joinedAt = member.longField("joinedAt") ?: 0,
                )
            },
        )
    }

    /** Import bookkeeping is optional in schema 3 so existing backups remain readable. */
    private fun parseImportBatch(element: JsonObject): BackupImportBatch = BackupImportBatch(
        sourceId = element.stringField("id").orEmpty(),
        fingerprint = element.stringField("fingerprint").orEmpty(),
        zipName = element.stringField("zipName").orEmpty(),
        createdAt = element.longField("createdAt") ?: 0L,
        status = element.stringField("status").orEmpty(),
        postProcessStatus = element.stringField("postProcessStatus").orEmpty(),
        groups = element.objects("groups").map(::parseImportGroup),
        windows = element.objects("windows").map(::parseImportWindow),
        sources = element.objects("sources").map(::parseImportSource),
    )

    private fun parseImportGroup(element: JsonObject): BackupImportGroup = BackupImportGroup(
        sourceId = element.stringField("id").orEmpty(),
        targetCharacterSourceId = element.stringField("targetCharacterId"),
        targetThreadSourceId = element.stringField("targetThreadId"),
        isNewCharacter = element.booleanField("isNewCharacter") ?: false,
        name = element.stringField("name").orEmpty(),
        persona = element.stringField("persona"),
        status = element.stringField("status").orEmpty(),
        memoryUndoSafe = element.booleanField("memoryUndoSafe") ?: false,
        beforeLastMessageAt = element.longField("beforeLastMessageAt"),
        beforeLastExtractAt = element.longField("beforeLastExtractAt"),
        beforeCharacterUpdatedAt = element.longField("beforeCharacterUpdatedAt"),
        importLastMessageAt = element.longField("importLastMessageAt"),
        importMessageCount = element.intField("importMessageCount") ?: 0,
        errorCode = element.stringField("errorCode"),
    )

    private fun parseImportWindow(element: JsonObject): BackupImportWindow = BackupImportWindow(
        sourceSessionId = element.stringField("sourceSessionId").orEmpty(),
        entryName = element.stringField("entryName").orEmpty(),
        title = element.stringField("title"),
        sourceStartedAt = element.longField("sourceStartedAt"),
        messageCount = element.intField("messageCount") ?: 0,
        damageCode = element.stringField("damageCode"),
        groupSourceId = element.stringField("groupId"),
        status = element.stringField("status").orEmpty(),
        errorCode = element.stringField("errorCode"),
    )

    private fun parseImportSource(element: JsonObject): BackupImportSource = BackupImportSource(
        sourceId = element.stringField("id").orEmpty(),
        targetCharacterSourceId = element.stringField("targetCharacterId").orEmpty(),
        targetThreadSourceId = element.stringField("targetThreadId").orEmpty(),
        sourceSessionId = element.stringField("sourceSessionId").orEmpty(),
        title = element.stringField("title"),
        sourceStartedAt = element.longField("sourceStartedAt"),
        messageCount = element.intField("messageCount") ?: 0,
        segmentSourceId = element.stringField("segmentId").orEmpty(),
        indexStatus = element.stringField("indexStatus").orEmpty(),
    )

    /** 字符串字段：只有 JSON 字符串算数；`""` 与非字符串（数字/布尔/对象）→ null。 */
    private fun JsonObject.stringField(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotEmpty() }

    /** 数字字段：JSON 字符串（"123"）、小数、对象一律当缺省。*/
    private fun JsonObject.longField(key: String): Long? {
        val primitive = this[key] as? JsonPrimitive ?: return null
        if (primitive.isString || primitive is JsonNull) return null
        return primitive.longOrNull
    }

    private fun JsonObject.intField(key: String): Int? {
        val primitive = this[key] as? JsonPrimitive ?: return null
        if (primitive.isString || primitive is JsonNull) return null
        return primitive.intOrNull
    }

    private fun JsonObject.doubleField(key: String): Double? {
        val primitive = this[key] as? JsonPrimitive ?: return null
        if (primitive.isString || primitive is JsonNull) return null
        return primitive.doubleOrNull
    }

    private fun JsonObject.booleanField(key: String): Boolean? {
        val primitive = this[key] as? JsonPrimitive ?: return null
        if (primitive.isString || primitive is JsonNull) return null
        return when (primitive.content) {
            "true" -> true
            "false" -> false
            else -> null
        }
    }

    /**
     * 形状判据：四个「键:」片段是否同时出现（只判键名，不判值）。
     *
     * 缺键/截断不在这里直接放行——[inspect] 第 ③ 步会用 [PULSEZ_APP_MARKER] 兜底：
     * 带 PulseZ 强标识的缺键文本按 DAMAGED 拒绝，只有两者都没有才回 Normal。
     */
    private fun hasBackupShape(t: String): Boolean = SHAPE_KEYS.all { it.containsMatchIn(t) }

    /**
     * 形状已命中后的第 5–10 步（调用方已整体 try/catch）。
     *
     * @param t 去 BOM/前导空白后的文本（用于解析）
     * @param raw 原始文本（用于真实 UTF-8 字节判定 写明用 `text.encodeToByteArray()`）
     */
    private fun inspectShapeMatched(t: String, raw: String, source: Source): BackupInspection {
        // ⑤ 非 FILE 的备份按真实 UTF-8 字节判上限；只对备份识别路径生效（普通大文本走 Normal）。
        if (source != Source.FILE) {
            // 字符数先短路：任何字符的 UTF-8 字节数都 ≥ 1，字符数不超就必不超。
            if (raw.length.toLong() > FILE_MAX_BYTES) {
                return BackupInspection.Rejected(BackupIssue.TOO_LARGE)
            }
            if (raw.encodeToByteArray().size.toLong() > FILE_MAX_BYTES) {
                return BackupInspection.Rejected(BackupIssue.TOO_LARGE)
            }
        }

        // ⑥ JSON 解析；顶层必须是对象。
        val root = Json.parseToJsonElement(t) as? JsonObject
            ?: return BackupInspection.Rejected(BackupIssue.DAMAGED)

        // ⑦ app 值必须逐字是 pulsez；值坏必须拒绝（不再用「签名值」当唯一判据，但值仍要校验）。
        val app = root["app"] as? JsonPrimitive
            ?: return BackupInspection.Rejected(BackupIssue.DAMAGED)
        if (!app.isString || app.content != "pulsez") {
            return BackupInspection.Rejected(BackupIssue.DAMAGED)
        }

        // ⑧ schema：必须是 JSON 整数；比本版新 → TOO_NEW，非正整数 → DAMAGED。
        val schemaPrim = root["schema"] as? JsonPrimitive
            ?: return BackupInspection.Rejected(BackupIssue.DAMAGED)
        if (schemaPrim.isString) return BackupInspection.Rejected(BackupIssue.DAMAGED)
        val schema = schemaPrim.intOrNull ?: return BackupInspection.Rejected(BackupIssue.DAMAGED)
        if (schema > SUPPORTED_SCHEMA) return BackupInspection.Rejected(BackupIssue.TOO_NEW)
        if (schema < 1) return BackupInspection.Rejected(BackupIssue.DAMAGED)

        // ⑨ 顶层数组形状。
        val characters = root["character"] as? JsonArray
            ?: return BackupInspection.Rejected(BackupIssue.DAMAGED)
        val threads = root["thread"] as? JsonArray
            ?: return BackupInspection.Rejected(BackupIssue.DAMAGED)
        val attachments = root["attachments"]?.let { it as? JsonArray ?: return BackupInspection.Rejected(BackupIssue.DAMAGED) }
        val importBatches = root["importBatches"]?.let {
            it as? JsonArray ?: return BackupInspection.Rejected(BackupIssue.DAMAGED)
        }

        // ⑩ 数量上限：先角色、再会话、最后消息；统计循环一超限就返回。
        if (characters.size > MAX_CHARACTERS) {
            return BackupInspection.Rejected(BackupIssue.TOO_MANY_CHARACTERS)
        }
        if (threads.size > MAX_THREADS) {
            return BackupInspection.Rejected(BackupIssue.TOO_MANY_THREADS)
        }
        characters.forEach { element ->
            if (element !is JsonObject) return BackupInspection.Rejected(BackupIssue.DAMAGED)
        }
        importBatches?.forEach { element ->
            if (element !is JsonObject) return BackupInspection.Rejected(BackupIssue.DAMAGED)
        }
        var messageCount = 0
        threads.forEach { element ->
            val thread = element as? JsonObject
                ?: return BackupInspection.Rejected(BackupIssue.DAMAGED)
            val messages = thread["messages"]
            if (messages != null && messages !is JsonNull) {
                val array = messages as? JsonArray
                    ?: return BackupInspection.Rejected(BackupIssue.DAMAGED)
                messageCount += array.size
                if (messageCount > MAX_MESSAGES) {
                    return BackupInspection.Rejected(BackupIssue.TOO_MANY_MESSAGES)
                }
            }
        }

        // ⑪ 全过 → 预览。
        val exportedAt = (root["exportedAt"] as? JsonPrimitive)?.let { primitive ->
            if (primitive.isString) null else primitive.longOrNull
        }
        val skipped = (root["skippedOrphanThreads"] as? JsonPrimitive)?.let { primitive ->
            if (primitive.isString) null else primitive.intOrNull
        } ?: 0
        return BackupInspection.Preview(
            BackupPreview(
                schema = schema,
                exportedAt = exportedAt,
                characterCount = characters.size,
                threadCount = threads.size,
                messageCount = messageCount,
                attachmentCount = attachments?.size ?: 0,
                skippedOrphanThreads = skipped,
            ),
        )
    }
}

/** [BackupParser.inspect] 的三种结果：普通内容 / 认得的备份预览 / 拒绝（带确定性原因）。 */
sealed interface BackupInspection {
    data object Normal : BackupInspection
    data class Preview(val preview: BackupPreview) : BackupInspection
    data class Rejected(val issue: BackupIssue) : BackupInspection
}

/**
 * 备份预览的规模计数（不承载恢复所需内容；恢复属）。
 *
 * [exportedAt] 缺失/非数字 → null（不判损坏）；[skippedOrphanThreads] 缺失 → 0。
 */
data class BackupPreview(
    val schema: Int,
    val exportedAt: Long?,
    val characterCount: Int,
    val threadCount: Int,
    val messageCount: Int,
    val attachmentCount: Int,
    val skippedOrphanThreads: Int,
)

/** 备份拒绝原因；文案映射见 `ui/copy/BackupCopy.issueText`（6 条互不相同）。 */
enum class BackupIssue { DAMAGED, TOO_NEW, TOO_LARGE, TOO_MANY_MESSAGES, TOO_MANY_THREADS, TOO_MANY_CHARACTERS }

/** [BackupParser.readFile] 的结果。 */
sealed interface BoundedReadResult {
    data class Ok(val bytes: ByteArray) : BoundedReadResult
    data object TooLarge : BoundedReadResult
    data class IoError(val cause: Throwable) : BoundedReadResult
}


// 字段级备份内容（v0.5.2 恢复输入；纯 JVM，不依赖 mem/Android/Room）。
// 与 BackupPreview/BackupIssue 同级放在文件顶层；parseBackup 是 object BackupParser
// 的成员。语义默认值（名字兜底、tokens 估算、记忆白名单等）在恢复层，不在解析层。


/** 一份备份的字段级内容。调用前必须先经 `BackupParser.inspect` 得到 `Preview`。 */
data class BackupDocument(
    val schema: Int,
    val exportedAt: Long?,
    /** 缺失/非法 → 0；仅展示/统计用（本轮恢复不重新判源侧跳过，按实际落库计）。 */
    val skippedOrphanThreads: Int,
    val characters: List<BackupCharacter>,
    val threads: List<BackupThread>,
    /** 备份里的附件元数据条数；恢复**不写** attachment 表（H）。*/
    val attachmentCount: Int,
    /** Optional schema-3 import provenance; absent in older backups. */
    val importBatches: List<BackupImportBatch> = emptyList(),
)

data class BackupImportBatch(
    val sourceId: String,
    val fingerprint: String,
    val zipName: String,
    val createdAt: Long,
    val status: String,
    val postProcessStatus: String,
    val groups: List<BackupImportGroup>,
    val windows: List<BackupImportWindow>,
    val sources: List<BackupImportSource>,
)

data class BackupImportGroup(
    val sourceId: String,
    val targetCharacterSourceId: String?,
    val targetThreadSourceId: String?,
    val isNewCharacter: Boolean,
    val name: String,
    val persona: String?,
    val status: String,
    /** Only a matching source snapshot permits restore to rebase the undo guard. Missing means unsafe. */
    val memoryUndoSafe: Boolean,
    val beforeLastMessageAt: Long?,
    val beforeLastExtractAt: Long?,
    val beforeCharacterUpdatedAt: Long?,
    val importLastMessageAt: Long?,
    val importMessageCount: Int,
    val errorCode: String?,
)

data class BackupImportWindow(
    val sourceSessionId: String,
    val entryName: String,
    val title: String?,
    val sourceStartedAt: Long?,
    val messageCount: Int,
    val damageCode: String?,
    val groupSourceId: String?,
    val status: String,
    val errorCode: String?,
)

data class BackupImportSource(
    val sourceId: String,
    val targetCharacterSourceId: String,
    val targetThreadSourceId: String,
    val sourceSessionId: String,
    val title: String?,
    val sourceStartedAt: Long?,
    val messageCount: Int,
    val segmentSourceId: String,
    val indexStatus: String,
)

data class BackupCharacter(
    val sourceId: String,
    /** 空 → ""（恢复层兜底成 `ImportRepository.DEFAULT_CHARACTER_NAME`）。 */
    val name: String,
    /** 非可空列；导出原样写出 → 恢复也原样写回（不 trim/不重算/不回填）。 */
    val persona: String,
    val greeting: String?,
    val userName: String?,
    /** `\u0001` 分隔串；恢复层原样整串写入（不拆不重拼，`Repositories.kt:49-51` 读取口径）。*/
    val styleSamples: String?,
    val sourceType: String,
    val createdAt: Long,
    val updatedAt: Long,
)

data class BackupThread(
    val sourceId: String,
    /** `""`/null/缺 → null（群聊）；恢复层据此判定孤儿单聊。 */
    val characterSourceId: String?,
    /** 归一后只可能是 "SINGLE" / "GROUP"。 */
    val kind: String,
    val title: String?,
    val createdAt: Long,
    val lastMessageAt: Long,
    val messages: List<BackupMessage>,
    val segments: List<BackupSegment>,
    val memories: List<BackupMemory>,
    val members: List<BackupMember>,
    /** Missing in older backups is intentionally null; it is not reconstructed from lastMessageAt. */
    val lastSingleChatActivityAt: Long? = null,
)

data class BackupMessage(
    /** 诊断/统计用；恢复用新 id，不写回。 */
    val sourceId: String,
    /** `""`/缺 → null（老备份：没有段归属）。 */
    val segmentSourceId: String?,
    val role: String,
    val content: String,
    val createdAt: Long,
    /** 缺/非数字 → null（恢复层用 `TokenEstimator.estimate` 兜底）。 */
    val tokens: Int?,
    /** `""`/缺 → null；映射不到导出角色时恢复层置 null + 计数。 */
    val speakerSourceId: String?,
)

data class BackupSegment(
    val sourceId: String,
    /** 缺/非数字 → Int.MAX_VALUE（排在有 ordinal 的后面）。 */
    val ordinal: Int,
    val summary: String?,
    /** 缺 → null（老备份没有这三列）。 */
    val firstMessageAt: Long?,
    val lastMessageAt: Long?,
    val tokenSum: Int?,
    /** `""`/缺 → null；恢复层统一写 "IMPORT"（C-3）。*/
    val batonReason: String?,
)

data class BackupMemory(
    val kind: String,
    val key: String?,
    val value: String,
    val status: String,
    val validFrom: Long,
    /** 0 哨兵/缺/非法 → null（`TemporalEntry.isActive()` 只认 null）。 */
    val validTo: Long?,
    /** `""`/缺 → null（单聊/会话级维度）；非空但映射不到 → 恢复层整行丢弃 + 计数。 */
    val characterSourceId: String?,
    val confidence: Float?,
    val importance: Int?,
    val happenedAt: Long?,
    val dueAt: Long?,
    val firstSeenAt: Long?,
    val lastSeenAt: Long?,
)

data class BackupMember(val characterSourceId: String, val ordinal: Int, val joinedAt: Long)
