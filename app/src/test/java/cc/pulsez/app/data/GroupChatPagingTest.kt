package cc.pulsez.app.data

import android.content.Context
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.test.core.app.ApplicationProvider
import cc.pulsez.app.data.db.MessageEntity
import cc.pulsez.app.data.db.PulsezDatabase
import cc.pulsez.app.data.db.ThreadMemberEntity
import cc.pulsez.app.data.repo.ChatRepository
import cc.pulsez.app.data.repo.CharacterRepository
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
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executor

/** Group UI keyset windows are tested against in-memory Room and stay independent of full groupHistory. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class GroupChatPagingTest {

    private lateinit var db: PulsezDatabase
    private lateinit var chat: ChatRepository
    private lateinit var threadId: String
    private lateinit var segmentId: String
    private lateinit var firstMember: String
    private lateinit var secondMember: String
    private lateinit var frozenOrder: List<ChatRepository.GroupUiMemberOrder>
    private val observedQueries = CopyOnWriteArrayList<String>()

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, PulsezDatabase::class.java)
            .setQueryCallback(
                RoomDatabase.QueryCallback { sql, _ -> observedQueries += sql },
                Executor { command -> command.run() },
            )
            .allowMainThreadQueries()
            .build()
        chat = ChatRepository(db)
        runBlocking {
            val characters = CharacterRepository(db)
            firstMember = characters.createWithThread("甲", "测试角色").first.id
            secondMember = characters.createWithThread("乙", "测试角色").first.id
            val group = chat.createGroupThread(listOf(firstMember, secondMember), "分页测试群")
            threadId = group.id
            segmentId = group.activeSegmentId!!
            frozenOrder = listOf(
                ChatRepository.GroupUiMemberOrder(firstMember, 0),
                ChatRepository.GroupUiMemberOrder(secondMember, 1),
            )
        }
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun sameMillisecondCompositeOrderPagesWithoutGapsAfterMemberReorder() = runBlocking {
        val sameTime = 1_700_000_000_000L
        val rows = listOf(
            message("user-a", sameTime, "user"),
            message("user-b", sameTime, "user"),
            message("member-a", sameTime, "assistant", firstMember),
            message("member-b", sameTime, "assistant", secondMember),
            message("unknown", sameTime, "assistant", "removed-member"),
        )
        rows.forEach { db.messageDao().upsert(it) }
        val expected = rows.sortedWith(
            compareBy<MessageEntity> { it.createdAt }
                .thenBy { row -> row.speakerCharacterId?.let { id -> frozenOrder.firstOrNull { it.characterId == id }?.ordinal ?: Int.MAX_VALUE } ?: -1 }
                .thenBy { it.id },
        ).map { it.id }

        val first = chat.groupUiLatestPage(threadId, frozenOrder, limit = 3)
        assertEquals(expected.takeLast(3), first.messages.map { it.id })
        assertTrue(first.hasOlder)

        // The current database order changes mid-session. Paging continues using the open-time ordinal snapshot.
        db.threadMemberDao().insertAll(
            listOf(
                ThreadMemberEntity(threadId, firstMember, ordinal = 1, joinedAt = 1L),
                ThreadMemberEntity(threadId, secondMember, ordinal = 0, joinedAt = 1L),
            ),
        )
        val older = chat.groupUiBeforePage(threadId, first.messages.first(), frozenOrder, limit = 2)
        val allLoaded = (older.messages + first.messages).distinctBy { it.id }
        assertEquals(expected, allLoaded.sortedWith(
            compareBy<MessageEntity> { it.createdAt }
                .thenBy { row -> row.speakerCharacterId?.let { id -> frozenOrder.firstOrNull { it.characterId == id }?.ordinal ?: Int.MAX_VALUE } ?: -1 }
                .thenBy { it.id },
        ).map { it.id })
        assertEquals("每页拼接必须无重复", allLoaded.size, older.messages.size + first.messages.size)
        assertFalse(older.hasOlder)
    }

    @Test
    fun pageBoundaryCarriesMentionAndMainForReactionMerge() = runBlocking {
        val user = message("turn-user", 1L, "user", content = "@甲 请回答")
        val main = message("turn-main", 2L, "assistant", firstMember)
        val reaction = message("turn-reaction", 3L, "assistant", secondMember)
        listOf(user, main, reaction).forEach { db.messageDao().upsert(it) }

        val page = chat.groupUiLatestPage(threadId, frozenOrder, limit = 1)

        assertEquals(listOf("turn-main", "turn-reaction"), page.messages.map { it.id })
        assertEquals("turn-user", page.precedingUser?.id)
        assertFalse(page.precedingAssistantSeen)
        assertTrue(page.hasOlder)
    }

    @Test
    fun laterReactionUsesCompactPrefixWithoutLoadingWholeTurn() = runBlocking {
        val user = message("long-turn-user", 1L, "user", content = "@甲 这一轮")
        val main = message("long-turn-main", 2L, "assistant", firstMember)
        val reaction = message("long-turn-reaction", 3L, "assistant", secondMember)
        val laterReaction = message("long-turn-later", 4L, "assistant", firstMember)
        listOf(user, main, reaction, laterReaction).forEach { db.messageDao().upsert(it) }

        val page = chat.groupUiLatestPage(threadId, frozenOrder, limit = 1)

        assertEquals(listOf("long-turn-later"), page.messages.map { it.id })
        assertEquals("long-turn-user", page.precedingUser?.id)
        assertTrue("已有至少两条 AI 回复时，页首仍应是 REACTION", page.precedingAssistantSeen)
        assertTrue(page.hasOlder)
    }

    @Test
    fun concurrentAppendDoesNotMoveOlderCursorOrDuplicateRows() = runBlocking {
        val original = (0 until 100).map { index ->
            message("row-%03d".format(index), 10_000L + index, "user")
        }
        original.forEach { db.messageDao().upsert(it) }

        val newest = chat.groupUiLatestPage(threadId, frozenOrder, limit = 80)
        assertEquals(original.map { it.id }.takeLast(80), newest.messages.map { it.id })

        val appended = message("append-during-page", 20_000L, "user")
        db.messageDao().upsert(appended)
        val older = chat.groupUiBeforePage(threadId, newest.messages.first(), frozenOrder, limit = 60)

        assertEquals(original.map { it.id }.take(20), older.messages.map { it.id })
        assertTrue(older.messages.none { it.id == appended.id })
        assertEquals(original.map { it.id }, older.messages.map { it.id } + newest.messages.map { it.id })
        assertEquals(appended.id, chat.groupUiLatestPage(threadId, frozenOrder, limit = 1).messages.single().id)
    }

    @Test
    fun emptyGroupWindowHasNoOlderCursor() = runBlocking {
        val page = chat.groupUiLatestPage(threadId, frozenOrder)
        assertTrue(page.messages.isEmpty())
        assertFalse(page.hasOlder)
        assertNull(page.precedingUser)
    }

    @Test
    fun softDeletedRowsAreExcludedFromUiPageLedgerFallbackAndRecentReplies() = runBlocking {
        val base = 1_700_100_000_000L
        val activeRows = listOf(
            message("live-user-a", base, "user"),
            message("live-main-a", base + 1, "assistant", firstMember, "live reply A"),
            message("live-user-b", base + 2, "user"),
            message("live-main-b", base + 3, "assistant", secondMember, "live reply B"),
        )
        val deletedRows = listOf(
            message("deleted-user", base + 4, "user"),
            message("deleted-main", base + 5, "assistant", firstMember, "deleted reply"),
        )
        val deletedIds = deletedRows.map { it.id }.toSet()
        (activeRows + deletedRows).forEach { db.messageDao().upsert(it) }
        deletedRows.forEach { db.messageDao().softDelete(it.id, base + 6) }

        val page = chat.groupUiLatestPage(threadId, frozenOrder, limit = 10)
        val fallback = chat.groupLedgerFallback(threadId, frozenOrder)
        val recent = chat.groupUiRecentAssistantReplies(threadId, frozenOrder, limit = 3)

        assertEquals("UI history keeps active rows in the original group order", activeRows.map { it.id }, page.messages.map { it.id })
        assertFalse("deleted rows do not create an older UI page", page.hasOlder)
        assertEquals(linkedMapOf(firstMember to 1, secondMember to 0), fallback.silentTurns)
        assertEquals(listOf(secondMember, firstMember), fallback.lastSpeakers)
        assertEquals(listOf("live reply A", "live reply B"), recent)
        assertTrue(page.messages.none { it.id in deletedIds })
        assertFalse(recent.contains("deleted reply"))
    }

    @Test
    fun aggregateLedgerFallbackMatchesHistoryRebuildWithoutReadingAllMessageRows() = runBlocking {
        val base = 1_700_000_000_000L
        listOf(
            message("pre-user-assistant", base, "assistant", firstMember),
            message("turn-1-user", base + 1, "user"),
            message("turn-1-null-speaker", base + 2, "assistant"),
            message("turn-1-main-a", base + 3, "assistant", firstMember),
            message("turn-1-reaction-b", base + 4, "assistant", secondMember),
            message("turn-2-user", base + 5, "user"),
            message("turn-2-main-b", base + 6, "assistant", secondMember),
            message("turn-3-user", base + 7, "user"),
            message("turn-3-main-a", base + 8, "assistant", firstMember),
            message("turn-4-user", base + 9, "user"),
            message("turn-4-main-removed", base + 10, "assistant", "removed-member"),
        ).forEach { db.messageDao().upsert(it) }

        // Reference the old in-memory rebuild on the same fixture, then clear its full-history query from the audit.
        val fullHistoryReference = chat.groupHistory(threadId)
        val expectedSilent = linkedMapOf(firstMember to 0, secondMember to 0)
        val primarySpeakers = mutableListOf<String>()
        var inTurn = false
        var primaryChosen = false
        fullHistoryReference.forEach { row ->
            when (row.role) {
                "user" -> {
                    inTurn = true
                    primaryChosen = false
                }

                "assistant" -> {
                    val speaker = row.speakerCharacterId ?: return@forEach
                    if (inTurn && !primaryChosen) {
                        primaryChosen = true
                        primarySpeakers += speaker
                        if (speaker in expectedSilent) expectedSilent[speaker] = 0
                        expectedSilent.keys.filter { it != speaker }.forEach { id ->
                            expectedSilent[id] = (expectedSilent[id] ?: 0) + 1
                        }
                    }
                }
            }
        }
        val expectedLastSpeakers = primarySpeakers.asReversed().distinct().take(2)
        val expectedRecentReplies = fullHistoryReference.asSequence()
            .filter { it.role == "assistant" }
            .map { it.content }
            .toList()
            .takeLast(3)
        observedQueries.clear()

        val fallback = chat.groupLedgerFallback(threadId, frozenOrder)
        val recent = chat.groupUiRecentAssistantReplies(threadId, frozenOrder, limit = 3)

        assertEquals(expectedSilent, fallback.silentTurns)
        assertEquals(expectedLastSpeakers, fallback.lastSpeakers)
        assertEquals(expectedRecentReplies, recent)
        assertEquals("fallback aggregate returns one row per current member", 2, fallback.silentTurns.size)
        assertTrue("cooldown fallback is limited to two distinct speakers", fallback.lastSpeakers.size <= 2)
        assertEquals("prompt de-duplication remains limited to three assistant replies", 3, recent.size)

        val sql = observedQueries.map { it.lowercase() }
        assertTrue("fallback uses key-only primary-turn aggregation", sql.any { "primary_turns as" in it })
        assertTrue("recent prompt de-duplication is SQL-limited", sql.any { "select content from message" in it && "limit ?" in it })
        assertTrue(
            "screen-open state must not execute the full-history message query",
            sql.none { "select * from message where threadid = ? and deletedat is null order by createdat asc, id asc" in it },
        )
    }

    private fun message(
        id: String,
        createdAt: Long,
        role: String,
        speaker: String? = null,
        content: String = "synthetic $id",
    ) = MessageEntity(
        id = id,
        threadId = threadId,
        segmentId = segmentId,
        role = role,
        content = content,
        tokens = 1,
        emotion = null,
        status = "OK",
        draft = null,
        usageIn = null,
        usageOut = null,
        previousContent = null,
        createdAt = createdAt,
        speakerCharacterId = speaker,
    )
}
