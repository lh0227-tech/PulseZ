package cc.pulsez.app.core

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import cc.pulsez.app.AppContainer
import cc.pulsez.app.data.db.AttachmentEntity
import cc.pulsez.app.data.db.AttachmentKind
import cc.pulsez.app.data.db.AttachmentStatus
import cc.pulsez.app.data.settings.Settings
import cc.pulsez.app.data.settings.SettingsStore
import cc.pulsez.app.mem.MemMessage
import cc.pulsez.app.net.ContentPart
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

/**
 * Offline coverage for credential-scoped DeepSeek history file references.
 * Synthetic binding IDs stand in for local HMAC identifiers; no API key or network request is used.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DeepSeekFileBindingHistoryTest {

    private lateinit var context: Context
    private var container: AppContainer? = null

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.deleteDatabase(APP_DB_NAME)
        runBlocking { SettingsStore(context).update { it.clear() } }
    }

    @After
    fun tearDown() {
        runCatching { container?.database?.close() }
        context.deleteDatabase(APP_DB_NAME)
    }

    @Test
    fun `same binding reuses file IDs and keeps current message reminder and tool history intact`() = runBlocking {
        val fixture = createFixture()
        val prepared = fixture.app.engine.prepare(
            thread = fixture.thread,
            characterId = fixture.characterId,
            userText = fixture.currentText,
            excludeMessageId = fixture.currentMessageId,
            requestSettings = deepSeekSettings,
            remoteCredentialBindingId = BINDING_A,
        )

        val historyBody = prepared.messages.single { it.content == HISTORY_BODY }
        assertEquals(
            listOf(ContentPart.Text(HISTORY_BODY), ContentPart.File(HISTORY_FILE_ID, "history.pdf")),
            historyBody.parts,
        )
        val pureFileHistory = prepared.messages.single { it.content == ChatEngine.FILE_PLACEHOLDER }
        assertEquals(
            listOf(
                ContentPart.Text(ChatEngine.FILE_PLACEHOLDER),
                ContentPart.File(PURE_HISTORY_FILE_ID, "archive.pdf"),
            ),
            pureFileHistory.parts,
        )
        assertHistoryBodyUnavailable(prepared.messages, UNKNOWN_BINDING_BODY)

        val current = prepared.messages.last()
        assertEquals("user", current.role)
        assertEquals(fixture.currentText, current.content)
        assertEquals(
            listOf(ContentPart.Text(fixture.currentText), ContentPart.File(CURRENT_FILE_ID, "current.pdf")),
            current.parts,
        )
        assertEquals("deepseek-chat", prepared.model)
        assertTrue(prepared.latestReminder.isNullOrBlank().not())
        assertEquals(
            listOf("latest_reminder", "user"),
            prepared.messages.takeLast(2).map { it.role },
        )

        val historicalAssistant = prepared.messages.single { it.content == ASSISTANT_BODY }
        assertEquals(TOOL_CALLS_JSON, historicalAssistant.serverToolCallsJson)
        assertEquals("synthetic-reasoning", historicalAssistant.reasoningContent)
        val secondHistoricalAssistant = prepared.messages.single { it.content == SECOND_ASSISTANT_BODY }
        assertEquals(SECOND_TOOL_CALLS_JSON, secondHistoricalAssistant.serverToolCallsJson)
        assertEquals("synthetic-reasoning-2", secondHistoricalAssistant.reasoningContent)

        val reminderMessages = prepared.messages.filter { it.role == "latest_reminder" }
        assertEquals(
            listOf(HISTORY_REMINDER, PURE_FILE_REMINDER, UNKNOWN_FILE_REMINDER, prepared.latestReminder),
            reminderMessages.map { it.content },
        )
        for ((reminder, expectedNextContent) in listOf(
            HISTORY_REMINDER to HISTORY_BODY,
            PURE_FILE_REMINDER to ChatEngine.FILE_PLACEHOLDER,
            UNKNOWN_FILE_REMINDER to "$UNKNOWN_BINDING_BODY\n${ChatEngine.FILE_UNAVAILABLE_PLACEHOLDER}",
        )) {
            val reminderIndex = prepared.messages.indexOfFirst {
                it.role == "latest_reminder" && it.content == reminder
            }
            assertTrue(reminderIndex >= 0)
            assertEquals("latest_reminder must stay immediately before its user turn", "user", prepared.messages[reminderIndex + 1].role)
            assertEquals(expectedNextContent, prepared.messages[reminderIndex + 1].content)
        }
    }

    @Test
    fun `prepare sends only same-binding file from one history row and marks omitted sibling`() = runBlocking {
        val fixture = createFixture()
        fixture.app.database.attachmentDao().upsert(
            attachment(
                id = "history-text-b",
                messageId = fixture.historyMessageId,
                contentFileId = HISTORY_FILE_ID_B,
                bindingId = BINDING_B,
                fileName = "other-account.pdf",
            ),
        )

        val prepared = fixture.app.engine.prepare(
            thread = fixture.thread,
            characterId = fixture.characterId,
            userText = fixture.currentText,
            excludeMessageId = fixture.currentMessageId,
            requestSettings = deepSeekSettings,
            remoteCredentialBindingId = BINDING_A,
        )
        val historyMessage = prepared.messages.single {
            it.content.startsWith(HISTORY_BODY)
        }

        assertEquals(
            "$HISTORY_BODY\n${ChatEngine.FILE_PARTIALLY_UNAVAILABLE_PLACEHOLDER}",
            historyMessage.content,
        )
        assertEquals(
            listOf(
                ContentPart.Text(historyMessage.content),
                ContentPart.File(HISTORY_FILE_ID, "history.pdf"),
            ),
            historyMessage.parts,
        )
        assertEquals(
            listOf(HISTORY_FILE_ID),
            historyMessage.parts.filterIsInstance<ContentPart.File>().map { it.fileId },
        )
        assertFalse(historyMessage.parts.filterIsInstance<ContentPart.File>().any { it.fileId == HISTORY_FILE_ID_B })
    }

    @Test
    fun `switching binding and unknown binding suppress old IDs but retain text and pure file placeholder`() = runBlocking {
        val fixture = createFixture()

        val switched = fixture.app.engine.prepare(
            thread = fixture.thread,
            characterId = fixture.characterId,
            userText = fixture.currentText,
            excludeMessageId = fixture.currentMessageId,
            requestSettings = deepSeekSettings,
            remoteCredentialBindingId = BINDING_B,
        )
        assertHistoryBodyUnavailable(switched.messages, HISTORY_BODY)
        assertPurePlaceholderUnavailable(switched.messages)
        assertHistoryBodyUnavailable(switched.messages, UNKNOWN_BINDING_BODY)
        assertBodyKeptWithoutFile(switched.messages, fixture.currentText)

        val unknown = fixture.app.engine.prepare(
            thread = fixture.thread,
            characterId = fixture.characterId,
            userText = fixture.currentText,
            excludeMessageId = fixture.currentMessageId,
            requestSettings = deepSeekSettings,
            remoteCredentialBindingId = null,
        )
        assertHistoryBodyUnavailable(unknown.messages, HISTORY_BODY)
        assertPurePlaceholderUnavailable(unknown.messages)
        assertHistoryBodyUnavailable(unknown.messages, UNKNOWN_BINDING_BODY)
        assertBodyKeptWithoutFile(unknown.messages, fixture.currentText)

        // Returning to the exact original synthetic binding restores only references owned by A.
        val returnedToA = fixture.app.engine.prepare(
            thread = fixture.thread,
            characterId = fixture.characterId,
            userText = fixture.currentText,
            excludeMessageId = fixture.currentMessageId,
            requestSettings = deepSeekSettings,
            remoteCredentialBindingId = BINDING_A,
        )
        assertEquals(
            listOf(ContentPart.Text(HISTORY_BODY), ContentPart.File(HISTORY_FILE_ID, "history.pdf")),
            returnedToA.messages.single { it.content == HISTORY_BODY }.parts,
        )
        assertEquals(
            listOf(
                ContentPart.Text(ChatEngine.FILE_PLACEHOLDER),
                ContentPart.File(PURE_HISTORY_FILE_ID, "archive.pdf"),
            ),
            returnedToA.messages.single { it.content == ChatEngine.FILE_PLACEHOLDER }.parts,
        )
        assertHistoryBodyUnavailable(returnedToA.messages, UNKNOWN_BINDING_BODY)
    }

    @Test
    fun `request settings snapshot controls provider and model instead of a later stored setting`() = runBlocking {
        val fixture = createFixture()
        // Deliberately make persisted settings disagree with the request snapshot.
        fixture.app.settings.setChatEndpoint("https://proxy.example.invalid/v1", "other-provider-model")

        val deepSeekRequest = fixture.app.engine.prepare(
            thread = fixture.thread,
            characterId = fixture.characterId,
            userText = fixture.currentText,
            excludeMessageId = fixture.currentMessageId,
            requestSettings = deepSeekSettings,
            remoteCredentialBindingId = BINDING_A,
        )
        assertEquals("deepseek-chat", deepSeekRequest.model)
        assertEquals(
            listOf(ContentPart.Text(HISTORY_BODY), ContentPart.File(HISTORY_FILE_ID, "history.pdf")),
            deepSeekRequest.messages.single { it.content == HISTORY_BODY }.parts,
        )
        assertTrue(deepSeekRequest.messages.any { it.role == "latest_reminder" })

        // A non-DeepSeek request with the same binding still cannot serialize DeepSeek file IDs.
        val otherProviderSettings = Settings(
            chatBaseUrl = "https://proxy.example.invalid/v1",
            chatModel = "other-provider-model",
        )
        val otherProviderRequest = fixture.app.engine.prepare(
            thread = fixture.thread,
            characterId = fixture.characterId,
            userText = fixture.currentText,
            excludeMessageId = fixture.currentMessageId,
            requestSettings = otherProviderSettings,
            remoteCredentialBindingId = BINDING_A,
        )
        assertEquals("other-provider-model", otherProviderRequest.model)
        assertBodyKeptWithoutFile(otherProviderRequest.messages, HISTORY_BODY)
        assertPurePlaceholderKeptWithoutFile(otherProviderRequest.messages)
        assertBodyKeptWithoutFile(otherProviderRequest.messages, UNKNOWN_BINDING_BODY)
        assertFalse(otherProviderRequest.messages.any { it.role == "latest_reminder" })
        assertFalse(otherProviderRequest.messages.any { it.serverToolCallsJson != null })
    }

    @Test
    fun `builder fails closed for null attachment binding and non DeepSeek requests`() {
        val bound = attachment(
            id = "row-bound",
            messageId = "message-bound",
            contentFileId = "synthetic-file-a",
            bindingId = BINDING_A,
        )
        val unbound = attachment(
            id = "row-unbound",
            messageId = "message-unbound",
            contentFileId = "synthetic-file-unknown",
            bindingId = null,
        )
        val blankBinding = attachment(
            id = "row-blank-binding",
            messageId = "message-blank-binding",
            contentFileId = "synthetic-file-blank-binding",
            bindingId = " ",
        )
        val mixedBound = attachment(
            id = "row-mixed-a",
            messageId = "message-mixed",
            contentFileId = "synthetic-file-mixed-a",
            bindingId = BINDING_A,
        )
        val mixedUnbound = attachment(
            id = "row-mixed-b",
            messageId = "message-mixed",
            contentFileId = "synthetic-file-mixed-b",
            bindingId = BINDING_B,
        )

        assertEquals(
            listOf(ContentPart.File("synthetic-file-a", "history.pdf")),
            ChatEngine.buildFilePartsByMessageId(listOf(bound), true, BINDING_A)["message-bound"],
        )
        assertNull(ChatEngine.buildFilePartsByMessageId(listOf(bound), true, null)["message-bound"])
        assertNull(ChatEngine.buildFilePartsByMessageId(listOf(bound), true, " ")["message-bound"])
        assertNull(
            ChatEngine.buildFilePartsByMessageId(listOf(bound), true, "$BINDING_A ")["message-bound"],
        )
        assertNull(ChatEngine.buildFilePartsByMessageId(listOf(unbound), true, BINDING_A)["message-unbound"])
        assertNull(
            ChatEngine.buildFilePartsByMessageId(listOf(blankBinding), true, BINDING_A)["message-blank-binding"],
        )
        assertTrue(ChatEngine.buildFilePartsByMessageId(listOf(bound), false, BINDING_A).isEmpty())
        assertEquals(
            listOf(ContentPart.File("synthetic-file-mixed-a", "history.pdf")),
            ChatEngine.buildFilePartsByMessageId(listOf(mixedBound, mixedUnbound), true, BINDING_A)["message-mixed"],
        )
        assertEquals(
            setOf("message-mixed"),
            ChatEngine.unavailableFileMessageIds(listOf(mixedBound, mixedUnbound), true, BINDING_A),
        )
        assertTrue(ChatEngine.unavailableFileMessageIds(listOf(mixedBound), false, BINDING_A).isEmpty())
        assertEquals(
            "$HISTORY_BODY\n${ChatEngine.FILE_PARTIALLY_UNAVAILABLE_PLACEHOLDER}",
            ChatEngine.partiallyUnavailableFileHistoryContent(HISTORY_BODY),
        )
        assertEquals(
            ChatEngine.FILE_PARTIALLY_UNAVAILABLE_PLACEHOLDER,
            ChatEngine.partiallyUnavailableFileHistoryContent(ChatEngine.FILE_PLACEHOLDER),
        )

        val history = listOf(
            MemMessage.system("persona"),
            MemMessage.user(ChatEngine.FILE_PLACEHOLDER, sourceId = "message-bound"),
            MemMessage.user("current", sourceId = "current-message"),
        )
        val outgoing = ChatEngine.buildOutgoingMessages(
            assembled = history,
            recentImageMessageId = null,
            currentImages = emptyList(),
            recentImages = emptyList(),
            currentFiles = emptyList(),
            filePartsByMessageId = ChatEngine.buildFilePartsByMessageId(listOf(bound), false, BINDING_A),
        )
        assertEquals(ChatEngine.FILE_PLACEHOLDER, outgoing[1].content)
        assertTrue(outgoing[1].parts.isEmpty())
    }

    private suspend fun createFixture(): Fixture {
        val app = AppContainer(context)
        container = app
        val (character, thread) = app.characters.createWithThread(
            name = "测试角色",
            persona = "只用于离线测试。",
        )
        val segmentId = thread.activeSegmentId ?: error("test thread must have an active segment")
        val oldUser = app.chat.append(
            threadId = thread.id,
            segmentId = segmentId,
            role = "user",
            text = HISTORY_BODY,
            tokens = 12,
            latestReminder = HISTORY_REMINDER,
        )
        app.database.attachmentDao().upsert(
            attachment("history-text", oldUser.id, HISTORY_FILE_ID, BINDING_A, "history.pdf"),
        )
        app.chat.append(
            threadId = thread.id,
            segmentId = segmentId,
            role = "assistant",
            text = ASSISTANT_BODY,
            tokens = 16,
            reasoningContent = "synthetic-reasoning",
            serverToolCallsJson = TOOL_CALLS_JSON,
        )
        val pureFileUser = app.chat.append(
            threadId = thread.id,
            segmentId = segmentId,
            role = "user",
            text = ChatEngine.FILE_PLACEHOLDER,
            tokens = 4,
            latestReminder = PURE_FILE_REMINDER,
        )
        app.database.attachmentDao().upsert(
            attachment("history-pure", pureFileUser.id, PURE_HISTORY_FILE_ID, BINDING_A, "archive.pdf"),
        )
        app.chat.append(
            threadId = thread.id,
            segmentId = segmentId,
            role = "assistant",
            text = SECOND_ASSISTANT_BODY,
            tokens = 16,
            reasoningContent = "synthetic-reasoning-2",
            serverToolCallsJson = SECOND_TOOL_CALLS_JSON,
        )
        val unknownBindingUser = app.chat.append(
            threadId = thread.id,
            segmentId = segmentId,
            role = "user",
            text = UNKNOWN_BINDING_BODY,
            tokens = 10,
            latestReminder = UNKNOWN_FILE_REMINDER,
        )
        app.database.attachmentDao().upsert(
            attachment("history-unknown", unknownBindingUser.id, UNKNOWN_FILE_ID, null, "unknown.pdf"),
        )
        val current = app.chat.append(
            threadId = thread.id,
            segmentId = segmentId,
            role = "user",
            text = CURRENT_BODY,
            tokens = 8,
        )
        app.database.attachmentDao().upsert(
            attachment("current", current.id, CURRENT_FILE_ID, BINDING_A, "current.pdf"),
        )
        return Fixture(app, character.id, thread, oldUser.id, current.id, CURRENT_BODY)
    }

    private fun attachment(
        id: String,
        messageId: String,
        contentFileId: String,
        bindingId: String?,
        fileName: String = "history.pdf",
    ): AttachmentEntity = AttachmentEntity(
        id = id,
        messageId = messageId,
        kind = AttachmentKind.FILE,
        mime = "application/pdf",
        localPath = "attachments/$id.pdf",
        fileName = fileName,
        sizeBytes = 32,
        width = null,
        height = null,
        status = AttachmentStatus.SENT,
        errorKind = null,
        attempts = 1,
        remoteFileId = contentFileId,
        expiresAt = null,
        deletedAt = null,
        createdAt = 1L,
        ownerThreadId = null,
        remoteCredentialBindingId = bindingId,
    )

    private fun assertBodyKeptWithoutFile(messages: List<cc.pulsez.app.net.ChatMessage>, body: String) {
        val message = messages.single { it.content == body }
        assertTrue(message.parts.none { it is ContentPart.File })
    }

    private fun assertPurePlaceholderKeptWithoutFile(messages: List<cc.pulsez.app.net.ChatMessage>) {
        val message = messages.single { it.content == ChatEngine.FILE_PLACEHOLDER }
        assertTrue(message.parts.none { it is ContentPart.File })
    }

    private fun assertHistoryBodyUnavailable(
        messages: List<cc.pulsez.app.net.ChatMessage>,
        originalBody: String,
    ) {
        val message = messages.single { it.content.startsWith(originalBody) }
        assertTrue(message.content.contains(ChatEngine.FILE_UNAVAILABLE_PLACEHOLDER))
        assertTrue(message.parts.none { it is ContentPart.File })
    }

    private fun assertPurePlaceholderUnavailable(messages: List<cc.pulsez.app.net.ChatMessage>) {
        val message = messages.single { it.content == ChatEngine.FILE_UNAVAILABLE_PLACEHOLDER }
        assertTrue(message.parts.none { it is ContentPart.File })
    }

    private data class Fixture(
        val app: AppContainer,
        val characterId: String,
        val thread: cc.pulsez.app.data.db.ThreadEntity,
        val historyMessageId: String,
        val currentMessageId: String,
        val currentText: String,
    )

    private companion object {
        const val APP_DB_NAME = "pulsez.db"
        const val BINDING_A = "synthetic-binding-a"
        const val BINDING_B = "synthetic-binding-b"
        const val HISTORY_BODY = "旧附件正文仍应保留。"
        const val UNKNOWN_BINDING_BODY = "来源不明附件的正文也要留下。"
        const val ASSISTANT_BODY = "这轮保修到明年。"
        const val SECOND_ASSISTANT_BODY = "我也记得交付日期。"
        const val CURRENT_BODY = "当前消息原样保留。"
        const val HISTORY_FILE_ID = "synthetic-file-history-a"
        const val HISTORY_FILE_ID_B = "synthetic-file-history-b"
        const val PURE_HISTORY_FILE_ID = "synthetic-file-pure-a"
        const val UNKNOWN_FILE_ID = "synthetic-file-unknown-binding"
        const val CURRENT_FILE_ID = "synthetic-file-current-a"
        const val TOOL_CALLS_JSON = "[{\"type\":\"deepseek_web_search\",\"id\":\"synthetic-tool-call\"}]"
        const val SECOND_TOOL_CALLS_JSON = "[{\"type\":\"deepseek_web_search\",\"id\":\"synthetic-tool-call-2\"}]"
        const val HISTORY_REMINDER = "2026-11-20,星期五,,App,中文"
        const val PURE_FILE_REMINDER = "2026-11-21,星期六,,App,中文"
        const val UNKNOWN_FILE_REMINDER = "2026-11-22,星期日,,App,中文"
        val deepSeekSettings = Settings(
            chatBaseUrl = "https://api.deepseek.com/v1",
            chatModel = "deepseek-chat",
        )
    }
}
