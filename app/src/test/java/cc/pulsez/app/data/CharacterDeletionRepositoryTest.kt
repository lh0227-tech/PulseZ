package cc.pulsez.app.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import cc.pulsez.app.core.ImportIndexer
import cc.pulsez.app.data.attachments.AttachmentStore
import cc.pulsez.app.data.db.AttachmentKind
import cc.pulsez.app.data.db.ChunkEntity
import cc.pulsez.app.data.db.ChunkTermEntity
import cc.pulsez.app.data.db.ImportBatchEntity
import cc.pulsez.app.data.db.ImportBatchGroupEntity
import cc.pulsez.app.data.db.ImportBatchWindowEntity
import cc.pulsez.app.data.db.ImportSourceSessionEntity
import cc.pulsez.app.data.db.MemoryItemEntity
import cc.pulsez.app.data.db.PulsezDatabase
import cc.pulsez.app.data.db.ThreadStateEntity
import cc.pulsez.app.data.repo.AttachmentRepository
import cc.pulsez.app.data.repo.BackupRestoreRepository
import cc.pulsez.app.data.repo.CharacterDeletionRepository
import cc.pulsez.app.data.repo.CharacterRepository
import cc.pulsez.app.data.repo.ChatRepository
import cc.pulsez.app.data.repo.ImportRepository
import cc.pulsez.app.data.repo.RestoreResult
import cc.pulsez.app.data.ExportService
import cc.pulsez.app.importer.BackupInspection
import cc.pulsez.app.importer.BackupParser
import cc.pulsez.app.importer.Source
import cc.pulsez.app.ui.group.DELETED_GROUP_SPEAKER_LABEL
import cc.pulsez.app.ui.group.groupSpeakerDisplayName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.IOException

/** Offline Room coverage for role-scoped deletion, shared group preservation, and retry safety. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CharacterDeletionRepositoryTest {

    private lateinit var db: PulsezDatabase
    private lateinit var characters: CharacterRepository
    private lateinit var chat: ChatRepository
    private lateinit var storeDir: File
    private lateinit var store: AttachmentStore

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, PulsezDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        PulsezDatabase.installRoleDeletionGuards(db.openHelper.writableDatabase)
        characters = CharacterRepository(db)
        chat = ChatRepository(db)
        storeDir = File(context.cacheDir, "role-delete-${System.nanoTime()}")
        storeDir.mkdirs()
        store = AttachmentStore(storeDir)
    }

    @After
    fun tearDown() {
        db.close()
        storeDir.deleteRecursively()
    }

    @Test
    fun deletesEverySingleChatDependencyAndOnlyThatRoleFromSharedGroupAndBatch() = runBlocking {
        val (deletedRole, deletedThread) = characters.createWithThread("要删除", "私有人设")
        val (keptRole, keptThread) = characters.createWithThread("要保留", "保留人设")
        val group = chat.createGroupThread(listOf(deletedRole.id, keptRole.id), "共享群")
        val singleMessage = chat.append(
            deletedThread.id,
            deletedThread.activeSegmentId!!,
            "user",
            "单聊内容",
            4,
        )
        val deletedSpeakerMessage = chat.append(
            group.id,
            group.activeSegmentId!!,
            "assistant",
            "群聊历史",
            4,
            createdAt = 10L,
            speakerCharacterId = deletedRole.id,
        )
        val sharedUserMessage = chat.append(group.id, group.activeSegmentId!!, "user", "共享内容", 4, createdAt = 20L)

        db.memoryDao().insertAll(
            listOf(
                memory("single-memory", deletedThread.id, null),
                memory("group-memory-deleted", group.id, deletedRole.id),
                memory("group-memory-kept", group.id, keptRole.id),
            ),
        )
        db.threadStateDao().upsert(ThreadStateEntity(deletedThread.id, "close", null, null, null, 1L))
        db.threadStateDao().upsert(ThreadStateEntity(group.id, "shared", null, null, null, 1L))
        val singleChunk = chunk("single-chunk", deletedThread.id, deletedThread.activeSegmentId!!, singleMessage.id)
        val groupChunk = chunk("group-chunk", group.id, group.activeSegmentId!!, deletedSpeakerMessage.id)
        db.chunkDao().insertAll(listOf(singleChunk, groupChunk))
        db.chunkDao().insertTerms(
            listOf(
                ChunkTermEntity("private", singleChunk.id, deletedThread.id, 5),
                ChunkTermEntity("shared", groupChunk.id, group.id, 5),
            ),
        )

        val attachmentRepo = AttachmentRepository(db)
        val singleAttachment = attachmentRepo.createDraft(
            store,
            AttachmentKind.IMAGE,
            "image/jpeg",
            byteArrayOf(1, 2, 3),
            id = "single-attachment",
            ownerThreadId = deletedThread.id,
        )
        attachmentRepo.markReady(singleAttachment.id)
        attachmentRepo.bindToMessage(listOf(singleAttachment.id), singleMessage.id)
        val groupAttachment = attachmentRepo.createDraft(
            store,
            AttachmentKind.IMAGE,
            "image/jpeg",
            byteArrayOf(4, 5, 6),
            id = "group-attachment",
            ownerThreadId = group.id,
        )
        attachmentRepo.markReady(groupAttachment.id)
        attachmentRepo.bindToMessage(listOf(groupAttachment.id), sharedUserMessage.id)
        val legacyUnownedDraft = attachmentRepo.createDraft(
            store,
            AttachmentKind.IMAGE,
            "image/jpeg",
            byteArrayOf(9, 10, 11),
            id = "legacy-unowned-draft",
        )
        attachmentRepo.markReady(legacyUnownedDraft.id)

        seedSameBatchForBothRoles(deletedRole.id, deletedThread.id, keptRole.id, keptThread.id)

        val result = CharacterDeletionRepository(db).deleteCharacters(listOf(deletedRole.id), store, at = 50L)

        assertEquals(1, result.deletedCount)
        assertEquals(0, result.pendingAttachmentFiles)
        assertNull(db.characterDao().byId(deletedRole.id))
        assertNotNull(db.characterDao().byId(keptRole.id))
        assertNull(db.threadDao().byId(deletedThread.id))
        assertNotNull(db.threadDao().byId(keptThread.id))
        assertTrue(db.messageDao().allForThread(deletedThread.id).isEmpty())
        assertEquals(listOf(deletedSpeakerMessage.id, sharedUserMessage.id), db.messageDao().allForThread(group.id).map { it.id })
        assertEquals(listOf(keptRole.id), db.threadMemberDao().membersOf(group.id).map { it.characterId })
        assertEquals(DELETED_GROUP_SPEAKER_LABEL, groupSpeakerDisplayName(db.characterDao().byId(deletedRole.id)?.name))
        assertTrue(db.memoryDao().allForThread(deletedThread.id).isEmpty())
        assertTrue(db.memoryDao().forOwner(group.id, deletedRole.id).isEmpty())
        assertEquals(listOf("group-memory-kept"), db.memoryDao().forOwner(group.id, keptRole.id).map { it.id })
        assertNull(db.threadStateDao().get(deletedThread.id))
        assertNotNull(db.threadStateDao().get(group.id))
        assertTrue(db.chunkDao().forSegment(deletedThread.activeSegmentId!!).isEmpty())
        assertEquals(listOf(groupChunk.id), db.chunkDao().forSegment(group.activeSegmentId!!).map { it.id })
        assertTrue(db.chunkDao().searchTerms(deletedThread.id, listOf("private"), 10).isEmpty())
        assertEquals(1, db.chunkDao().searchTerms(group.id, listOf("shared"), 10).size)
        assertNull(db.attachmentDao().byId(singleAttachment.id))
        assertNotNull(db.attachmentDao().byId(groupAttachment.id))
        assertNull("旧版无法归属的草稿保留，不猜测属于已删角色", db.attachmentDao().byId(legacyUnownedDraft.id)?.messageId)
        assertFalse(store.exists(singleAttachment.localPath))
        assertTrue(store.exists(groupAttachment.localPath))
        assertTrue(store.exists(legacyUnownedDraft.localPath))
        assertTrue(
            "不能在删除后把旧草稿迟到绑定到已删单聊",
            runCatching { attachmentRepo.bindToMessage(listOf(legacyUnownedDraft.id), singleMessage.id) }.isFailure,
        )

        val batch = requireNotNull(db.importBatchDao().batch("shared-batch"))
        assertEquals("COMPLETE", batch.status)
        assertTrue(db.importBatchDao().groupsForCharacter(deletedRole.id).isEmpty())
        assertEquals(listOf("group-kept"), db.importBatchDao().groupsForCharacter(keptRole.id).map { it.id })
        assertEquals(listOf("source-kept"), db.importBatchDao().sourcesForBatch(batch.id).map { it.id })
        val keptWindows = db.importBatchDao().windows(batch.id)
        assertEquals(listOf("session-kept"), keptWindows.map { it.sourceSessionId })
        assertEquals(listOf("group-kept"), keptWindows.map { it.groupId })

        assertTrue("同一角色 id 不能由迟到写入复活", runCatching { db.characterDao().upsert(deletedRole) }.isFailure)
        assertTrue(
            "共享群聊中不能再落已删除角色的迟到发言",
            runCatching {
                db.messageDao().upsert(deletedSpeakerMessage.copy(id = "late-speaker-message", createdAt = 60L))
            }.isFailure,
        )
        assertTrue(
            "共享群聊不能再加回已删除成员",
            runCatching {
                db.threadMemberDao().insertAll(
                    listOf(cc.pulsez.app.data.db.ThreadMemberEntity(group.id, deletedRole.id, 0, 60L)),
                )
            }.isFailure,
        )
    }

    @Test
    fun failedFileRemovalLeavesARecordAndCanBeRetried() = runBlocking {
        val (role, thread) = characters.createWithThread("带草稿", "人设")
        val attachmentRepo = AttachmentRepository(db)
        val draft = attachmentRepo.createDraft(
            store,
            AttachmentKind.IMAGE,
            "image/jpeg",
            byteArrayOf(7, 8, 9),
            id = "retry-attachment",
            ownerThreadId = thread.id,
        )
        attachmentRepo.markReady(draft.id)

        val failing = CharacterDeletionRepository(db) { _, _ -> throw IOException("test file lock") }
        val result = failing.deleteCharacters(listOf(role.id), store, at = 100L)

        assertEquals(1, result.deletedCount)
        assertEquals(1, result.pendingAttachmentFiles)
        assertNull(db.attachmentDao().byId(draft.id))
        assertTrue(store.exists(draft.localPath))
        assertEquals(1, db.pendingAttachmentDeleteDao().all().size)
        assertEquals(1, db.pendingAttachmentDeleteDao().all().single().attempts)

        assertEquals(0, CharacterDeletionRepository(db).retryPendingAttachmentFiles(store))
        assertFalse(store.exists(draft.localPath))
        assertTrue(db.pendingAttachmentDeleteDao().all().isEmpty())
    }

    @Test
    fun oldExportIsIndependentAndRestoreUsesANewRoleId() = runBlocking {
        val original = cc.pulsez.app.data.db.CharacterEntity(
            id = "backup-role-id",
            name = "备份中的 TA",
            avatarUri = null,
            persona = "备份人设",
            greeting = null,
            styleSamples = null,
            userName = null,
            systemPromptOverride = null,
            protocolEnabled = true,
            sourceType = "MANUAL",
            sourceRef = null,
            rawImported = null,
            emoAssetDir = null,
            createdAt = 1L,
            updatedAt = 2L,
        )
        db.characterDao().upsert(original)
        val oldBackupJson = ExportService.buildJson(db)
        val inspection = BackupParser.inspect(oldBackupJson, Source.FILE)
        assertTrue(inspection is BackupInspection.Preview)
        val oldBackup = BackupParser.parseBackup(oldBackupJson)
        assertEquals("backup-role-id", oldBackup.characters.single().sourceId)

        CharacterDeletionRepository(db).deleteCharacters(listOf(original.id), store, at = 500L)
        assertTrue(db.characterDao().all().isEmpty())
        assertTrue("旧导出内容不被应用内删除改写", oldBackup.characters.any { it.sourceId == original.id })

        val indexer = ImportIndexer(loadMessages = { emptyList() }, saveChunks = { _, _ -> })
        val restore = BackupRestoreRepository(db, indexer, Dispatchers.Unconfined).restore(oldBackup)
        assertTrue(restore is RestoreResult.Restored)
        val restored = db.characterDao().all().single()
        assertEquals(original.name, restored.name)
        assertNotEquals("角色同名恢复，但不能复用已删除 id", original.id, restored.id)
    }

    private suspend fun seedSameBatchForBothRoles(
        deletedCharacterId: String,
        deletedThreadId: String,
        keptCharacterId: String,
        keptThreadId: String,
    ) {
        db.importBatchDao().upsertBatch(
            ImportBatchEntity("shared-batch", "fingerprint", "source.zip", 1L, "COMPLETE", "DONE"),
        )
        db.importBatchDao().upsertGroups(
            listOf(
                batchGroup("group-deleted", deletedCharacterId, deletedThreadId),
                batchGroup("group-kept", keptCharacterId, keptThreadId),
            ),
        )
        db.importBatchDao().upsertWindows(
            listOf(
                batchWindow("session-deleted", "group-deleted"),
                batchWindow("session-kept", "group-kept"),
            ),
        )
        db.importBatchDao().upsertSources(
            listOf(
                source("source-deleted", deletedCharacterId, deletedThreadId, "session-deleted", "segment-deleted"),
                source("source-kept", keptCharacterId, keptThreadId, "session-kept", "segment-kept"),
            ),
        )
    }

    private fun batchGroup(id: String, characterId: String, threadId: String) = ImportBatchGroupEntity(
        id = id,
        batchId = "shared-batch",
        targetCharacterId = characterId,
        targetThreadId = threadId,
        isNewCharacter = false,
        name = id,
        persona = null,
        status = ImportRepository.GROUP_SUCCESS,
        memoryFingerprint = null,
        beforeLastMessageAt = null,
        beforeLastExtractAt = null,
        beforeCharacterUpdatedAt = null,
        importLastMessageAt = null,
        importMessageCount = 1,
        errorCode = null,
    )

    private fun batchWindow(sourceSessionId: String, groupId: String) = ImportBatchWindowEntity(
        batchId = "shared-batch",
        sourceSessionId = sourceSessionId,
        entryName = "$sourceSessionId.json",
        title = sourceSessionId,
        sourceStartedAt = 1L,
        messageCount = 1,
        damageCode = null,
        groupId = groupId,
        status = ImportRepository.WINDOW_IMPORTED,
        errorCode = null,
    )

    private fun source(
        id: String,
        characterId: String,
        threadId: String,
        sourceSessionId: String,
        segmentId: String,
    ) = ImportSourceSessionEntity(
        id = id,
        batchId = "shared-batch",
        targetCharacterId = characterId,
        targetThreadId = threadId,
        sourceSessionId = sourceSessionId,
        title = sourceSessionId,
        sourceStartedAt = 1L,
        messageCount = 1,
        segmentId = segmentId,
        indexStatus = ImportRepository.INDEX_DONE,
    )

    private fun memory(id: String, threadId: String, ownerId: String?) = MemoryItemEntity(
        id = id,
        threadId = threadId,
        kind = "FACT",
        key = id,
        value = id,
        confidence = 1f,
        importance = 1,
        happenedAt = null,
        dueAt = null,
        validFrom = 1L,
        validTo = null,
        firstSeenAt = 1L,
        lastSeenAt = 1L,
        sourceSegmentId = null,
        sourceMessageIds = null,
        status = "ACTIVE",
        supersededBy = null,
        characterId = ownerId,
    )

    private fun chunk(id: String, threadId: String, segmentId: String, messageId: String) = ChunkEntity(
        id = id,
        threadId = threadId,
        segmentId = segmentId,
        text = id,
        firstMessageAt = 1L,
        lastMessageAt = 1L,
        messageIds = messageId,
        dim = 0,
        vec = ByteArray(0),
        scale = 1f,
        model = "none",
        createdAt = 1L,
    )
}
