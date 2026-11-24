package cc.pulsez.app.ui.group

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import cc.pulsez.app.core.GroupMember
import cc.pulsez.app.data.db.MessageEntity
import cc.pulsez.app.data.db.PulsezDatabase
import cc.pulsez.app.data.repo.CharacterRepository
import cc.pulsez.app.data.repo.ChatRepository
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Real Room pages are passed through the production history mapper at reaction/@ page boundaries. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class GroupChatPagingMapperTest {

    private lateinit var db: PulsezDatabase
    private lateinit var chat: ChatRepository
    private lateinit var threadId: String
    private lateinit var segmentId: String
    private lateinit var firstMember: String
    private lateinit var secondMember: String
    private lateinit var memberOrder: List<ChatRepository.GroupUiMemberOrder>
    private lateinit var engineMembers: List<GroupMember>

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, PulsezDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        chat = ChatRepository(db)
        runBlocking {
            val characters = CharacterRepository(db)
            val first = characters.createWithThread("甲", "测试角色").first
            val second = characters.createWithThread("乙", "测试角色").first
            firstMember = first.id
            secondMember = second.id
            val group = chat.createGroupThread(listOf(firstMember, secondMember), "mapper 边界")
            threadId = group.id
            segmentId = group.activeSegmentId!!
            memberOrder = listOf(
                ChatRepository.GroupUiMemberOrder(firstMember, 0),
                ChatRepository.GroupUiMemberOrder(secondMember, 1),
            )
            engineMembers = listOf(
                GroupMember(characterId = firstMember, name = first.name, persona = first.persona, ordinal = 0),
                GroupMember(characterId = secondMember, name = second.name, persona = second.persona, ordinal = 1),
            )
        }
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun roomPagePrefixKeepsMainReactionMentionAndMessageKeysAcrossPageJoin() = runBlocking {
        val rows = listOf(
            message("turn-user", 1L, "user", content = "@甲 @乙 这一轮"),
            message("turn-main", 2L, "assistant", firstMember, "主回"),
            message("turn-reaction", 3L, "assistant", secondMember, "短反应"),
            message("turn-later-reaction", 4L, "assistant", firstMember, "更后的反应"),
        )
        rows.forEach { db.messageDao().upsert(it) }
        val names = mapOf(firstMember to "甲", secondMember to "乙")

        val newestPage = chat.groupUiLatestPage(threadId, memberOrder, limit = 1)
        assertEquals(listOf("turn-later-reaction"), newestPage.messages.map { it.id })
        assertEquals("turn-user", newestPage.precedingUser?.id)
        assertTrue(newestPage.precedingAssistantSeen)
        val newestUi = mapGroupHistoryToUiMessages(
            history = newestPage.messages,
            members = engineMembers,
            speakerNames = names,
            precedingUser = newestPage.precedingUser,
            precedingAssistantSeen = newestPage.precedingAssistantSeen,
        )
        assertEquals(GroupMessageKind.REACTION, newestUi.single().kind)
        assertTrue(newestUi.single().addressed)

        val olderPage = chat.groupUiBeforePage(
            threadId = threadId,
            before = newestPage.messages.first(),
            memberOrder = memberOrder,
            limit = 1,
        )
        assertEquals(listOf("turn-main", "turn-reaction"), olderPage.messages.map { it.id })
        assertEquals("turn-user", olderPage.precedingUser?.id)
        assertFalse(olderPage.precedingAssistantSeen)

        val merged = olderPage.messages + newestPage.messages
        assertEquals(merged.size, merged.map { it.id }.distinct().size)
        val mergedUi = mapGroupHistoryToUiMessages(
            history = merged,
            members = engineMembers,
            speakerNames = names,
            precedingUser = olderPage.precedingUser,
            precedingAssistantSeen = olderPage.precedingAssistantSeen,
        )
        assertEquals(
            listOf(
                "turn-main" to GroupMessageKind.MAIN,
                "turn-reaction" to GroupMessageKind.REACTION,
                "turn-later-reaction" to GroupMessageKind.REACTION,
            ),
            mergedUi.map { it.id to it.kind },
        )
        assertTrue("both explicitly mentioned speakers retain their marker", mergedUi.all { it.addressed })
        assertEquals("joining older rows must keep the visible item key", "turn-later-reaction", mergedUi.last().id)
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
