package cc.pulsez.app.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import cc.pulsez.app.data.db.AttachmentEntity
import cc.pulsez.app.data.db.AttachmentStatus
import cc.pulsez.app.data.db.PulsezDatabase
import cc.pulsez.app.data.db.ThreadKind
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
 * v2 → v3（附件表）历史迁移测试，并升级到当前 v9。
 *
 * 与 [MigrationV1ToV2Test] 同一套「原生 SQLite 造旧库 + 真 Room 打开」模式：
 * `androidx.room:room-testing` 不在依赖里（本写作用域不允许改 build 文件），
 * 所以不引 `MigrationTestHelper`。作为替代：
 *
 * 1. 用**原生 SQLite** 按 v1 schema（SQL 逐字取自 `app/schemas/.../1.json`）造库并灌旧数据；
 * 2. 用**逐字复刻** `MIGRATION_1_2` 的 DDL 把库升到 v2（`Migration.migrate` 只吃
 *    `SupportSQLiteDatabase`，而原生库的官方包装类是 `internal`，见 [createV2Database]），
 *    再灌 v2 特有数据（群聊 thread / thread_member / speakerCharacterId / 角色维度记忆）；
 * 3. 让**真实 Room** 打开 v2 库沿生产 2→3→4→5→6→7→8→9 完整迁移链升级；本类仍保留 v2/v3 阶段的数据、
 *    附件结构与可写性断言，Room 同时校验最终 v9 schema。
 *
 * 复刻的 v1→v2 DDL 若与真实 v2 schema 有偏差，Room 迁移后的 `onValidateSchema`（以及本类
 * `migratedCurrentSchemaMatchesFreshCurrentSchema`）会直接失败，不会静默放过。
 *
 * Room 打开后会调用生成代码的 `onValidateSchema`，把迁移结果与编译期期望的当前 v9 表结构
 * 逐列（类型 / NOT NULL / 默认值 / 主键位置）、逐索引比对，不一致直接抛
 * `IllegalStateException("Migration didn't properly handle: ...")`。
 * Room 2.6.1 `RoomOpenHelper.kt` / `TableInfo.kt`（同 MigrationV1ToV2Test 的说明）。
 *
 * 覆盖：
 * - v2 旧数据（角色/会话/段/消息/记忆/群成员）升级后一条不丢；
 * - v1→v9 链式升级（v1 用户不先经过中间版本）；
 * - 迁移后的历史相关表与全新 v9 对照一致（含 attachment 两索引），Room 校验完整 v9 schema；
 * - 迁移后 attachment 表可写（草稿/绑定/软删），全新库直接 v9 也可用。
 *
 * 全程 Robolectric/JVM，离线，临时库文件跑完删除。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MigrationV2ToV3Test {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.deleteDatabase(MIGRATED_DB_NAME)
        context.deleteDatabase(CHAIN_DB_NAME)
        context.deleteDatabase(FRESH_DB_NAME)
    }

    @After
    fun tearDown() {
        context.deleteDatabase(MIGRATED_DB_NAME)
        context.deleteDatabase(CHAIN_DB_NAME)
        context.deleteDatabase(FRESH_DB_NAME)
    }

    // ------------------------------------------------------------------
    // 1. v2 历史数据不丢、v3 附件仍可写，最终 DB 版本 = 9
    // ------------------------------------------------------------------

    @Test
    fun migrationKeepsV2RowsAndCreatesUsableAttachmentTable() = runBlocking {
        createV2Database()
        val db = openMigrated()
        try {
            // 触发迁移 + 校验
            assertEquals("v2 旧库必须沿生产链升级到当前 v9", 9, db.openHelper.writableDatabase.version)

            // --- v1 时代就有的数据 ---
            val thread = db.threadDao().byId(THREAD_ID)
            assertNotNull("v2 用户的单聊 thread 不应丢", thread)
            assertEquals(ThreadKind.SINGLE, thread!!.kind)
            assertEquals(CHARACTER_ID, thread.characterId)
            assertEquals("旧 thread 计数不应被迁移改掉", 2, thread.totalMessages)

            val messages = db.messageDao().allForThread(THREAD_ID)
            assertEquals("v1 旧消息不应丢", 2, messages.size)
            assertEquals(listOf("在吗", "在的"), messages.map { it.content })
            assertTrue(
                "v1 旧消息的 speakerCharacterId 应保持 null",
                messages.all { it.speakerCharacterId == null },
            )

            val memoryRows = db.memoryDao().forOwner(THREAD_ID, null)
            assertEquals("v1 旧记忆不应丢", 1, memoryRows.size)
            assertEquals("她喜欢被叫阿淮", memoryRows.single().value)

            // --- v2 时代才有的数据（群聊 / 群成员 / 发言人 / 角色维度记忆）---
            val group = db.threadDao().byId(GROUP_ID)
            assertNotNull("v2 群聊 thread 不应丢", group)
            assertEquals(ThreadKind.GROUP, group!!.kind)
            assertNull("群聊 characterId 应保持 null", group.characterId)
            assertEquals(
                listOf(CHARACTER_ID, CHARACTER_2_ID),
                db.threadMemberDao().membersOf(GROUP_ID).map { it.characterId },
            )
            assertEquals(
                listOf("gm1"),
                db.messageDao().bySpeaker(GROUP_ID, CHARACTER_2_ID).map { it.id },
            )
            assertEquals(
                "v2 角色维度记忆不应丢",
                1,
                db.memoryDao().forOwner(GROUP_ID, CHARACTER_2_ID).size,
            )

            // --- v3 新表：真实仓储路径可写 ---
            val draft = AttachmentEntity(
                id = "att-1",
                messageId = null,
                kind = "IMAGE",
                mime = "image/jpeg",
                localPath = "attachments/att-1.jpg",
                fileName = "截图.jpg",
                sizeBytes = 1_024L,
                width = 640,
                height = 480,
                status = AttachmentStatus.DRAFT,
                errorKind = null,
                attempts = 0,
                remoteFileId = null,
                expiresAt = null,
                deletedAt = null,
                createdAt = 10_000L,
            )
            db.attachmentDao().upsert(draft)
            assertEquals(listOf("att-1"), db.attachmentDao().listDraft().map { it.id })

            // 草稿 → 绑定到 v1 旧消息；旧消息仍然在
            assertEquals(1, db.attachmentDao().bindToMessage("att-1", "m1"))
            assertEquals(listOf("att-1"), db.attachmentDao().byMessageId("m1").map { it.id })
            assertEquals(1, db.attachmentDao().countByMessage("m1"))
            assertEquals(listOf("att-1"), db.attachmentDao().idsInUse())

            // 状态机 + 软删
            assertEquals(1, db.attachmentDao().updateStatus("att-1", AttachmentStatus.READY, null))
            assertEquals(AttachmentStatus.READY, db.attachmentDao().byId("att-1")!!.status)
            assertEquals(1, db.attachmentDao().softDeleteByMessageId("m1", 20_000L))
            assertTrue("软删后不应再出现在消息附件里", db.attachmentDao().byMessageId("m1").isEmpty())
            assertTrue("软删后不在 keep 集合里", db.attachmentDao().idsInUse().isEmpty())
            assertEquals(1, db.attachmentDao().clearSoftDeleted())
            assertNull("物理清理后行应消失", db.attachmentDao().byId("att-1"))
        } finally {
            db.close()
        }
    }

    // ------------------------------------------------------------------
    // 2. 结构：历史相关表与当前安装一致，Room 同时完整校验当前 v9 schema
    // ------------------------------------------------------------------

    @Test
    fun migratedCurrentSchemaMatchesFreshCurrentSchema() = runBlocking {
        createV2Database()

        val migrated = openMigrated()
        val fresh = Room.databaseBuilder(context, PulsezDatabase::class.java, FRESH_DB_NAME)
            // Robolectric 的 SQLite 实现不必依赖 WAL；TRUNCATE 让文件库行为更确定
            .setJournalMode(RoomDatabase.JournalMode.TRUNCATE)
            .allowMainThreadQueries()
            .build()
        try {
            migrated.openHelper.writableDatabase
            fresh.openHelper.writableDatabase
            assertEquals("全新安装的库版本必须是当前 v9", 9, fresh.openHelper.writableDatabase.version)

            assertEquals(
                "历史相关表必须与当前库一致（Room 已完整校验最终 v9 schema）",
                schemaSnapshot(fresh),
                schemaSnapshot(migrated),
            )

            // 逐条断言 的 attachment 契约（schema 对比失败时便于定位）
            val attachmentColumns = columns(migrated, "attachment")
            assertEquals("attachment 必须有 id 主键", 1, attachmentColumns.getValue("id").primaryKeyPosition)
            assertTrue("id 必须 NOT NULL", attachmentColumns.getValue("id").notNull)
            assertFalse("messageId 必须可空（草稿期没有消息）", attachmentColumns.getValue("messageId").notNull)
            assertTrue("kind 必须 NOT NULL", attachmentColumns.getValue("kind").notNull)
            assertTrue("mime 必须 NOT NULL", attachmentColumns.getValue("mime").notNull)
            assertTrue("localPath 必须 NOT NULL", attachmentColumns.getValue("localPath").notNull)
            assertFalse("fileName 可空", attachmentColumns.getValue("fileName").notNull)
            assertTrue("sizeBytes 必须 NOT NULL", attachmentColumns.getValue("sizeBytes").notNull)
            assertFalse("width 可空", attachmentColumns.getValue("width").notNull)
            assertFalse("height 可空", attachmentColumns.getValue("height").notNull)
            assertTrue("status 必须 NOT NULL", attachmentColumns.getValue("status").notNull)
            assertFalse("errorKind 可空", attachmentColumns.getValue("errorKind").notNull)
            assertTrue("attempts 必须 NOT NULL", attachmentColumns.getValue("attempts").notNull)
            assertFalse("attempts 不应带 DB 默认值（实体 Kotlin 默认值 0 由 Room 绑定）", attachmentColumns.getValue("attempts").defaultValue != null)
            assertFalse("remoteFileId 可空", attachmentColumns.getValue("remoteFileId").notNull)
            assertFalse("expiresAt 可空", attachmentColumns.getValue("expiresAt").notNull)
            assertFalse("deletedAt 可空", attachmentColumns.getValue("deletedAt").notNull)
            assertTrue("createdAt 必须 NOT NULL", attachmentColumns.getValue("createdAt").notNull)

            val attachmentIndices = indexNames(migrated, "attachment")
            assertTrue(
                "attachment 必须有 messageId 索引",
                attachmentIndices.contains("index_attachment_messageId"),
            )
            assertTrue(
                "attachment 必须有 status 索引",
                attachmentIndices.contains("index_attachment_status"),
            )
        } finally {
            migrated.close()
            fresh.close()
        }
    }

    // ------------------------------------------------------------------
    // 3. v1 → current 链式升级：v1 用户直接升到 v9，数据不丢
    // ------------------------------------------------------------------

    @Test
    fun v1ToV9ChainKeepsDataAndCreatesUsableAttachmentTable() = runBlocking {
        createV1Database(CHAIN_DB_NAME)
        val db = Room.databaseBuilder(context, PulsezDatabase::class.java, CHAIN_DB_NAME)
            .addMigrations(
                PulsezDatabase.MIGRATION_1_2,
                PulsezDatabase.MIGRATION_2_3,
                PulsezDatabase.MIGRATION_3_4,
                PulsezDatabase.MIGRATION_4_5,
                PulsezDatabase.MIGRATION_5_6,
                PulsezDatabase.MIGRATION_6_7,
                PulsezDatabase.MIGRATION_7_8,
                PulsezDatabase.MIGRATION_8_9,
            )
            .setJournalMode(RoomDatabase.JournalMode.TRUNCATE)
            .allowMainThreadQueries()
            .build()
        try {
            assertEquals("链式迁移后 user_version 必须是当前 v9", 9, db.openHelper.writableDatabase.version)
            assertEquals("v1 角色不应丢", CHARACTER_ID, db.characterDao().byId(CHARACTER_ID)!!.id)
            assertEquals(ThreadKind.SINGLE, db.threadDao().byId(THREAD_ID)!!.kind)
            assertEquals(2, db.messageDao().allForThread(THREAD_ID).size)
            assertEquals(1, db.memoryDao().forOwner(THREAD_ID, null).size)

            db.attachmentDao().upsert(
                AttachmentEntity(
                    id = "chain-att",
                    messageId = "m1",
                    kind = "IMAGE",
                    mime = "image/png",
                    localPath = "attachments/chain-att.png",
                    fileName = null,
                    sizeBytes = 2L,
                    width = null,
                    height = null,
                    status = AttachmentStatus.READY,
                    errorKind = null,
                    attempts = 0,
                    remoteFileId = null,
                    expiresAt = null,
                    deletedAt = null,
                    createdAt = 11_000L,
                ),
            )
            assertEquals(listOf("chain-att"), db.attachmentDao().byMessageId("m1").map { it.id })
        } finally {
            db.close()
        }
    }

    // ------------------------------------------------------------------
    // 4. 全新安装直接当前 v9：onCreate 建出 attachment 表且可用
    // ------------------------------------------------------------------

    @Test
    fun freshCurrentDatabaseHasAttachmentTableAndWorks() = runBlocking {
        val db = Room.databaseBuilder(context, PulsezDatabase::class.java, FRESH_DB_NAME)
            .setJournalMode(RoomDatabase.JournalMode.TRUNCATE)
            .allowMainThreadQueries()
            .build()
        try {
            assertEquals(9, db.openHelper.writableDatabase.version)
            assertTrue("全新当前 v9 库必须有 attachment 表", columns(db, "attachment").isNotEmpty())
            db.attachmentDao().upsert(
                AttachmentEntity(
                    id = "fresh-att",
                    messageId = null,
                    kind = "IMAGE",
                    mime = "image/jpeg",
                    localPath = "attachments/fresh-att.jpg",
                    fileName = "a.jpg",
                    sizeBytes = 3L,
                    width = 1,
                    height = 1,
                    status = AttachmentStatus.DRAFT,
                    errorKind = null,
                    attempts = 0,
                    remoteFileId = null,
                    expiresAt = null,
                    deletedAt = null,
                    createdAt = 12_000L,
                ),
            )
            assertEquals(listOf("fresh-att"), db.attachmentDao().listDraft().map { it.id })
        } finally {
            db.close()
        }
    }

    // ------------------------------------------------------------------
    // 旧库构造
    // ------------------------------------------------------------------

    /**
     * 造 v2 库：v1 schema + v1→v2 的 DDL + v2 特有数据。
     *
     * 为什么手写 v1→v2 的 DDL 而不用生产 [PulsezDatabase.MIGRATION_1_2]：
     * `Migration.migrate` 只接受 `SupportSQLiteDatabase`，而原生 [`SQLiteDatabase`] 的官方包装类
     * `androidx.sqlite.db.framework.FrameworkSQLiteDatabase` 是 sqlite-framework 的 `internal` 类
     * （Kotlin 里不可见），本工作包写作用域又不允许加 `room-testing`。所以这里**逐字复刻**
     * MIGRATION_1_2 的 DDL（第 2 个测试会用「迁移后历史相关表 == 全新 v9 中对应表」兜底：如果这段
     * 复刻和真实 v2 schema 有偏差，Room 的 `onValidateSchema` 会直接失败）。
     *
     * v2→v3 及后续迁移仍然跑生产代码 [PulsezDatabase.MIGRATION_2_3] 等完整链——测试要验的正是它们。
     */
    private fun createV2Database() {
        val dbFile = context.getDatabasePath(MIGRATED_DB_NAME)
        dbFile.parentFile?.mkdirs()
        val raw = SQLiteDatabase.openOrCreateDatabase(dbFile, null)
        try {
            V1_TABLES.forEach { (table, createSql, indexSqls) ->
                raw.execSQL(createSql.replace(TABLE_NAME_TOKEN, table))
                indexSqls.forEach { raw.execSQL(it.replace(TABLE_NAME_TOKEN, table)) }
            }
            seedV1Rows(raw)
            upgradeV1SchemaToV2Raw(raw)
            seedV2Rows(raw)
            raw.version = 2
        } finally {
            raw.close()
        }
    }

    /** 逐字复刻 `PulsezDatabase.MIGRATION_1_2` 的 DDL（见 [createV2Database] 的说明）。 */
    private fun upgradeV1SchemaToV2Raw(db: SQLiteDatabase) {
        db.execSQL("ALTER TABLE `message` ADD COLUMN `speakerCharacterId` TEXT")
        db.execSQL("ALTER TABLE `memory_item` ADD COLUMN `characterId` TEXT")
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `thread_member` (" +
                "`threadId` TEXT NOT NULL, " +
                "`characterId` TEXT NOT NULL, " +
                "`ordinal` INTEGER NOT NULL, " +
                "`joinedAt` INTEGER NOT NULL, " +
                "PRIMARY KEY(`threadId`, `characterId`))",
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_thread_member_characterId` " +
                "ON `thread_member` (`characterId`)",
        )
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `thread_new` (" +
                "`id` TEXT NOT NULL, " +
                "`characterId` TEXT, " +
                "`title` TEXT, " +
                "`createdAt` INTEGER NOT NULL, " +
                "`lastMessageAt` INTEGER NOT NULL, " +
                "`activeSegmentId` TEXT, " +
                "`segmentOrdinal` INTEGER NOT NULL, " +
                "`totalMessages` INTEGER NOT NULL, " +
                "`totalTokens` INTEGER NOT NULL, " +
                "`lastExtractAt` INTEGER NOT NULL, " +
                "`archived` INTEGER NOT NULL, " +
                "`kind` TEXT NOT NULL DEFAULT 'SINGLE', " +
                "PRIMARY KEY(`id`), " +
                "FOREIGN KEY(`characterId`) REFERENCES `character`(`id`) " +
                "ON UPDATE NO ACTION ON DELETE CASCADE)",
        )
        db.execSQL(
            "INSERT INTO `thread_new` (" +
                "`id`, `characterId`, `title`, `createdAt`, `lastMessageAt`, " +
                "`activeSegmentId`, `segmentOrdinal`, `totalMessages`, `totalTokens`, " +
                "`lastExtractAt`, `archived`, `kind`) " +
                "SELECT `id`, `characterId`, `title`, `createdAt`, `lastMessageAt`, " +
                "`activeSegmentId`, `segmentOrdinal`, `totalMessages`, `totalTokens`, " +
                "`lastExtractAt`, `archived`, 'SINGLE' FROM `thread`",
        )
        db.execSQL("DROP TABLE `thread`")
        db.execSQL("ALTER TABLE `thread_new` RENAME TO `thread`")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_thread_characterId` ON `thread` (`characterId`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_thread_lastMessageAt` ON `thread` (`lastMessageAt`)")
    }

    /** v2 特有数据：群聊、群成员、带发言人的消息、角色维度记忆。 */
    private fun seedV2Rows(raw: SQLiteDatabase) {
        raw.execSQL(
            "INSERT INTO `character` (" +
                "`id`, `name`, `avatarUri`, `persona`, `greeting`, `styleSamples`, `userName`, " +
                "`systemPromptOverride`, `protocolEnabled`, `sourceType`, `sourceRef`, `rawImported`, " +
                "`emoAssetDir`, `createdAt`, `updatedAt`, `deletedAt`) VALUES (" +
                "'$CHARACTER_2_ID', '阿澈', NULL, '话多、爱笑', NULL, NULL, NULL, NULL, 1, 'MANUAL', " +
                "NULL, NULL, NULL, 1200, 1200, NULL)",
        )
        raw.execSQL(
            "INSERT INTO `thread` (" +
                "`id`, `characterId`, `title`, `createdAt`, `lastMessageAt`, `activeSegmentId`, " +
                "`segmentOrdinal`, `totalMessages`, `totalTokens`, `lastExtractAt`, `archived`, `kind`) VALUES (" +
                "'$GROUP_ID', NULL, '我们仨', 3000, 5000, NULL, 0, 1, 5, 0, 0, '${ThreadKind.GROUP}')",
        )
        raw.execSQL(
            "INSERT INTO `thread_member` (`threadId`, `characterId`, `ordinal`, `joinedAt`) VALUES " +
                "('$GROUP_ID', '$CHARACTER_ID', 0, 3000), ('$GROUP_ID', '$CHARACTER_2_ID', 1, 3000)",
        )
        raw.execSQL(
            "INSERT INTO `message` (" +
                "`id`, `threadId`, `segmentId`, `role`, `content`, `tokens`, `emotion`, `status`, `draft`, " +
                "`usageIn`, `usageOut`, `previousContent`, `createdAt`, `deletedAt`, `speakerCharacterId`) VALUES (" +
                "'gm1', '$GROUP_ID', 'seg-group', 'assistant', '我在呢', 3, NULL, 'OK', NULL, NULL, NULL, NULL, " +
                "4000, NULL, '$CHARACTER_2_ID')",
        )
        raw.execSQL(
            "INSERT INTO `memory_item` (" +
                "`id`, `threadId`, `kind`, `key`, `value`, `confidence`, `importance`, `happenedAt`, " +
                "`dueAt`, `validFrom`, `validTo`, `firstSeenAt`, `lastSeenAt`, `sourceSegmentId`, " +
                "`sourceMessageIds`, `status`, `supersededBy`, `characterId`) VALUES (" +
                "'gmem1', '$GROUP_ID', 'FACT', '口头禅', '阿澈爱说「好耶」', 0.8, 3, NULL, NULL, " +
                "4000, NULL, 4000, 4000, NULL, 'gm1', 'ACTIVE', NULL, '$CHARACTER_2_ID')",
        )
    }

    /** 造纯 v1 库（链式升级测试用；数据与 MigrationV1ToV2Test 一致的最小集）。 */
    private fun createV1Database(name: String) {
        val dbFile = context.getDatabasePath(name)
        dbFile.parentFile?.mkdirs()
        val db = SQLiteDatabase.openOrCreateDatabase(dbFile, null)
        try {
            V1_TABLES.forEach { (table, createSql, indexSqls) ->
                db.execSQL(createSql.replace(TABLE_NAME_TOKEN, table))
                indexSqls.forEach { db.execSQL(it.replace(TABLE_NAME_TOKEN, table)) }
            }
            seedV1Rows(db)
            db.version = 1
        } finally {
            db.close()
        }
    }

    /** v1 旧数据：一个角色、一条单聊 thread、一个 OPEN 段、两条消息、一条记忆。 */
    private fun seedV1Rows(db: SQLiteDatabase) {
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
    }

    private fun openMigrated(): PulsezDatabase =
        Room.databaseBuilder(context, PulsezDatabase::class.java, MIGRATED_DB_NAME)
            .addMigrations(
                PulsezDatabase.MIGRATION_1_2,
                PulsezDatabase.MIGRATION_2_3,
                PulsezDatabase.MIGRATION_3_4,
                PulsezDatabase.MIGRATION_4_5,
                PulsezDatabase.MIGRATION_5_6,
                PulsezDatabase.MIGRATION_6_7,
                PulsezDatabase.MIGRATION_7_8,
                PulsezDatabase.MIGRATION_8_9,
            )
            .setJournalMode(RoomDatabase.JournalMode.TRUNCATE)
            .allowMainThreadQueries()
            .build()

    // ------------------------------------------------------------------
    // schema 快照：列（名/类型/NOT NULL/默认值/主键位）+ 索引（名/列）
    // ------------------------------------------------------------------

    private fun schemaSnapshot(db: PulsezDatabase): Map<String, String> {
        val raw = db.openHelper.writableDatabase
        return ALL_TABLES.associateWith { table ->
            val columnText = columns(raw, table).values
                .sortedBy { it.name }
                .joinToString("|") {
                    "${it.name}:${it.type}:notNull=${it.notNull}:default=${it.defaultValue}:pk=${it.primaryKeyPosition}"
                }
            val indices = indexNames(raw, table).sorted().joinToString("|") { indexName ->
                val cols = indexColumns(raw, indexName).joinToString(",")
                "$indexName($cols)"
            }
            "columns=$columnText;indices=$indices"
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
        const val MIGRATED_DB_NAME = "pulsez-migration-v2-v3-test.db"
        const val CHAIN_DB_NAME = "pulsez-chain-v1-v3-test.db"
        const val FRESH_DB_NAME = "pulsez-fresh-v3-test.db"

        const val TABLE_NAME_TOKEN = "\${TABLE_NAME}"

        const val CHARACTER_ID = "char-1"
        const val CHARACTER_2_ID = "char-2"
        const val THREAD_ID = "thread-old"
        const val SEGMENT_ID = "seg-old"
        const val GROUP_ID = "group-v2"

        /** 参与 schema 对比的全部表（v1 的 8 张 + v2 thread_member + v3 attachment）。 */
        val ALL_TABLES: List<String> = listOf(
            "character",
            "thread",
            "message",
            "segment",
            "memory_item",
            "thread_state",
            "chunk",
            "chunk_term",
            "thread_member",
            "attachment",
        )

        /** v1 全表 CREATE + 索引，逐字取自 app/schemas/.../1.json（同 MigrationV1ToV2Test）。 */
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
