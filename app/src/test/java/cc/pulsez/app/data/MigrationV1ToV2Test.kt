package cc.pulsez.app.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import cc.pulsez.app.data.db.MessageEntity
import cc.pulsez.app.data.db.PulsezDatabase
import cc.pulsez.app.data.db.ThreadEntity
import cc.pulsez.app.data.db.ThreadKind
import cc.pulsez.app.data.db.ThreadMemberEntity
import cc.pulsez.app.data.repo.MemoryRepository
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * v1 → v2 历史迁移契约测试，并让 Room 执行到当前 v7。
 *
 * **为什么不用 Room `MigrationTestHelper`**：
 * `androidx.room:room-testing` 不在依赖里（`app/build.gradle.kts` 只有 room-runtime/ktx/compiler），
 * 而本测试的写作用域排除了 build 文件，所以不能加依赖。作为替代，这里用**原生 SQLite 造 v1 库**
 * （SQL 逐字取自 KSP 导出的 v1 schema：`app/schemas/cc.pulsez.app.data.db.PulsezDatabase/1.json`），
 * 再让**真实 Room** 打开该库，沿生产的 1→2→3→4→5→6→7 完整迁移链升级；本类仍逐项锁定 v1→v2
 * 阶段的数据和结构兼容性，Room 同时会按当前 v7 schema 完整校验最终库。
 *
 * 这比"只执行迁移 SQL 再看列"更强：Room 的 `RoomOpenHelper.onUpgrade` 在跑完迁移后会调用
 * 生成代码里的 `onValidateSchema`，把最终迁移结果和编译期期望的当前 v7 表结构逐列（含 notNull、
 * defaultValue、主键位置）、逐索引、逐外键比对，不一致直接抛
 * `IllegalStateException("Migration didn't properly handle: ...")`。
 * Room 2.6.1 `RoomOpenHelper.kt`（onUpgrade → onValidateSchema → "Migration didn't properly handle"）
 * 与 `TableInfo.kt`（Column.equals 比较 notNull / defaultValue）。
 * 所以"测试通过"同时证明了：迁移 SQL 能被 SQLite 执行 + 迁移结果与 v2 实体完全一致 + 旧数据没丢。
 *
 * 只用 Robolectric/JVM，不连真机；数据库是临时文件，跑完删除。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MigrationV1ToV2Test {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.deleteDatabase(MIGRATED_DB_NAME)
        context.deleteDatabase(FRESH_DB_NAME)
    }

    @After
    fun tearDown() {
        context.deleteDatabase(MIGRATED_DB_NAME)
        context.deleteDatabase(FRESH_DB_NAME)
    }

    // ------------------------------------------------------------------
    // 1. 旧数据：thread 变 SINGLE、消息与记忆都还在
    // ------------------------------------------------------------------

    @Test
    fun migrationKeepsV1RowsAndMarksThemAsSingleKind() = runBlocking {
        createV1Database()
        val db = openMigrated()
        try {
            val thread = db.threadDao().byId(THREAD_ID)
            assertNotNull("v1 旧 thread 迁移后应仍可读", thread)
            assertEquals("旧 thread 必须默认 SINGLE", ThreadKind.SINGLE, thread!!.kind)
            assertEquals(CHARACTER_ID, thread.characterId)
            assertEquals("旧 thread 的其它字段不应被改", 2, thread.totalMessages)
            assertEquals(0L, thread.lastExtractAt)
            assertFalse(thread.archived)

            val messages = db.messageDao().allForThread(THREAD_ID)
            assertEquals("旧消息不应丢", 2, messages.size)
            assertEquals(listOf("在吗", "在的"), messages.map { it.content })
            assertTrue(
                "v1 消息的 speakerCharacterId 应迁移为 null（用户消息/单聊）",
                messages.all { it.speakerCharacterId == null },
            )

            val memoryRows = db.memoryDao().forOwner(THREAD_ID, null)
            assertEquals("旧记忆应按单聊维度（characterId IS NULL）可读", 1, memoryRows.size)
            assertNull("v1 记忆迁移后 characterId 必须为 null", memoryRows.single().characterId)

            val memory = MemoryRepository(db)
            assertEquals(listOf("她喜欢被叫阿淮"), memory.load(THREAD_ID).facts.map { it.value })
            assertEquals(1, memory.activeCount(THREAD_ID))

            // v2 写入路径在迁移后的库上必须可用：group 成员 / 群 thread / 带发言人的消息
            db.threadMemberDao().insertAll(
                listOf(ThreadMemberEntity(threadId = THREAD_ID, characterId = CHARACTER_ID, ordinal = 0, joinedAt = 3_000L)),
            )
            assertEquals(CHARACTER_ID, db.threadMemberDao().membersOf(THREAD_ID).single().characterId)

            db.threadDao().upsert(
                ThreadEntity(
                    id = GROUP_ID,
                    characterId = null,
                    title = "我们仨",
                    createdAt = 4_000L,
                    lastMessageAt = 4_000L,
                    activeSegmentId = null,
                    segmentOrdinal = 0,
                    totalMessages = 0,
                    totalTokens = 0,
                    archived = false,
                    kind = ThreadKind.GROUP,
                ),
            )
            val group = db.threadDao().byId(GROUP_ID)
            assertNotNull(group)
            assertNull("群聊 thread 的 characterId 必须可空", group!!.characterId)
            assertEquals(ThreadKind.GROUP, group.kind)

            db.messageDao().upsert(
                MessageEntity(
                    id = "m3",
                    threadId = THREAD_ID,
                    segmentId = SEGMENT_ID,
                    role = "assistant",
                    content = "我也是",
                    tokens = 2,
                    emotion = null,
                    status = "OK",
                    draft = null,
                    usageIn = null,
                    usageOut = null,
                    previousContent = null,
                    createdAt = 5_000L,
                    deletedAt = null,
                    speakerCharacterId = CHARACTER_ID,
                ),
            )
            assertEquals(
                listOf("m3"),
                db.messageDao().bySpeaker(THREAD_ID, CHARACTER_ID).map { it.id },
            )
        } finally {
            db.close()
        }
    }

    // ------------------------------------------------------------------
    // 2. 结构：历史相关表保持契约，Room 另外完整校验当前 v7 schema
    // ------------------------------------------------------------------

    @Test
    fun migratedHistoricalTablesMatchFreshCurrentSchema() = runBlocking {
        createV1Database()

        val migrated = openMigrated()
        val fresh = Room.databaseBuilder(context, PulsezDatabase::class.java, FRESH_DB_NAME)
            // Robolectric 的 SQLite 实现不必依赖 WAL；TRUNCATE 让文件库行为更确定
            .setJournalMode(RoomDatabase.JournalMode.TRUNCATE)
            .allowMainThreadQueries()
            .build()
        try {
            // 触发：migrated 沿 v1→v7 生产链升级；fresh 走当前 v7 onCreate
            migrated.openHelper.writableDatabase
            fresh.openHelper.writableDatabase
            assertEquals("v1 旧库必须沿生产链升级到当前 v7", 7, migrated.openHelper.writableDatabase.version)
            assertEquals("全新安装必须是当前 v7", 7, fresh.openHelper.writableDatabase.version)

            val migratedSchema = schemaSnapshot(migrated)
            val freshSchema = schemaSnapshot(fresh)
            assertEquals(
                "v1 历史相关表必须与当前库一致（Room 已完整校验最终 v7 schema）",
                freshSchema,
                migratedSchema,
            )

            // 逐条断言 的契约（schema 对比失败时便于定位）
            val threadColumns = columns(migrated, "thread")
            assertTrue("thread.kind 必须 NOT NULL", threadColumns.getValue("kind").notNull)
            assertEquals(
                "thread.kind 的 DB 默认值必须是 'SINGLE'（与实体的 @ColumnInfo(defaultValue) 一致）",
                "'SINGLE'",
                threadColumns.getValue("kind").defaultValue,
            )
            assertFalse("thread.characterId 必须放开 NOT NULL", threadColumns.getValue("characterId").notNull)

            val messageColumns = columns(migrated, "message")
            assertTrue("message 必须有 speakerCharacterId 列", messageColumns.containsKey("speakerCharacterId"))
            assertFalse("message.speakerCharacterId 必须可空", messageColumns.getValue("speakerCharacterId").notNull)
            assertNull(messageColumns.getValue("speakerCharacterId").defaultValue)

            val memoryColumns = columns(migrated, "memory_item")
            assertTrue("memory_item 必须有 characterId 列", memoryColumns.containsKey("characterId"))
            assertFalse("memory_item.characterId 必须可空", memoryColumns.getValue("characterId").notNull)

            val memberColumns = columns(migrated, "thread_member")
            assertEquals(1, memberColumns.getValue("threadId").primaryKeyPosition)
            assertEquals(2, memberColumns.getValue("characterId").primaryKeyPosition)
            assertEquals("ordinal 不是主键列", 0, memberColumns.getValue("ordinal").primaryKeyPosition)
            assertTrue("ordinal 必须 NOT NULL", memberColumns.getValue("ordinal").notNull)
            assertTrue("joinedAt 必须 NOT NULL", memberColumns.getValue("joinedAt").notNull)
            assertTrue(indexNames(migrated, "thread_member").contains("index_thread_member_characterId"))
        } finally {
            migrated.close()
            fresh.close()
        }
    }

    // ------------------------------------------------------------------
    // v1 库：SQL 逐字取自 app/schemas/.../1.json（KSP 导出的 v1 schema）
    // ------------------------------------------------------------------

    private fun createV1Database() {
        val dbFile = context.getDatabasePath(MIGRATED_DB_NAME)
        dbFile.parentFile?.mkdirs()
        val db = SQLiteDatabase.openOrCreateDatabase(dbFile, null)
        try {
            V1_TABLES.forEach { (table, createSql, indexSqls) ->
                db.execSQL(createSql.replace(TABLE_NAME_TOKEN, table))
                indexSqls.forEach { db.execSQL(it.replace(TABLE_NAME_TOKEN, table)) }
            }

            // 旧数据：一个角色、一条单聊 thread、两条消息、一条记忆
            db.execSQL(
                "INSERT INTO `character` (" +
                    "`id`, `name`, `avatarUri`, `persona`, `greeting`, `styleSamples`, `userName`, " +
                    "`systemPromptOverride`, `protocolEnabled`, `sourceType`, `sourceRef`, `rawImported`, " +
                    "`emoAssetDir`, `createdAt`, `updatedAt`, `deletedAt`) VALUES (" +
                    "'$CHARACTER_ID', '阿淮', NULL, '温和、话不多', NULL, NULL, NULL, NULL, 1, 'MANUAL', " +
                    "NULL, NULL, NULL, 1000, 1000, NULL)",
            )
            db.execSQL(
                "INSERT INTO `thread` (" +
                    "`id`, `characterId`, `title`, `createdAt`, `lastMessageAt`, `activeSegmentId`, " +
                    "`segmentOrdinal`, `totalMessages`, `totalTokens`, `lastExtractAt`, `archived`) VALUES (" +
                    "'$THREAD_ID', '$CHARACTER_ID', NULL, 1000, 2000, '$SEGMENT_ID', 0, 2, 10, 0, 0)",
            )
            db.execSQL(
                "INSERT INTO `segment` (" +
                    "`id`, `threadId`, `ordinal`, `summary`, `styleNotes`, `firstMessageAt`, `lastMessageAt`, " +
                    "`tokenSum`, `state`, `batonReason`, `createdAt`) VALUES (" +
                    "'$SEGMENT_ID', '$THREAD_ID', 0, NULL, NULL, 1000, 2000, 10, 'OPEN', NULL, 1000)",
            )
            db.execSQL(
                "INSERT INTO `message` (" +
                    "`id`, `threadId`, `segmentId`, `role`, `content`, `tokens`, `emotion`, `status`, `draft`, " +
                    "`usageIn`, `usageOut`, `previousContent`, `createdAt`, `deletedAt`) VALUES (" +
                    "'m1', '$THREAD_ID', '$SEGMENT_ID', 'user', '在吗', 2, NULL, 'OK', NULL, NULL, NULL, NULL, 1000, NULL)",
            )
            db.execSQL(
                "INSERT INTO `message` (" +
                    "`id`, `threadId`, `segmentId`, `role`, `content`, `tokens`, `emotion`, `status`, `draft`, " +
                    "`usageIn`, `usageOut`, `previousContent`, `createdAt`, `deletedAt`) VALUES (" +
                    "'m2', '$THREAD_ID', '$SEGMENT_ID', 'assistant', '在的', 2, '平静', 'OK', NULL, 10, 2, NULL, 2000, NULL)",
            )
            db.execSQL(
                "INSERT INTO `memory_item` (" +
                    "`id`, `threadId`, `kind`, `key`, `value`, `confidence`, `importance`, `happenedAt`, " +
                    "`dueAt`, `validFrom`, `validTo`, `firstSeenAt`, `lastSeenAt`, `sourceSegmentId`, " +
                    "`sourceMessageIds`, `status`, `supersededBy`) VALUES (" +
                    "'mem1', '$THREAD_ID', 'FACT', '称呼', '她喜欢被叫阿淮', 0.9, 4, NULL, NULL, " +
                    "1000, NULL, 1000, 1000, '$SEGMENT_ID', 'm1', 'ACTIVE', NULL)",
            )

            // 关键：告诉 Room 这是 v1 库，打开时才会走 1→2 迁移而不是 onCreate
            db.version = 1
        } finally {
            db.close()
        }
    }

    private fun openMigrated(): PulsezDatabase =
        Room.databaseBuilder(context, PulsezDatabase::class.java, MIGRATED_DB_NAME)
            // DB 当前为 v7：v1 fixture 必须沿完整生产链升级；本测试额外锁定 v1→v2 的旧数据与结构契约。
            .addMigrations(
                PulsezDatabase.MIGRATION_1_2,
                PulsezDatabase.MIGRATION_2_3,
                PulsezDatabase.MIGRATION_3_4,
                PulsezDatabase.MIGRATION_4_5,
                PulsezDatabase.MIGRATION_5_6,
                PulsezDatabase.MIGRATION_6_7,
            )
            .setJournalMode(RoomDatabase.JournalMode.TRUNCATE)
            .allowMainThreadQueries()
            .build()

    // ------------------------------------------------------------------
    // schema 快照：列（名/类型/NOT NULL/默认值/主键位）+ 索引（名/列）
    // ------------------------------------------------------------------

    private fun schemaSnapshot(db: PulsezDatabase): Map<String, String> {
        val raw = db.openHelper.writableDatabase
        return V1_TABLES.map { it.first }.plus("thread_member").associateWith { table ->
            val columns = columns(raw, table).values
                .sortedBy { it.name }
                .joinToString("|") {
                    "${it.name}:${it.type}:notNull=${it.notNull}:default=${it.defaultValue}:pk=${it.primaryKeyPosition}"
                }
            val indices = indexNames(raw, table).sorted().joinToString("|") { indexName ->
                val cols = indexColumns(raw, indexName).joinToString(",")
                "$indexName($cols)"
            }
            "columns=$columns;indices=$indices"
        }
    }

    private data class ColumnInfo(
        val name: String,
        val type: String,
        val notNull: Boolean,
        val defaultValue: String?,
        val primaryKeyPosition: Int,
    )

    private fun columns(db: PulsezDatabase, table: String): Map<String, ColumnInfo> =
        columns(db.openHelper.writableDatabase, table)

    private fun columns(db: SupportSQLiteDatabase, table: String): Map<String, ColumnInfo> {
        val result = LinkedHashMap<String, ColumnInfo>()
        db.query("PRAGMA table_info(`$table`)").use { cursor ->
            val nameIndex = cursor.getColumnIndexOrThrow("name")
            val typeIndex = cursor.getColumnIndexOrThrow("type")
            val notNullIndex = cursor.getColumnIndexOrThrow("notnull")
            val defaultIndex = cursor.getColumnIndexOrThrow("dflt_value")
            val pkIndex = cursor.getColumnIndexOrThrow("pk")
            while (cursor.moveToNext()) {
                val name = cursor.getString(nameIndex)
                result[name] = ColumnInfo(
                    name = name,
                    type = cursor.getString(typeIndex),
                    notNull = cursor.getInt(notNullIndex) != 0,
                    defaultValue = if (cursor.isNull(defaultIndex)) null else cursor.getString(defaultIndex),
                    primaryKeyPosition = cursor.getInt(pkIndex),
                )
            }
        }
        return result
    }

    private fun indexNames(db: PulsezDatabase, table: String): List<String> =
        indexNames(db.openHelper.writableDatabase, table)

    private fun indexNames(db: SupportSQLiteDatabase, table: String): List<String> {
        val names = mutableListOf<String>()
        db.query("PRAGMA index_list(`$table`)").use { cursor ->
            val nameIndex = cursor.getColumnIndexOrThrow("name")
            while (cursor.moveToNext()) names += cursor.getString(nameIndex)
        }
        return names
    }

    private fun indexColumns(db: PulsezDatabase, indexName: String): List<String> =
        indexColumns(db.openHelper.writableDatabase, indexName)

    private fun indexColumns(db: SupportSQLiteDatabase, indexName: String): List<String> {
        val result = mutableListOf<String>()
        db.query("PRAGMA index_info(`$indexName`)").use { cursor ->
            val nameIndex = cursor.getColumnIndexOrThrow("name")
            while (cursor.moveToNext()) result += cursor.getString(nameIndex)
        }
        return result
    }

    private companion object {
        const val MIGRATED_DB_NAME = "pulsez-migration-v1-v2-test.db"
        const val FRESH_DB_NAME = "pulsez-fresh-v2-test.db"

        const val TABLE_NAME_TOKEN = "\${TABLE_NAME}"

        const val CHARACTER_ID = "char-1"
        const val THREAD_ID = "thread-old"
        const val SEGMENT_ID = "seg-old"
        const val GROUP_ID = "group-new"

        /** v1 全表 CREATE + 索引，逐字取自 app/schemas/.../1.json。 */
        val V1_TABLES: List<Triple<String, String, List<String>>> = listOf(
            Triple(
                "character",
                "CREATE TABLE IF NOT EXISTS `\${TABLE_NAME}` (`id` TEXT NOT NULL, `name` TEXT NOT NULL, " +
                    "`avatarUri` TEXT, `persona` TEXT NOT NULL, `greeting` TEXT, `styleSamples` TEXT, " +
                    "`userName` TEXT, `systemPromptOverride` TEXT, `protocolEnabled` INTEGER NOT NULL, " +
                    "`sourceType` TEXT NOT NULL, `sourceRef` TEXT, `rawImported` TEXT, `emoAssetDir` TEXT, " +
                    "`createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, `deletedAt` INTEGER, " +
                    "PRIMARY KEY(`id`))",
                emptyList<String>(),
            ),
            Triple(
                "thread",
                "CREATE TABLE IF NOT EXISTS `\${TABLE_NAME}` (`id` TEXT NOT NULL, `characterId` TEXT NOT NULL, " +
                    "`title` TEXT, `createdAt` INTEGER NOT NULL, `lastMessageAt` INTEGER NOT NULL, " +
                    "`activeSegmentId` TEXT, `segmentOrdinal` INTEGER NOT NULL, `totalMessages` INTEGER NOT NULL, " +
                    "`totalTokens` INTEGER NOT NULL, `lastExtractAt` INTEGER NOT NULL, `archived` INTEGER NOT NULL, " +
                    "PRIMARY KEY(`id`), FOREIGN KEY(`characterId`) REFERENCES `character`(`id`) " +
                    "ON UPDATE NO ACTION ON DELETE CASCADE )",
                listOf(
                    "CREATE INDEX IF NOT EXISTS `index_thread_characterId` ON `\${TABLE_NAME}` (`characterId`)",
                    "CREATE INDEX IF NOT EXISTS `index_thread_lastMessageAt` ON `\${TABLE_NAME}` (`lastMessageAt`)",
                ),
            ),
            Triple(
                "message",
                "CREATE TABLE IF NOT EXISTS `\${TABLE_NAME}` (`id` TEXT NOT NULL, `threadId` TEXT NOT NULL, " +
                    "`segmentId` TEXT NOT NULL, `role` TEXT NOT NULL, `content` TEXT NOT NULL, `tokens` INTEGER NOT NULL, " +
                    "`emotion` TEXT, `status` TEXT NOT NULL, `draft` TEXT, `usageIn` INTEGER, `usageOut` INTEGER, " +
                    "`previousContent` TEXT, `createdAt` INTEGER NOT NULL, `deletedAt` INTEGER, PRIMARY KEY(`id`))",
                listOf(
                    "CREATE INDEX IF NOT EXISTS `index_message_threadId_createdAt` ON `\${TABLE_NAME}` (`threadId`, `createdAt`)",
                    "CREATE INDEX IF NOT EXISTS `index_message_segmentId` ON `\${TABLE_NAME}` (`segmentId`)",
                    "CREATE INDEX IF NOT EXISTS `index_message_status` ON `\${TABLE_NAME}` (`status`)",
                ),
            ),
            Triple(
                "segment",
                "CREATE TABLE IF NOT EXISTS `\${TABLE_NAME}` (`id` TEXT NOT NULL, `threadId` TEXT NOT NULL, " +
                    "`ordinal` INTEGER NOT NULL, `summary` TEXT, `styleNotes` TEXT, `firstMessageAt` INTEGER NOT NULL, " +
                    "`lastMessageAt` INTEGER NOT NULL, `tokenSum` INTEGER NOT NULL, `state` TEXT NOT NULL, " +
                    "`batonReason` TEXT, `createdAt` INTEGER NOT NULL, PRIMARY KEY(`id`))",
                listOf(
                    "CREATE INDEX IF NOT EXISTS `index_segment_threadId_ordinal` ON `\${TABLE_NAME}` (`threadId`, `ordinal`)",
                ),
            ),
            Triple(
                "memory_item",
                "CREATE TABLE IF NOT EXISTS `\${TABLE_NAME}` (`id` TEXT NOT NULL, `threadId` TEXT NOT NULL, " +
                    "`kind` TEXT NOT NULL, `key` TEXT, `value` TEXT NOT NULL, `confidence` REAL NOT NULL, " +
                    "`importance` INTEGER NOT NULL, `happenedAt` INTEGER, `dueAt` INTEGER, `validFrom` INTEGER NOT NULL, " +
                    "`validTo` INTEGER, `firstSeenAt` INTEGER NOT NULL, `lastSeenAt` INTEGER NOT NULL, " +
                    "`sourceSegmentId` TEXT, `sourceMessageIds` TEXT, `status` TEXT NOT NULL, `supersededBy` TEXT, " +
                    "PRIMARY KEY(`id`))",
                listOf(
                    "CREATE INDEX IF NOT EXISTS `index_memory_item_threadId_kind_status` ON `\${TABLE_NAME}` (`threadId`, `kind`, `status`)",
                    "CREATE INDEX IF NOT EXISTS `index_memory_item_threadId_lastSeenAt` ON `\${TABLE_NAME}` (`threadId`, `lastSeenAt`)",
                ),
            ),
            Triple(
                "thread_state",
                "CREATE TABLE IF NOT EXISTS `\${TABLE_NAME}` (`threadId` TEXT NOT NULL, `relationStage` TEXT, " +
                    "`nicknameByMe` TEXT, `nicknameForMe` TEXT, `mood` TEXT, `updatedAt` INTEGER NOT NULL, " +
                    "PRIMARY KEY(`threadId`))",
                emptyList<String>(),
            ),
            Triple(
                "chunk",
                "CREATE TABLE IF NOT EXISTS `\${TABLE_NAME}` (`id` TEXT NOT NULL, `threadId` TEXT NOT NULL, " +
                    "`segmentId` TEXT NOT NULL, `text` TEXT NOT NULL, `firstMessageAt` INTEGER NOT NULL, " +
                    "`lastMessageAt` INTEGER NOT NULL, `messageIds` TEXT NOT NULL, `dim` INTEGER NOT NULL, " +
                    "`vec` BLOB NOT NULL, `scale` REAL NOT NULL, `model` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, " +
                    "`deletedAt` INTEGER, PRIMARY KEY(`id`))",
                listOf(
                    "CREATE INDEX IF NOT EXISTS `index_chunk_threadId_createdAt` ON `\${TABLE_NAME}` (`threadId`, `createdAt`)",
                    "CREATE INDEX IF NOT EXISTS `index_chunk_threadId_lastMessageAt` ON `\${TABLE_NAME}` (`threadId`, `lastMessageAt`)",
                    "CREATE INDEX IF NOT EXISTS `index_chunk_threadId_model` ON `\${TABLE_NAME}` (`threadId`, `model`)",
                ),
            ),
            Triple(
                "chunk_term",
                "CREATE TABLE IF NOT EXISTS `\${TABLE_NAME}` (`term` TEXT NOT NULL, `chunkId` TEXT NOT NULL, " +
                    "`threadId` TEXT NOT NULL, `weight` INTEGER NOT NULL, PRIMARY KEY(`term`, `chunkId`))",
                listOf(
                    "CREATE INDEX IF NOT EXISTS `index_chunk_term_chunkId` ON `\${TABLE_NAME}` (`chunkId`)",
                ),
            ),
        )
    }
}
