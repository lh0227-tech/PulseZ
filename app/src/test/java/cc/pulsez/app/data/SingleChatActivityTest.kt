package cc.pulsez.app.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import cc.pulsez.app.data.attachments.AttachmentStore
import cc.pulsez.app.data.db.AttachmentKind
import cc.pulsez.app.data.db.PulsezDatabase
import cc.pulsez.app.data.repo.AttachmentRepository
import cc.pulsez.app.data.repo.CharacterRepository
import cc.pulsez.app.data.repo.ChatRepository
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/** Offline Room coverage for explicit live single-chat activity events. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SingleChatActivityTest {

    private lateinit var db: PulsezDatabase
    private lateinit var characters: CharacterRepository
    private lateinit var chat: ChatRepository

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, PulsezDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        characters = CharacterRepository(db)
        chat = ChatRepository(db)
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun liveUserAppendStoresLocalEventAndDeletionDoesNotRollItBack() = runBlocking {
        val (_, thread) = characters.createWithThread("实时角色", "人设")
        assertNull("空会话没有真实活动时间", db.threadDao().byId(thread.id)?.lastSingleChatActivityAt)
        val beforeAppend = System.currentTimeMillis()

        val message = chat.append(
            threadId = thread.id,
            segmentId = thread.activeSegmentId!!,
            role = "user",
            text = "本轮用户消息",
            tokens = 4,
            // Historical message time cannot stand in for the local activity event.
            createdAt = 1L,
            recordSingleChatActivity = true,
        )

        val persistedThread = requireNotNull(db.threadDao().byId(thread.id))
        val activityAt = requireNotNull(persistedThread.lastSingleChatActivityAt)
        assertTrue(activityAt >= beforeAppend)
        assertEquals("消息时间仍保留原值", 1L, persistedThread.lastMessageAt)
        assertEquals("请求失败也不撤销已持久化的用户消息", listOf(message.id), db.messageDao().allForThread(thread.id).map { it.id })

        db.messageDao().softDelete(message.id, System.currentTimeMillis())
        assertEquals(
            "删除消息不回退已经记录的事件时间",
            activityAt,
            db.threadDao().byId(thread.id)?.lastSingleChatActivityAt,
        )
    }

    @Test
    fun importedAssistantBlankAndGroupWritesDoNotRecordSingleActivity() = runBlocking {
        val (importedRole, importedThread) = characters.createWithThread("导入角色", "人设")
        chat.append(
            importedThread.id,
            importedThread.activeSegmentId!!,
            role = "user",
            text = "历史导入用户消息",
            tokens = 2,
        )
        assertNull("默认 append 不把导入 user 消息算成真实聊天", db.threadDao().byId(importedThread.id)?.lastSingleChatActivityAt)

        val (assistantRole, assistantThread) = characters.createWithThread("回复角色", "人设")
        chat.append(
            assistantThread.id,
            assistantThread.activeSegmentId!!,
            role = "assistant",
            text = "助手回复",
            tokens = 2,
            recordSingleChatActivity = true,
        )
        assertNull("assistant 流式落库不推进真实 user 活动", db.threadDao().byId(assistantThread.id)?.lastSingleChatActivityAt)

        val (blankRole, blankThread) = characters.createWithThread("空消息角色", "人设")
        chat.append(
            blankThread.id,
            blankThread.activeSegmentId!!,
            role = "user",
            text = "  ",
            tokens = 0,
            recordSingleChatActivity = true,
        )
        assertNull("空白 user 消息不推进活动", db.threadDao().byId(blankThread.id)?.lastSingleChatActivityAt)

        val group = chat.createGroupThread(listOf(importedRole.id, assistantRole.id, blankRole.id))
        chat.append(
            group.id,
            group.activeSegmentId!!,
            role = "user",
            text = "群聊消息",
            tokens = 2,
            recordSingleChatActivity = true,
        )
        assertNull("群聊无单一角色活动", db.threadDao().byId(group.id)?.lastSingleChatActivityAt)
    }

    @Test
    fun archivedSingleThreadIsNotUpdatedAndOuterRollbackIsAtomic() = runBlocking {
        val (_, archivedThread) = characters.createWithThread("归档角色", "人设")
        db.threadDao().upsert(archivedThread.copy(archived = true))
        chat.append(
            archivedThread.id,
            archivedThread.activeSegmentId!!,
            role = "user",
            text = "归档会话里的旧消息",
            tokens = 2,
            recordSingleChatActivity = true,
        )
        assertNull("归档会话不计入活动", db.threadDao().byId(archivedThread.id)?.lastSingleChatActivityAt)

        val (_, thread) = characters.createWithThread("回滚角色", "人设")
        val beforeCount = db.messageDao().allForThread(thread.id).size
        db.openHelper.writableDatabase.execSQL(
            "CREATE TRIGGER reject_live_activity " +
                "BEFORE UPDATE OF lastSingleChatActivityAt ON thread " +
                "WHEN NEW.lastSingleChatActivityAt IS NOT NULL " +
                "BEGIN SELECT RAISE(ABORT, 'reject activity'); END",
        )
        val aborted = runCatching {
            chat.append(
                thread.id,
                thread.activeSegmentId!!,
                role = "user",
                text = "活动写入被数据库拒绝",
                tokens = 2,
                recordSingleChatActivity = true,
            )
        }
        assertTrue(aborted.isFailure)
        assertEquals(beforeCount, db.messageDao().allForThread(thread.id).size)
        assertNull("消息与活动时间随同一事务一起回滚", db.threadDao().byId(thread.id)?.lastSingleChatActivityAt)
    }

    @Test
    fun imageOnlyActivityIsRecordedOnlyAfterMessageAndAttachmentArePersisted() = runBlocking {
        val (_, thread) = characters.createWithThread("图片角色", "人设")
        val storeDir = File(ApplicationProvider.getApplicationContext<Context>().cacheDir, "image-activity-${System.nanoTime()}")
        storeDir.mkdirs()
        val store = AttachmentStore(storeDir)
        try {
            val attachments = AttachmentRepository(db)
            val draft = attachments.createDraft(
                store = store,
                kind = AttachmentKind.IMAGE,
                mime = "image/jpeg",
                bytes = byteArrayOf(1, 2, 3),
                id = "activity-image",
                ownerThreadId = thread.id,
            )
            attachments.markReady(draft.id)
            val message = chat.append(
                threadId = thread.id,
                segmentId = thread.activeSegmentId!!,
                role = "user",
                text = "",
                tokens = 4,
                recordSingleChatActivity = true,
            )
            assertNull("纯图片尚未绑定时不记活动", db.threadDao().byId(thread.id)?.lastSingleChatActivityAt)

            attachments.bindToMessage(listOf(draft.id), message.id)
            val eventAt = 7_000L
            assertTrue(chat.recordActivityForImageMessage(thread.id, message.id, eventAt))
            assertEquals(eventAt, db.threadDao().byId(thread.id)?.lastSingleChatActivityAt)

            val blank = chat.append(
                threadId = thread.id,
                segmentId = thread.activeSegmentId!!,
                role = "user",
                text = "   ",
                tokens = 0,
                createdAt = eventAt + 1,
                recordSingleChatActivity = true,
            )
            assertFalse("空白无附件不能改写活动时间", chat.recordActivityForImageMessage(thread.id, blank.id, eventAt + 2))
            assertEquals(eventAt, db.threadDao().byId(thread.id)?.lastSingleChatActivityAt)
        } finally {
            storeDir.deleteRecursively()
        }
    }

    @Test
    fun roleActivityUsesLatestUnarchivedSingleEventOnly() = runBlocking {
        val (role, thread) = characters.createWithThread("多会话角色", "人设")
        db.threadDao().upsert(thread.copy(lastSingleChatActivityAt = 100L))
        db.threadDao().upsert(
            thread.copy(
                id = "single-newer-event",
                createdAt = thread.createdAt + 1,
                lastMessageAt = 900_000L,
                lastSingleChatActivityAt = 500L,
            ),
        )
        db.threadDao().upsert(
            thread.copy(
                id = "single-archived-event",
                createdAt = thread.createdAt + 2,
                archived = true,
                lastSingleChatActivityAt = 900L,
            ),
        )
        val (legacyRole, _) = characters.createWithThread("旧数据角色", "人设")
        val (groupRole, _) = characters.createWithThread("群聊成员", "人设")
        val group = chat.createGroupThread(listOf(role.id, legacyRole.id, groupRole.id))
        db.threadDao().upsert(group.copy(lastSingleChatActivityAt = 2_000L))

        val rows = db.characterDao().allWithChatActivity().associate { it.character.id to it.lastSingleChatActivityAt }
        assertEquals(500L, rows[role.id])
        assertNull("旧数据的 lastMessageAt 不作回填", rows[legacyRole.id])
        assertNull("群聊时间不进入角色单聊活动", rows[groupRole.id])
    }
}
