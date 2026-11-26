package cc.pulsez.app.data

import android.content.Context
import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import cc.pulsez.app.AppContainer
import cc.pulsez.app.data.attachments.AttachmentStore
import cc.pulsez.app.data.db.AttachmentKind
import cc.pulsez.app.data.db.AttachmentStatus
import cc.pulsez.app.data.db.CharacterEntity
import cc.pulsez.app.data.db.ChunkEntity
import cc.pulsez.app.data.db.ChunkTermEntity
import cc.pulsez.app.data.db.MemoryItemEntity
import cc.pulsez.app.data.db.PulsezDatabase
import cc.pulsez.app.data.repo.AttachmentRepository
import cc.pulsez.app.data.repo.CharacterRepository
import cc.pulsez.app.data.repo.ChatRepository
import cc.pulsez.app.data.repo.RemoteFileCleanupRepository
import cc.pulsez.app.mem.MemoryStatus
import java.io.ByteArrayInputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Repository behavior tests cover:
 * - 单条消息软删传播到 chunk / chunk_term / memory_item(UNVERIFIED) / attachment；
 * - 原地更新助手回复不虚增 totalMessages；
 * - 通用文件草稿与 remoteFileId 更新；
 * - ChatEndpointSession.toString() 密钥遮罩。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RepositoriesSoftDeleteTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var db: PulsezDatabase
    private lateinit var characters: CharacterRepository
    private lateinit var chat: ChatRepository
    private lateinit var attachments: AttachmentRepository
    private lateinit var store: AttachmentStore

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, PulsezDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        PulsezDatabase.installRemoteFileCleanupQueue(db.openHelper.writableDatabase)
        characters = CharacterRepository(db)
        chat = ChatRepository(db)
        attachments = AttachmentRepository(db)
        store = AttachmentStore(tempFolder.newFolder("files"))
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `softDeleteMessageWithPropagation propagates to chunks terms memory and attachments`() = runBlocking {
        val (_, thread) = characters.createWithThread("阿淮", "温和")
        val segmentId = thread.activeSegmentId!!
        val targetMsg = chat.append(
            threadId = thread.id,
            segmentId = segmentId,
            role = "user",
            text = "周六去喝桂花拿铁",
            tokens = 6,
            createdAt = 1_000L,
            latestReminder = "2026-11-25,周三,,App,中文",
        )
        val keepMsg = chat.append(
            threadId = thread.id,
            segmentId = segmentId,
            role = "assistant",
            text = "好呀",
            tokens = 2,
            createdAt = 2_000L,
        )

        db.chunkDao().insertAll(
            listOf(
                ChunkEntity(
                    id = "chunk-target",
                    threadId = thread.id,
                    segmentId = segmentId,
                    text = "周六去喝桂花拿铁",
                    firstMessageAt = 1_000L,
                    lastMessageAt = 1_000L,
                    messageIds = targetMsg.id,
                    dim = 2,
                    vec = byteArrayOf(1, 2),
                    scale = 1.0f,
                    model = "m",
                    createdAt = 1_000L,
                ),
                ChunkEntity(
                    id = "chunk-keep",
                    threadId = thread.id,
                    segmentId = segmentId,
                    text = "好呀",
                    firstMessageAt = 2_000L,
                    lastMessageAt = 2_000L,
                    messageIds = keepMsg.id,
                    dim = 2,
                    vec = byteArrayOf(3, 4),
                    scale = 1.0f,
                    model = "m",
                    createdAt = 2_000L,
                ),
            ),
        )
        db.chunkDao().insertTerms(
            listOf(
                ChunkTermEntity("桂花", "chunk-target", thread.id, 1),
                ChunkTermEntity("拿铁", "chunk-target", thread.id, 1),
                ChunkTermEntity("好呀", "chunk-keep", thread.id, 1),
            ),
        )
        db.memoryDao().insertAll(
            listOf(
                MemoryItemEntity(
                    id = "mem-target",
                    threadId = thread.id,
                    kind = "EVENT",
                    key = null,
                    value = "周六去喝桂花拿铁",
                    confidence = 0.9f,
                    importance = 3,
                    happenedAt = null,
                    dueAt = null,
                    validFrom = 1_000L,
                    validTo = null,
                    firstSeenAt = 1_000L,
                    lastSeenAt = 1_000L,
                    sourceSegmentId = segmentId,
                    sourceMessageIds = targetMsg.id,
                    status = MemoryStatus.ACTIVE,
                    supersededBy = null,
                ),
                MemoryItemEntity(
                    id = "mem-keep",
                    threadId = thread.id,
                    kind = "FACT",
                    key = null,
                    value = "普通事实",
                    confidence = 0.9f,
                    importance = 3,
                    happenedAt = null,
                    dueAt = null,
                    validFrom = 2_000L,
                    validTo = null,
                    firstSeenAt = 2_000L,
                    lastSeenAt = 2_000L,
                    sourceSegmentId = segmentId,
                    sourceMessageIds = keepMsg.id,
                    status = MemoryStatus.ACTIVE,
                    supersededBy = null,
                ),
            ),
        )

        val draft = attachments.createFileDraft(
            store = store,
            mime = "application/pdf",
            stream = ByteArrayInputStream("pdf-content".toByteArray()),
            fileName = "plan.pdf",
            ownerThreadId = thread.id,
        )
        attachments.markReady(draft.id)
        attachments.bindToMessage(listOf(draft.id), targetMsg.id)

        val deleteAt = 9_999L
        val deletion = chat.softDeleteMessageWithPropagation(thread.id, targetMsg.id, at = deleteAt)

        assertTrue(deletion.deleted)
        assertEquals(listOf(draft.localPath), deletion.localPaths)
        assertEquals(listOf(keepMsg.id), db.messageDao().allForThread(thread.id).map { it.id })
        assertEquals(deleteAt, db.messageDao().byId(targetMsg.id)?.deletedAt)

        val chunksById = db.chunkDao().forSegment(segmentId).associateBy { it.id }
        assertEquals(deleteAt, chunksById.getValue("chunk-target").deletedAt)
        assertNull(chunksById.getValue("chunk-keep").deletedAt)
        assertTrue(db.chunkDao().searchTerms(thread.id, listOf("桂花", "拿铁"), 10).isEmpty())
        assertEquals(listOf("chunk-keep"), db.chunkDao().searchTerms(thread.id, listOf("好呀"), 10).map { it.chunkId })

        val memoriesById = db.memoryDao().allForThread(thread.id).associateBy { it.id }
        assertEquals(MemoryStatus.UNVERIFIED, memoriesById.getValue("mem-target").status)
        assertEquals(MemoryStatus.ACTIVE, memoriesById.getValue("mem-keep").status)

        assertTrue(attachments.listForMessage(targetMsg.id).isEmpty())
        assertEquals(deleteAt, db.attachmentDao().byId(draft.id)?.deletedAt)

        // 幂等：重复删除返回空列表
        assertFalse(chat.softDeleteMessageWithPropagation(thread.id, targetMsg.id, at = 10_000L).deleted)
    }

    @Test
    fun `softDeleteMessageWithPropagation batches more than 500 chunk terms`() = runBlocking {
        val (_, thread) = characters.createWithThread("阿淮", "温和")
        val segmentId = thread.activeSegmentId!!
        val msg = chat.append(thread.id, segmentId, "user", "批量分块", 4, createdAt = 1_000L)

        val chunks = (1..520).map { i ->
            ChunkEntity(
                id = "c-$i",
                threadId = thread.id,
                segmentId = segmentId,
                text = "t-$i",
                firstMessageAt = 1_000L,
                lastMessageAt = 1_000L,
                messageIds = msg.id,
                dim = 1,
                vec = byteArrayOf(1),
                scale = 1f,
                model = "m",
                createdAt = 1_000L,
            )
        }
        val terms = (1..520).map { i ->
            ChunkTermEntity(term = "term-$i", chunkId = "c-$i", threadId = thread.id, weight = 1)
        }
        db.chunkDao().insertAll(chunks)
        db.chunkDao().insertTerms(terms)

        chat.softDeleteMessageWithPropagation(thread.id, msg.id, at = 5_000L)
        assertTrue(db.chunkDao().idsMatchingMessage(thread.id, msg.id).isEmpty())
        assertTrue(db.chunkDao().searchTerms(thread.id, listOf("term-1", "term-520"), 10).isEmpty())
    }

    @Test
    fun `softDelete propagation rolls back every table when SQLite aborts attachment update`() = runBlocking {
        val (_, thread) = characters.createWithThread("阿淮", "温和")
        val segmentId = thread.activeSegmentId!!
        val target = chat.append(thread.id, segmentId, "user", "事务回滚目标", 5, createdAt = 1_000L)
        db.chunkDao().insertAll(
            listOf(
                ChunkEntity(
                    id = "abort-target-chunk",
                    threadId = thread.id,
                    segmentId = segmentId,
                    text = "事务回滚目标",
                    firstMessageAt = 1_000L,
                    lastMessageAt = 1_000L,
                    messageIds = target.id,
                    dim = 1,
                    vec = byteArrayOf(1),
                    scale = 1f,
                    model = "m",
                    createdAt = 1_000L,
                ),
            ),
        )
        db.chunkDao().insertTerms(listOf(ChunkTermEntity("回滚", "abort-target-chunk", thread.id, 1)))
        db.memoryDao().insertAll(
            listOf(
                MemoryItemEntity(
                    id = "abort-target-memory",
                    threadId = thread.id,
                    kind = "FACT",
                    key = null,
                    value = "事务回滚目标",
                    confidence = 0.9f,
                    importance = 2,
                    happenedAt = null,
                    dueAt = null,
                    validFrom = 1_000L,
                    validTo = null,
                    firstSeenAt = 1_000L,
                    lastSeenAt = 1_000L,
                    sourceSegmentId = segmentId,
                    sourceMessageIds = target.id,
                    status = MemoryStatus.ACTIVE,
                    supersededBy = null,
                ),
            ),
        )
        val draft = attachments.createFileDraft(
            store = store,
            mime = "text/plain",
            stream = ByteArrayInputStream("kept".toByteArray()),
            fileName = "keep.txt",
            ownerThreadId = thread.id,
        )
        attachments.markReady(draft.id)
        assertEquals(1, attachments.setRemoteFile(draft.id, "remote-abort", null, "binding-a"))
        attachments.bindToMessage(listOf(draft.id), target.id)
        db.openHelper.writableDatabase.execSQL(
            """CREATE TRIGGER abort_attachment_soft_delete
                BEFORE UPDATE OF deletedAt ON attachment
                WHEN NEW.deletedAt IS NOT NULL
                BEGIN SELECT RAISE(ABORT, 'injected attachment delete failure'); END""".trimIndent(),
        )

        val failure = runCatching {
            chat.softDeleteMessageWithPropagation(thread.id, target.id, at = 9_999L)
        }.exceptionOrNull()

        assertNotNull("真实 SQLite trigger 应中止事务", failure)
        assertNull("消息软删必须回滚", db.messageDao().byId(target.id)?.deletedAt)
        assertNull("chunk 失效必须回滚", db.chunkDao().byIds(listOf("abort-target-chunk")).single().deletedAt)
        assertEquals("chunk_term 删除必须回滚", listOf("abort-target-chunk"),
            db.chunkDao().searchTerms(thread.id, listOf("回滚"), 10).map { it.chunkId })
        assertEquals("记忆状态更新必须回滚", MemoryStatus.ACTIVE,
            db.memoryDao().allForThread(thread.id).single().status)
        assertNull("附件软删必须回滚", db.attachmentDao().byId(draft.id)?.deletedAt)
        assertTrue("SQLite abort 应回滚远端清理 tombstone", RemoteFileCleanupRepository(db).entries().isEmpty())
    }

    @Test
    fun `assistant replacement commits new reply and deletion propagation together`() = runBlocking {
        val (_, thread) = characters.createWithThread("阿淮", "温和")
        val segmentId = thread.activeSegmentId!!
        val user = chat.append(thread.id, segmentId, "user", "再回答一次", 5, createdAt = 1_000L)
        val oldReply = chat.append(thread.id, segmentId, "assistant", "旧回复", 3, createdAt = 2_000L)
        db.chunkDao().insertAll(
            listOf(
                ChunkEntity(
                    id = "replace-old-chunk",
                    threadId = thread.id,
                    segmentId = segmentId,
                    text = "旧回复",
                    firstMessageAt = 2_000L,
                    lastMessageAt = 2_000L,
                    messageIds = oldReply.id,
                    dim = 1,
                    vec = byteArrayOf(1),
                    scale = 1f,
                    model = "m",
                    createdAt = 2_000L,
                ),
            ),
        )
        db.chunkDao().insertTerms(listOf(ChunkTermEntity("旧回复", "replace-old-chunk", thread.id, 1)))
        db.memoryDao().insertAll(
            listOf(
                MemoryItemEntity(
                    id = "replace-old-memory",
                    threadId = thread.id,
                    kind = "FACT",
                    key = null,
                    value = "旧回复记忆",
                    confidence = 0.9f,
                    importance = 2,
                    happenedAt = null,
                    dueAt = null,
                    validFrom = 2_000L,
                    validTo = null,
                    firstSeenAt = 2_000L,
                    lastSeenAt = 2_000L,
                    sourceSegmentId = segmentId,
                    sourceMessageIds = oldReply.id,
                    status = MemoryStatus.ACTIVE,
                    supersededBy = null,
                ),
            ),
        )
        val draft = attachments.createFileDraft(
            store = store,
            mime = "text/plain",
            stream = ByteArrayInputStream("old attachment".toByteArray()),
            fileName = "old.txt",
            ownerThreadId = thread.id,
        )
        attachments.markReady(draft.id)
        assertEquals(1, attachments.setRemoteFile(draft.id, "remote-old-reply", null, "binding-a"))
        attachments.bindToMessage(listOf(draft.id), oldReply.id)

        val result = chat.replaceAssistantReplyWithPropagation(
            threadId = thread.id,
            segmentId = segmentId,
            oldMessageId = oldReply.id,
            text = "新回复",
            tokens = 3,
            createdAt = 9_000L,
        )

        val replacement = requireNotNull(result)
        assertTrue(replacement.deleted.deleted)
        assertEquals(listOf(draft.localPath), replacement.deleted.localPaths)
        assertNotNull(db.messageDao().byId(oldReply.id)?.deletedAt)
        assertEquals("新回复", db.messageDao().byId(replacement.message.id)?.content)
        assertEquals(listOf(user.id, replacement.message.id),
            chat.chatTailInSegmentOrder(thread.id, limit = 10).map { it.message.id })
        assertTrue(db.chunkDao().searchTerms(thread.id, listOf("旧回复"), 10).isEmpty())
        assertEquals(MemoryStatus.UNVERIFIED, db.memoryDao().allForThread(thread.id).single().status)
        assertNotNull(db.attachmentDao().byId(draft.id)?.deletedAt)
        assertEquals(
            listOf("remote-old-reply" to "binding-a"),
            RemoteFileCleanupRepository(db).entries().map { it.remoteFileId to it.credentialBindingId },
        )
    }

    @Test
    fun `assistant replacement SQLite abort rolls back message and remote cleanup tombstone`() = runBlocking {
        val (_, thread) = characters.createWithThread("阿淮", "温和")
        val segmentId = thread.activeSegmentId!!
        val user = chat.append(thread.id, segmentId, "user", "再回答一次", 5, createdAt = 1_000L)
        val oldReply = chat.append(thread.id, segmentId, "assistant", "旧回复", 3, createdAt = 2_000L)
        val draft = attachments.createFileDraft(
            store = store,
            mime = "text/plain",
            stream = ByteArrayInputStream("old attachment".toByteArray()),
            fileName = "old.txt",
            ownerThreadId = thread.id,
        )
        attachments.markReady(draft.id)
        assertEquals(1, attachments.setRemoteFile(draft.id, "remote-replacement-abort", null, "binding-a"))
        attachments.bindToMessage(listOf(draft.id), oldReply.id)
        db.openHelper.writableDatabase.execSQL(
            """CREATE TRIGGER abort_replacement_attachment_soft_delete
                BEFORE UPDATE OF deletedAt ON attachment
                WHEN NEW.deletedAt IS NOT NULL
                BEGIN SELECT RAISE(ABORT, 'injected replacement attachment failure'); END""".trimIndent(),
        )

        val failure = runCatching {
            chat.replaceAssistantReplyWithPropagation(
                threadId = thread.id,
                segmentId = segmentId,
                oldMessageId = oldReply.id,
                text = "不应提交的新回复",
                tokens = 4,
                createdAt = 9_000L,
            )
        }.exceptionOrNull()

        assertNotNull("真实 SQLite trigger 应中止 replacement 事务", failure)
        assertNull("旧回复必须保持有效", db.messageDao().byId(oldReply.id)?.deletedAt)
        val messagesAfterRollback = db.messageDao().allIncludingDeletedForThread(thread.id)
        assertEquals(
            "Rollback must preserve the original user message and old reply only",
            listOf(user.id, oldReply.id),
            messagesAfterRollback.map { it.id },
        )
        assertEquals(
            "Rollback must preserve original message content",
            listOf("再回答一次", "旧回复"),
            messagesAfterRollback.map { it.content },
        )
        assertNull("原附件必须保持有效", db.attachmentDao().byId(draft.id)?.deletedAt)
        assertTrue("replacement 回滚也必须撤销清理 tombstone", RemoteFileCleanupRepository(db).entries().isEmpty())
    }

    @Test
    fun `assistant replacement leaves old reply when stop wins before commit fence`() = runBlocking {
        val (_, thread) = characters.createWithThread("阿淮", "温和")
        val segmentId = thread.activeSegmentId!!
        chat.append(thread.id, segmentId, "user", "再回答一次", 5, createdAt = 1_000L)
        val oldReply = chat.append(thread.id, segmentId, "assistant", "旧回复", 3, createdAt = 2_000L)

        val result = chat.replaceAssistantReplyWithPropagation(
            threadId = thread.id,
            segmentId = segmentId,
            oldMessageId = oldReply.id,
            text = "不应写入",
            tokens = 3,
            canBeginCommit = { false },
        )

        assertNull(result)
        assertNull(db.messageDao().byId(oldReply.id)?.deletedAt)
        assertEquals(
            listOf("再回答一次", "旧回复"),
            chat.chatTailInSegmentOrder(thread.id, limit = 10).map { it.message.content },
        )
    }

    @Test
    fun `assistant replacement rolls back when wipe invalidates after first mutation`() = runBlocking {
        val (_, thread) = characters.createWithThread("阿淮", "温和")
        val segmentId = thread.activeSegmentId!!
        chat.append(thread.id, segmentId, "user", "再回答一次", 5, createdAt = 1_000L)
        val oldReply = chat.append(thread.id, segmentId, "assistant", "旧回复", 3, createdAt = 2_000L)
        var continueChecks = 0

        val result = chat.replaceAssistantReplyWithPropagation(
            threadId = thread.id,
            segmentId = segmentId,
            oldMessageId = oldReply.id,
            text = "不应写入",
            tokens = 3,
            canBeginCommit = { true },
            // First in-transaction check runs after softDeleteById; model a wipe invalidating it.
            canContinue = { continueChecks += 1; false },
        )

        assertNull(result)
        assertEquals(1, continueChecks)
        assertNull(db.messageDao().byId(oldReply.id)?.deletedAt)
        assertEquals(
            listOf("再回答一次", "旧回复"),
            chat.chatTailInSegmentOrder(thread.id, limit = 10).map { it.message.content },
        )
    }

    @Test
    fun `soft delete preserves message when stop or wipe invalidates transaction`() = runBlocking {
        val (_, thread) = characters.createWithThread("阿淮", "温和")
        val segmentId = thread.activeSegmentId!!
        val message = chat.append(thread.id, segmentId, "assistant", "保留这条", 3, createdAt = 2_000L)

        val stopped = chat.softDeleteMessageWithPropagation(
            threadId = thread.id,
            messageId = message.id,
            canBeginCommit = { false },
        )
        assertFalse(stopped.deleted)
        assertNull(db.messageDao().byId(message.id)?.deletedAt)

        var continueChecks = 0
        val wiped = chat.softDeleteMessageWithPropagation(
            threadId = thread.id,
            messageId = message.id,
            canBeginCommit = { true },
            // This check follows softDeleteById, so false must roll that mutation back.
            canContinue = { continueChecks += 1; false },
        )
        assertFalse(wiped.deleted)
        assertEquals(1, continueChecks)
        assertNull(db.messageDao().byId(message.id)?.deletedAt)
        assertEquals(
            listOf(message.id),
            chat.chatTailInSegmentOrder(thread.id, limit = 10).map { it.message.id },
        )
    }

    @Test
    fun `assistant append refuses before commit and rolls back wipe during mutation`() = runBlocking {
        val (_, thread) = characters.createWithThread("阿淮", "温和")
        val segmentId = thread.activeSegmentId!!

        val stopped = chat.appendAssistantForTurn(
            threadId = thread.id,
            segmentId = segmentId,
            text = "停止后不写",
            tokens = 3,
            canBeginCommit = { false },
            canContinue = { true },
        )
        assertNull(stopped)
        assertTrue(chat.chatTailInSegmentOrder(thread.id, limit = 10).isEmpty())

        var epoch = 4L
        val capturedEpoch = epoch
        var continueChecks = 0
        val wiped = chat.appendAssistantForTurn(
            threadId = thread.id,
            segmentId = segmentId,
            text = "清空期间不写",
            tokens = 3,
            canBeginCommit = { epoch == capturedEpoch },
            canContinue = {
                continueChecks += 1
                epoch = capturedEpoch + 1L
                epoch == capturedEpoch
            },
        )
        assertNull(wiped)
        assertEquals("插入消息后第一次事务内复核应使整笔写入回滚", 1, continueChecks)
        assertEquals(5L, epoch)
        assertTrue(chat.chatTailInSegmentOrder(thread.id, limit = 10).isEmpty())
    }

    @Test
    fun `live user append preserves reminder and rolls back message activity and touch on invalidation`() = runBlocking {
        val (_, thread) = characters.createWithThread("阿淮", "温和")
        val segmentId = thread.activeSegmentId!!
        val before = db.threadDao().byId(thread.id)!!

        val rejected = chat.appendUserForTurn(
            threadId = thread.id,
            segmentId = segmentId,
            text = "这条会回滚",
            tokens = 5,
            latestReminder = "2026-11-26,星期四,,App,中文",
            canBeginCommit = { true },
            // The first post-insert check models a wipe arriving while the transaction is active.
            canContinue = { false },
        )

        assertNull(rejected)
        assertTrue(chat.chatTailInSegmentOrder(thread.id, limit = 10).isEmpty())
        val afterRollback = db.threadDao().byId(thread.id)!!
        assertEquals(before.totalMessages, afterRollback.totalMessages)
        assertEquals(before.lastMessageAt, afterRollback.lastMessageAt)
        assertEquals(before.lastSingleChatActivityAt, afterRollback.lastSingleChatActivityAt)

        val capturedReminder = "2026-11-26,星期四,,App,中文"
        val appended = chat.appendUserForTurn(
            threadId = thread.id,
            segmentId = segmentId,
            text = "正常发送",
            tokens = 4,
            createdAt = 20_000L,
            latestReminder = capturedReminder,
            canBeginCommit = { true },
            canContinue = { true },
        )

        assertNotNull(appended)
        assertEquals(capturedReminder, appended!!.latestReminder)
        assertEquals(1, db.threadDao().byId(thread.id)!!.totalMessages)
        assertNotNull(db.threadDao().byId(thread.id)!!.lastSingleChatActivityAt)
    }

    @Test
    fun `wipe queued ahead of live append prevents message and attachment resurrection`() = runBlocking {
        val (_, thread) = characters.createWithThread("阿淮", "温和")
        val segmentId = thread.activeSegmentId!!
        val draft = attachments.createDraft(
            store = store,
            kind = AttachmentKind.IMAGE,
            mime = "image/jpeg",
            bytes = byteArrayOf(1, 2, 3),
            fileName = "local.jpg",
            ownerThreadId = thread.id,
        )
        attachments.markReady(draft.id)

        val transactionHeld = CompletableDeferred<Unit>()
        val releaseTransaction = CompletableDeferred<Unit>()
        val holder = launch(Dispatchers.IO) {
            db.withTransaction {
                transactionHeld.complete(Unit)
                releaseTransaction.await()
            }
        }
        try {
            transactionHeld.await()

            val wipeEpoch = AtomicBoolean(false)
            // Advance the generation while Room is held, then start clear and stale send append.
            wipeEpoch.set(true)
            val clearStarted = CompletableDeferred<Unit>()
            val clear = async(Dispatchers.IO) {
                clearStarted.complete(Unit)
                db.clearAllTables()
            }
            clearStarted.await()
            val appendStarted = CompletableDeferred<Unit>()
            val append = async(Dispatchers.IO) {
                appendStarted.complete(Unit)
                chat.appendUserForTurn(
                    threadId = thread.id,
                    segmentId = segmentId,
                    text = "清空期间的消息",
                    tokens = 5,
                    canBeginCommit = { !wipeEpoch.get() },
                    canContinue = { !wipeEpoch.get() },
                )
            }
            appendStarted.await()
            releaseTransaction.complete(Unit)
            holder.join()
            assertNull(append.await())
            clear.await()

            assertTrue(chat.chatTailInSegmentOrder(thread.id, limit = 10).isEmpty())
            assertNull(db.threadDao().byId(thread.id))
            assertNull(db.attachmentDao().byId(draft.id))
        } finally {
            releaseTransaction.complete(Unit)
        }
    }

    @Test
    fun `wipe between final user append gate and commit is followed by clear`() = runBlocking {
        val (_, thread) = characters.createWithThread("阿淮", "温和")
        val segmentId = thread.activeSegmentId!!
        val wipeEpoch = AtomicBoolean(false)
        val finalGateChecked = CountDownLatch(1)
        val releaseFinalGate = CountDownLatch(1)
        var continueChecks = 0

        val append = async(Dispatchers.IO) {
            chat.appendUserForTurn(
                threadId = thread.id,
                segmentId = segmentId,
                text = "最终校验后立即清空",
                tokens = 5,
                canBeginCommit = { !wipeEpoch.get() },
                canContinue = {
                    val allowed = !wipeEpoch.get()
                    continueChecks += 1
                    if (continueChecks == 3) {
                        finalGateChecked.countDown()
                        releaseFinalGate.await()
                    }
                    allowed
                },
            )
        }

        try {
            assertTrue(
                "追加应在 SQLite 事务内停在最后一次 epoch 检查之后",
                withContext(Dispatchers.IO) { finalGateChecked.await(5, TimeUnit.SECONDS) },
            )
            wipeEpoch.set(true)
            val clearStarted = CompletableDeferred<Unit>()
            val clear = async(Dispatchers.IO) {
                clearStarted.complete(Unit)
                db.clearAllTables()
            }
            clearStarted.await()
            releaseFinalGate.countDown()
            val appended = append.await()
            clear.await()

            assertNotNull(appended)
            assertEquals(3, continueChecks)
            assertNull("清空排在已提交追加之后，不得留下迟到用户行", db.messageDao().byId(appended!!.id))
            assertNull(db.threadDao().byId(thread.id))
        } finally {
            releaseFinalGate.countDown()
        }
    }

    @Test
    fun `load thread creation rolls back when wipe changes after thread insert`() = runBlocking {
        val character = CharacterEntity(
            id = "load-wipe-character",
            name = "加载测试",
            avatarUri = null,
            persona = "测试",
            greeting = null,
            styleSamples = null,
            userName = null,
            systemPromptOverride = null,
            sourceType = "MANUAL",
            sourceRef = null,
            rawImported = null,
            emoAssetDir = null,
            createdAt = 1L,
            updatedAt = 1L,
        )
        db.characterDao().upsert(character)
        var checks = 0

        val stale = chat.ensureThreadForLoad(character.id, canContinue = {
            checks += 1
            checks < 2 // reject after the thread row, so Room must roll it and its segment back
        })

        assertNull(stale)
        assertNull(db.threadDao().latestForCharacter(character.id))
        assertEquals(2, checks)

        val loaded = chat.ensureThreadForLoad(character.id, canContinue = { true })
        assertNotNull(loaded)
        assertNotNull(db.threadDao().latestForCharacter(character.id))
    }

    @Test
    fun `wipe after final append gate is followed by clear and leaves no resurrected row`() = runBlocking {
        val (_, thread) = characters.createWithThread("阿淮", "温和")
        val segmentId = thread.activeSegmentId!!
        val capturedEpoch = 7L
        var epoch = capturedEpoch
        var checks = 0

        val appended = chat.appendAssistantForTurn(
            threadId = thread.id,
            segmentId = segmentId,
            text = "提交与清空交错",
            tokens = 4,
            canBeginCommit = { epoch == capturedEpoch },
            canContinue = {
                val allowed = epoch == capturedEpoch
                checks += 1
                if (checks == 2) epoch += 1L // epoch changes after the final check returned true
                allowed
            },
        )

        assertNotNull("最终 gate 已放行时事务可先提交", appended)
        assertEquals(2, checks)
        withContext(Dispatchers.IO) { db.clearAllTables() }
        assertNull("清空排在提交后，不得留下被旧事务复活的消息", db.messageDao().byId(appended!!.id))
    }

    @Test
    fun `append waiting for Room transaction slot observes stop before writing`() = runBlocking {
        val (_, thread) = characters.createWithThread("阿淮", "温和")
        val segmentId = thread.activeSegmentId!!
        val transactionHeld = CompletableDeferred<Unit>()
        val releaseTransaction = CompletableDeferred<Unit>()
        val holder = launch(Dispatchers.IO) {
            db.withTransaction {
                transactionHeld.complete(Unit)
                releaseTransaction.await()
            }
        }
        transactionHeld.await()

        val stopRequested = AtomicBoolean(false)
        val write = async(Dispatchers.IO, start = CoroutineStart.UNDISPATCHED) {
            chat.appendAssistantForTurn(
                threadId = thread.id,
                segmentId = segmentId,
                text = "停止后不能补写",
                tokens = 3,
                canBeginCommit = { !stopRequested.get() },
                canContinue = { !stopRequested.get() },
            )
        }
        val wasWaitingForRoom = !write.isCompleted
        stopRequested.set(true)
        releaseTransaction.complete(Unit)
        holder.join()

        assertTrue("第一个事务持锁时，第二笔写入必须仍在等待", wasWaitingForRoom)
        assertNull(write.await())
        assertTrue(chat.chatTailInSegmentOrder(thread.id, limit = 10).isEmpty())
    }

    @Test
    fun `Room queued append cancellation while waiting for transaction does not write`() = runBlocking {
        val (_, thread) = characters.createWithThread("阿淮", "温和")
        val segmentId = thread.activeSegmentId!!
        val transactionHeld = CompletableDeferred<Unit>()
        val releaseTransaction = CompletableDeferred<Unit>()
        val holder = launch(Dispatchers.IO) {
            db.withTransaction {
                transactionHeld.complete(Unit)
                releaseTransaction.await()
            }
        }
        transactionHeld.await()

        val write = async(Dispatchers.IO, start = CoroutineStart.UNDISPATCHED) {
            chat.appendAssistantForTurn(
                threadId = thread.id,
                segmentId = segmentId,
                text = "取消后不写",
                tokens = 3,
                canBeginCommit = { true },
                canContinue = { true },
            )
        }
        val wasWaitingForRoom = !write.isCompleted
        write.cancel()
        releaseTransaction.complete(Unit)
        holder.join()
        write.join()

        assertTrue("取消前写入必须仍在等 Room 的事务槽", wasWaitingForRoom)
        assertTrue(write.isCancelled)
        assertTrue(chat.chatTailInSegmentOrder(thread.id, limit = 10).isEmpty())
    }

    @Test
    fun `updateAssistantReply updates fields without incrementing totalMessages`() = runBlocking {
        val (_, thread) = characters.createWithThread("阿淮", "温和")
        val segmentId = thread.activeSegmentId!!
        chat.append(thread.id, segmentId, "user", "你好", 2, createdAt = 1_000L)
        val reply = chat.append(thread.id, segmentId, "assistant", "", 0, status = "FAILED", createdAt = 2_000L)
        assertEquals(2, db.threadDao().byId(thread.id)?.totalMessages)

        val updated = chat.updateAssistantReply(
            threadId = thread.id,
            messageId = reply.id,
            text = "我回来了",
            tokens = 5,
            status = "OK",
            emotion = "开心",
            usageIn = 12,
            usageOut = 5,
            reasoningContent = "先打个招呼",
            serverToolCallsJson = "[{\"type\":\"deepseek_web_search\"}]",
            citationsJson = "[{\"url\":\"https://example.com\",\"startIndex\":0,\"endIndex\":4}]",
        )
        assertTrue(updated)

        val stored = db.messageDao().byId(reply.id)!!
        assertEquals("我回来了", stored.content)
        assertEquals(5, stored.tokens)
        assertEquals("OK", stored.status)
        assertEquals("开心", stored.emotion)
        assertEquals(12, stored.usageIn)
        assertEquals(5, stored.usageOut)
        assertEquals("先打个招呼", stored.reasoningContent)
        assertEquals("[{\"type\":\"deepseek_web_search\"}]", stored.serverToolCallsJson)
        assertEquals("[{\"url\":\"https://example.com\",\"startIndex\":0,\"endIndex\":4}]", stored.citationsJson)

        val updatedThread = db.threadDao().byId(thread.id)!!
        assertEquals("原地更新回复不得虚增 totalMessages", 2, updatedThread.totalMessages)
        assertTrue(updatedThread.lastMessageAt >= 2_000L)

        // 已软删消息不可被 updateAssistantReply 改写
        chat.softDeleteMessageWithPropagation(thread.id, reply.id, at = 3_000L)
        assertFalse(
            chat.updateAssistantReply(
                threadId = thread.id,
                messageId = reply.id,
                text = "不应写入",
                tokens = 3,
            ),
        )
    }

    @Test
    fun `createFileDraft and setRemoteFile persist file metadata safely`() = runBlocking {
        val pdf = attachments.createFileDraft(
            store = store,
            mime = "application/pdf",
            stream = ByteArrayInputStream("hello-pdf".toByteArray()),
            fileName = " 会议纪要.PDF ",
            createdAt = 1_000L,
        )
        assertEquals(AttachmentKind.FILE, pdf.kind)
        assertEquals(AttachmentStatus.DRAFT, pdf.status)
        assertEquals("会议纪要.PDF", pdf.fileName)
        assertNull(pdf.width)
        assertNull(pdf.height)
        assertTrue(pdf.localPath.endsWith("/${pdf.id}.pdf"))
        assertTrue(store.exists(pdf.localPath))

        val fallbackExt = attachments.createFileDraft(
            store = store,
            mime = "application/octet-stream",
            stream = ByteArrayInputStream("raw".toByteArray()),
            fileName = "../weird.toolongext123",
            createdAt = 2_000L,
        )
        assertTrue(fallbackExt.localPath.endsWith("/${fallbackExt.id}.bin"))

        assertEquals(1, attachments.setRemoteFile(pdf.id, "file-remote-42", 1_900_000_000_000L))
        val reloaded = db.attachmentDao().byId(pdf.id)!!
        assertEquals("file-remote-42", reloaded.remoteFileId)
        assertEquals(1_900_000_000_000L, reloaded.expiresAt)

        db.attachmentDao().softDeleteById(pdf.id, 3_000L)
        assertEquals(0, attachments.setRemoteFile(pdf.id, "file-remote-99", null))
    }

    @Test
    fun `ChatEndpointSession masks apiKey in toString`() {
        val session = AppContainer.ChatEndpointSession(
            baseUrl = "https://api.deepseek.com",
            apiKey = "sk-secret-key-999",
        )
        val rendered = session.toString()
        assertEquals("ChatEndpointSession(baseUrl=https://api.deepseek.com, apiKey=***)", rendered)
        assertFalse(rendered.contains("sk-secret-key-999"))
        assertNotNull(session.baseUrl)
    }
}
