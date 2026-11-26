package cc.pulsez.app.ui.chat

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Looper
import androidx.lifecycle.ViewModelStore
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import cc.pulsez.app.AppContainer
import cc.pulsez.app.data.attachments.AttachmentStore
import cc.pulsez.app.data.db.AttachmentKind
import cc.pulsez.app.data.settings.SettingsStore
import cc.pulsez.app.media.DraftStatus
import cc.pulsez.app.net.Citation
import cc.pulsez.app.net.FilesClient
import cc.pulsez.app.net.UploadedFile
import cc.pulsez.app.ui.copy.ChatActionCopy
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.first
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
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
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * `ChatViewModel` v0.6.10 闭环离线行为测试：
 * - 联网搜索 `ToolProgress` / `Finished` 落库 `reasoningContent` + `serverToolCallsJson` + `citationsJson` 及 `load()` 恢复；
 * - 失败占位原地重试（不插第二条用户消息，保持 `id` 不变，首次落 assistant 行）；
 * - 重新生成最后一条助手回复（软删旧 assistant 行，复用用户消息生成新回复）；
 * - 单条消息软删传播及本地附件文件清理；
 * - 非识图 DeepSeek 端点拆门禁、`onFilePicked` 体积上限卡控与无密钥发送保留输入/草稿。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ChatViewModelAlignmentTest {

    private companion object {
        const val APP_DB_NAME = "pulsez.db"
    }

    private lateinit var context: Context
    private lateinit var server: MockWebServer
    private lateinit var container: AppContainer
    private lateinit var characterId: String
    private lateinit var threadId: String
    private val secrets = mutableMapOf<String, String>()
    private val viewModelStores = mutableListOf<ViewModelStore>()
    private val tempFiles = mutableListOf<File>()

    @Before
    fun setUp() = runBlocking {
        context = ApplicationProvider.getApplicationContext()
        context.deleteDatabase(APP_DB_NAME)
        SettingsStore(context).update { it.clear() }
        AttachmentStore.install(AttachmentStore.forContext(context))
        secrets.clear()
        server = MockWebServer()
        server.start()
        container = AppContainer(
            context,
            secretReader = { name -> secrets[name] },
            secretRemover = { name -> secrets.remove(name) },
            secretWriter = { name, value -> secrets[name] = value },
        )
        container.saveChatCredential(
            baseUrl = server.url("/v1").toString(),
            model = "deepseek-chat",
            keyInput = "test-key",
        )
        val (character, thread) = container.characters.createWithThread(name = "阿淮", persona = "温和")
        characterId = character.id
        threadId = thread.id
    }

    @After
    fun tearDown() {
        viewModelStores.forEach { runCatching { it.clear() } }
        viewModelStores.clear()
        tempFiles.forEach { runCatching { it.delete() } }
        tempFiles.clear()
        runCatching { shadowOf(Looper.getMainLooper()).idle() }
        runCatching { container.database.close() }
        runCatching { server.shutdown() }
        AttachmentStore.resetForTest()
        secrets.clear()
        context.deleteDatabase(APP_DB_NAME)
    }

    private fun createVm(
        charId: String = characterId,
        waitForReady: Boolean = true,
        beforeLoadPublishForTest: (suspend () -> Unit)? = null,
        beforeLoadOlderPublishForTest: (suspend () -> Unit)? = null,
    ): ChatViewModel {
        val store = ViewModelStore()
        viewModelStores += store
        val vm = ChatViewModel(container, charId, beforeLoadPublishForTest, beforeLoadOlderPublishForTest)
        store.put("chat-${viewModelStores.size}", vm)
        if (waitForReady) waitUntil(vm) { it.ready }
        return vm
    }

    private fun waitUntil(vm: ChatViewModel, predicate: (ChatViewModel.UiState) -> Boolean) {
        val deadline = System.currentTimeMillis() + 8_000L
        while (System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            if (predicate(vm.state.value)) return
            Thread.sleep(20L)
        }
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue("等待 ChatViewModel 状态超时：${vm.state.value}", predicate(vm.state.value))
    }

    // AttachmentDao.byId is a suspending query; wait for the soft-delete to appear in the database.
    private suspend fun waitForAttachmentSoftDelete(attachmentId: String) {
        val deadline = System.currentTimeMillis() + 8_000L
        while (System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            if (container.database.attachmentDao().byId(attachmentId)?.deletedAt != null) return
            delay(20L)
        }
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(
            "等待附件草稿软删除超时：$attachmentId",
            container.database.attachmentDao().byId(attachmentId)?.deletedAt != null,
        )
    }

    private fun waitForRequestCount(expected: Int) {
        val deadline = System.currentTimeMillis() + 8_000L
        while (System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            if (server.requestCount >= expected) return
            Thread.sleep(20L)
        }
        assertEquals("MockWebServer 请求数", expected, server.requestCount)
    }

    @Test
    fun `remote file reuse requires the same known binding`() {
        assertTrue(canReuseRemoteFile("file-a", "binding-a", "binding-a"))
        assertFalse(canReuseRemoteFile("file-a", "binding-a", "binding-b"))
        assertFalse(canReuseRemoteFile("file-a", null, "binding-a"))
        assertFalse(canReuseRemoteFile("file-a", "binding-a", null))
        assertFalse(canReuseRemoteFile(null, "binding-a", "binding-a"))
        assertFalse(canReuseRemoteFile(" ", "binding-a", "binding-a"))
    }

    @Test
    fun `credential switch after upload keeps local draft and queues its original binding when removed`() = runBlocking {
        container.settings.setChatEndpoint("https://api.deepseek.com", "deepseek-chat")
        val baseSettings = container.settings.flow.first().copy(
            chatBaseUrl = "https://api.deepseek.com",
            chatModel = "deepseek-chat",
        )
        val sessionA = AppContainer.ChatEndpointSession(
            baseUrl = "https://api.deepseek.com",
            apiKey = "offline-key-a",
            remoteCredentialBindingId = "binding-a",
            settingsSnapshot = baseSettings,
        )
        val sessionB = AppContainer.ChatEndpointSession(
            baseUrl = "https://api.deepseek.com",
            apiKey = "offline-key-b",
            remoteCredentialBindingId = "binding-b",
            settingsSnapshot = baseSettings,
        )
        var sessionReads = 0
        var uploadCalls = 0
        val vm = createVm()
        vm.endpointSessionResolverForTest = {
            sessionReads += 1
            if (sessionReads == 1) sessionA else sessionB
        }
        vm.fileUploaderForTest = { file, name, purpose ->
            assertTrue("只读本地副本交给离线上传 fake", file.isFile)
            uploadCalls += 1
            UploadedFile("uploaded-a", file.length(), name, purpose, expiresAt = null)
        }

        val source = File(context.cacheDir, "binding-switch.txt").apply { writeText("offline file") }
        tempFiles += source
        waitUntil(vm) { it.fileInputEnabled }
        vm.onFilePicked(Uri.fromFile(source), context.contentResolver)
        waitUntil(vm) { it.fileDrafts.singleOrNull()?.status == DraftStatus.READY }
        val draftId = vm.state.value.fileDrafts.single().id
        vm.onInputChange("请看文件")
        vm.send()
        waitUntil(vm) { it.notice == ChatActionCopy.FILE_BINDING_CHANGED && !it.uploadingFiles }

        assertEquals("只有一次离线上传", 1, uploadCalls)
        assertEquals("上传前后只取 A 和 B 两个快照", 2, sessionReads)
        assertEquals("切换凭据时不能发聊天请求", 0, server.requestCount)
        assertEquals("正文应保留以便重试", "请看文件", vm.state.value.input)
        assertEquals("本地文件草稿应保留", 1, vm.state.value.fileDrafts.size)
        val persisted = requireNotNull(container.database.attachmentDao().byId(draftId))
        assertEquals("新文件 ID 必须保存上传时的 binding", "uploaded-a", persisted.remoteFileId)
        assertEquals("新文件 ID 必须保存上传时的 binding", "binding-a", persisted.remoteCredentialBindingId)

        vm.removeFileDraft(draftId)
        waitForAttachmentSoftDelete(draftId)
        assertEquals(
            "删除草稿只用原 binding 入队，不能用当前 B 凭据覆盖",
            listOf("uploaded-a" to "binding-a"),
            container.remoteFileCleanup.entries().map { it.remoteFileId to it.credentialBindingId },
        )
        assertEquals("草稿清理不应直接请求远端", 0, server.requestCount)
    }

    @Test
    fun `wipe after upload returned ID queues it without restoring attachment`() = runBlocking {
        container.settings.setChatEndpoint("https://api.deepseek.com", "deepseek-chat")
        val sessionSettings = container.settings.flow.first().copy(
            chatBaseUrl = "https://api.deepseek.com",
            chatModel = "deepseek-chat",
        )
        val sessionA = AppContainer.ChatEndpointSession(
            baseUrl = "https://api.deepseek.com",
            apiKey = "offline-key-a",
            remoteCredentialBindingId = "binding-a",
            settingsSnapshot = sessionSettings,
        )
        val vm = createVm()
        vm.endpointSessionResolverForTest = { sessionA }
        vm.fileUploaderForTest = { file, name, purpose ->
            UploadedFile("uploaded-before-wipe", file.length(), name, purpose, expiresAt = null)
        }
        val beforePersist = CompletableDeferred<String>()
        val allowPersist = CompletableDeferred<Unit>()
        vm.beforePersistUploadedFileForTest = { uploadedId ->
            beforePersist.complete(uploadedId)
            allowPersist.await()
        }

        val source = File(context.cacheDir, "wipe-after-upload.txt").apply { writeText("offline file") }
        tempFiles += source
        waitUntil(vm) { it.fileInputEnabled }
        vm.onFilePicked(Uri.fromFile(source), context.contentResolver)
        waitUntil(vm) { it.fileDrafts.singleOrNull()?.status == DraftStatus.READY }
        val draftId = vm.state.value.fileDrafts.single().id
        vm.onInputChange("请看文件")
        vm.send()
        try {
            waitUntil(vm) { beforePersist.isCompleted }
            assertEquals("测试注入点确认上传已返回服务端 ID", "uploaded-before-wipe", beforePersist.await())

            // Hold the real post-upload handoff, clear Room, then let its NonCancellable bind-or-enqueue finish.
            container.notifyUserDataWiped()
            withContext(Dispatchers.IO) { container.database.clearAllTables() }
            allowPersist.complete(Unit)

            val deadline = System.currentTimeMillis() + 8_000L
            var cleanupEntry: List<Pair<String, String?>> = emptyList()
            while (System.currentTimeMillis() < deadline) {
                shadowOf(Looper.getMainLooper()).idle()
                cleanupEntry = container.remoteFileCleanup.entries().map { it.remoteFileId to it.credentialBindingId }
                if (cleanupEntry.contains("uploaded-before-wipe" to "binding-a")) break
                Thread.sleep(20L)
            }
            assertEquals(listOf("uploaded-before-wipe" to "binding-a"), cleanupEntry)
            assertNull("清空后的附件行不能被迟到上传回写复活", container.database.attachmentDao().byId(draftId))
            assertTrue("清空后的消息不能被迟到发送复活", container.database.messageDao().allForThread(threadId).isEmpty())
            assertEquals("离线 fake 路径不得请求真实服务", 0, server.requestCount)
        } finally {
            allowPersist.complete(Unit)
        }
    }

    @Test
    fun `partial upload survives failure and retry reuses only original binding`() = runBlocking {
        container.settings.setChatEndpoint("https://api.deepseek.com", "deepseek-chat")
        val sessionSettings = container.settings.flow.first().copy(
            chatBaseUrl = "https://api.deepseek.com",
            chatModel = "deepseek-chat",
        )
        val sessionA = AppContainer.ChatEndpointSession(
            baseUrl = "https://api.deepseek.com",
            apiKey = "offline-key-a",
            remoteCredentialBindingId = "binding-a",
            settingsSnapshot = sessionSettings,
        )
        val sessionB = AppContainer.ChatEndpointSession(
            baseUrl = "https://api.deepseek.com",
            apiKey = "offline-key-b",
            remoteCredentialBindingId = "binding-b",
            settingsSnapshot = sessionSettings,
        )
        val vm = createVm()
        var sessionReads = 0
        vm.endpointSessionResolverForTest = {
            sessionReads += 1
            if (sessionReads == 3) sessionB else sessionA
        }
        var uploadCalls = 0
        vm.fileUploaderForTest = { file, name, purpose ->
            assertTrue(file.isFile)
            uploadCalls += 1
            when (uploadCalls) {
                1 -> UploadedFile("partial-a", file.length(), name, purpose, expiresAt = null)
                2 -> throw IOException("offline transport failure")
                else -> UploadedFile("second-a", file.length(), name, purpose, expiresAt = null)
            }
        }

        waitUntil(vm) { it.fileInputEnabled }
        val firstFile = File(context.cacheDir, "partial-one.txt").apply { writeText("one") }
        val secondFile = File(context.cacheDir, "partial-two.txt").apply { writeText("two") }
        tempFiles += firstFile
        tempFiles += secondFile
        vm.onFilePicked(Uri.fromFile(firstFile), context.contentResolver)
        waitUntil(vm) { it.fileDrafts.size == 1 && it.fileDrafts[0].status == DraftStatus.READY }
        vm.onFilePicked(Uri.fromFile(secondFile), context.contentResolver)
        waitUntil(vm) { it.fileDrafts.size == 2 && it.fileDrafts.all { draft -> draft.status == DraftStatus.READY } }
        val draftIds = vm.state.value.fileDrafts.map { it.id }
        vm.onInputChange("比较两个文件")

        vm.send()
        waitUntil(vm) { it.error == ChatActionCopy.FILE_UPLOAD_FAILED && !it.uploadingFiles }
        assertEquals("首个已成功文件继续留在草稿", "partial-a", container.database.attachmentDao().byId(draftIds[0])?.remoteFileId)
        assertEquals("失败的第二份草稿也保留", null, container.database.attachmentDao().byId(draftIds[1])?.remoteFileId)
        assertEquals("失败后两个本地草稿都还在", 2, vm.state.value.fileDrafts.size)

        // Retry under the original A binding: the first ID is reused, only the failed second upload is retried.
        vm.send()
        waitUntil(vm) { it.notice == ChatActionCopy.FILE_BINDING_CHANGED && !it.uploadingFiles }
        assertEquals("A/A 项复用旧文件，B 快照使该轮在发送前停止", 3, uploadCalls)
        assertEquals("重试门禁已释放且正文保留", "比较两个文件", vm.state.value.input)
        assertEquals("两个服务端 ID 都仍绑定 A", listOf("binding-a", "binding-a"),
            draftIds.map { container.database.attachmentDao().byId(it)?.remoteCredentialBindingId })
        assertEquals("没有发送聊天请求", 0, server.requestCount)

        draftIds.forEach(vm::removeFileDraft)
        val deadline = System.currentTimeMillis() + 8_000L
        var entries: List<Pair<String, String?>> = emptyList()
        while (System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            entries = container.remoteFileCleanup.entries().map { it.remoteFileId to it.credentialBindingId }
            if (entries.size == 2) break
            Thread.sleep(20L)
        }
        assertEquals(
            listOf("partial-a" to "binding-a", "second-a" to "binding-a"),
            entries.sortedBy { it.first },
        )
    }

    @Test
    fun `stop during upload without returned ID warns and releases retry gate`() = runBlocking {
        container.settings.setChatEndpoint("https://api.deepseek.com", "deepseek-chat")
        val sessionSettings = container.settings.flow.first().copy(
            chatBaseUrl = "https://api.deepseek.com",
            chatModel = "deepseek-chat",
        )
        val sessionA = AppContainer.ChatEndpointSession(
            baseUrl = "https://api.deepseek.com",
            apiKey = "offline-key-a",
            remoteCredentialBindingId = "binding-a",
            settingsSnapshot = sessionSettings,
        )
        val sessionB = AppContainer.ChatEndpointSession(
            baseUrl = "https://api.deepseek.com",
            apiKey = "offline-key-b",
            remoteCredentialBindingId = "binding-b",
            settingsSnapshot = sessionSettings,
        )
        val vm = createVm()
        var sessionReads = 0
        vm.endpointSessionResolverForTest = {
            sessionReads += 1
            if (sessionReads >= 3) sessionB else sessionA
        }
        val uploadStarted = CompletableDeferred<Unit>()
        vm.fileUploaderForTest = { _, _, _ ->
            uploadStarted.complete(Unit)
            awaitCancellation()
        }

        waitUntil(vm) { it.fileInputEnabled }
        val source = File(context.cacheDir, "stop-before-id.txt").apply { writeText("offline file") }
        tempFiles += source
        vm.onFilePicked(Uri.fromFile(source), context.contentResolver)
        waitUntil(vm) { it.fileDrafts.singleOrNull()?.status == DraftStatus.READY }
        val draftId = vm.state.value.fileDrafts.single().id
        vm.onInputChange("请看文件")
        vm.send()
        waitUntil(vm) { uploadStarted.isCompleted }
        vm.stop()
        waitUntil(vm) { it.notice == ChatActionCopy.FILE_UPLOAD_CANCELLED_UNCONFIRMED && !it.uploadingFiles }

        assertEquals("没有收到 ID 时不能伪造远端清理队列", emptyList<Pair<String, String?>>(),
            container.remoteFileCleanup.entries().map { it.remoteFileId to it.credentialBindingId })
        assertNull("未返回 ID 时草稿不能记录远端句柄",
            container.database.attachmentDao().byId(draftId)?.remoteFileId)
        assertEquals("停止后文本和本地文件保留", "请看文件", vm.state.value.input)

        vm.fileUploaderForTest = { file, name, purpose ->
            UploadedFile("retry-upload-a", file.length(), name, purpose, expiresAt = null)
        }
        vm.send()
        waitUntil(vm) { it.notice == ChatActionCopy.FILE_BINDING_CHANGED && !it.uploadingFiles }
        assertEquals("停止后可以重新尝试上传", 3, sessionReads)
        assertEquals("再次失败边界前没有发送聊天请求", 0, server.requestCount)
    }

    @Test
    fun `wipe during load snapshot prevents stale history from being published`() = runBlocking {
        val thread = requireNotNull(container.database.threadDao().byId(threadId))
        val segmentId = requireNotNull(thread.activeSegmentId)
        container.chat.append(threadId, segmentId, "user", "清空前的旧消息", 5, createdAt = 10_000L)
        container.chat.append(threadId, segmentId, "assistant", "旧回复", 3, createdAt = 11_000L)

        val loadReachedPublish = CompletableDeferred<Unit>()
        val allowPublish = CompletableDeferred<Unit>()
        val vm = createVm(
            waitForReady = false,
            beforeLoadPublishForTest = {
                loadReachedPublish.complete(Unit)
                allowPublish.await()
            },
        )
        try {
            waitUntil(vm) { loadReachedPublish.isCompleted }
            assertFalse(vm.state.value.ready)

            // Hold the completed read snapshot, then perform the production order: bump epoch,
            // clear Room, and only then let load() reach its final publish guard.
            container.notifyUserDataWiped()
            withContext(Dispatchers.IO) { container.database.clearAllTables() }
            allowPublish.complete(Unit)
            shadowOf(Looper.getMainLooper()).idle()

            assertFalse("被清空代际作废的加载不得把 ready 提前改成 true", vm.state.value.ready)
            assertTrue("旧历史不得覆盖清空后的初始 UI", vm.state.value.messages.isEmpty())
            assertEquals("清空前读到的角色名不得写回", "", vm.state.value.name)
        } finally {
            allowPublish.complete(Unit)
        }
    }

    @Test
    fun `wipe after older page snapshot prevents stale history from being prepended`() = runBlocking {
        val thread = requireNotNull(container.database.threadDao().byId(threadId))
        val segmentId = requireNotNull(thread.activeSegmentId)
        repeat(81) { index ->
            container.chat.append(
                threadId,
                segmentId,
                "user",
                "分页历史-$index",
                1,
                createdAt = 20_000L + index,
            )
        }

        val pageReachedPublish = CompletableDeferred<Unit>()
        val allowPublish = CompletableDeferred<Unit>()
        val vm = createVm(
            beforeLoadOlderPublishForTest = {
                pageReachedPublish.complete(Unit)
                allowPublish.await()
            },
        )
        val loadedIds = vm.state.value.messages.map { it.id }
        val oldestHistoryId = requireNotNull(
            container.database.messageDao().allForThread(threadId).minByOrNull { it.createdAt },
        ).id
        assertTrue("首屏满页时应允许继续读取更早历史", vm.state.value.hasMoreOlder)
        assertFalse(loadedIds.contains(oldestHistoryId))

        try {
            vm.loadOlder()
            waitUntil(vm) { pageReachedPublish.isCompleted }
            assertTrue("读取期间保留 loadingOlder", vm.state.value.loadingOlder)

            // Hold the completed older-page, attachment, and import-source snapshot, then clear
            // Room before allowing the final generation guard to publish it.
            container.notifyUserDataWiped()
            withContext(Dispatchers.IO) { container.database.clearAllTables() }
            allowPublish.complete(Unit)
            waitUntil(vm) { !it.loadingOlder }

            assertEquals("清空前已经显示的首屏保持原样，不得前插迟到历史", loadedIds, vm.state.value.messages.map { it.id })
            assertFalse("迟到页不得复活清空前最早消息", vm.state.value.messages.any { it.id == oldestHistoryId })
            assertTrue("只读请求被清空代际作废不应改成分页错误", vm.state.value.error == null)
        } finally {
            allowPublish.complete(Unit)
        }
    }

    private fun sseResponse(vararg frames: String): MockResponse {
        val body = buildString {
            frames.forEach { append("data: ").append(it).append("\n\n") }
            append("data: [DONE]\n\n")
        }
        return MockResponse()
            .setResponseCode(200)
            .setHeader("Content-Type", "text/event-stream")
            .setBody(body)
    }

    @Test
    fun `message action fence linearizes stop and transaction commit`() {
        val stoppedBeforeCommit = MessageActionFence()
        assertTrue(stoppedBeforeCommit.cancelBeforeCommit())
        assertFalse(stoppedBeforeCommit.beginCommit { true })
        assertFalse(stoppedBeforeCommit.canContinue())

        val wipedBeforeCommit = MessageActionFence()
        assertFalse(wipedBeforeCommit.beginCommit { false })
        assertFalse(wipedBeforeCommit.canContinue())

        val committed = MessageActionFence()
        assertTrue(committed.beginCommit { true })
        assertTrue(committed.canContinue())
        assertFalse("Stop must not cancel after the transaction owns the fence", committed.cancelBeforeCommit())
        committed.complete()
        assertFalse(committed.canContinue())
        assertFalse(committed.cancelBeforeCommit())
    }

    @Test
    fun `DeepSeek refused response state removes body reasoning citations and tool progress`() {
        val refused = ChatViewModel.refusedMessageState(
            ChatUiMessage(
                id = "local-assistant",
                role = "assistant",
                text = "流出的正文前缀",
                reasoning = "已流出的思考",
                citations = listOf(Citation(url = "https://example.com")),
                dbId = "must-not-survive",
                toolProgressText = "正在搜索",
            ),
            targetUserDbId = "user-row",
        )

        assertEquals(ChatViewModel.REPLY_FAILED_PLACEHOLDER, refused.text)
        assertTrue(refused.failed)
        assertNull(refused.dbId)
        assertNull(refused.reasoning)
        assertTrue(refused.citations.isEmpty())
        assertNull(refused.toolProgressText)
        assertEquals("user-row", refused.targetUserDbId)
    }

    @Test
    fun `finished persists reasoning serverToolCalls and citations and load restores them`() = runBlocking {
        val rawToolCalls =
            """[{"index":1,"reasoning_content":"第一步思考","calls":[{"index":0,"id":"srv_1","type":"deepseek_web_search","signature":"sig_abc"}]}]"""
        server.enqueue(
            sseResponse(
                """{"choices":[{"delta":{"reasoning_content":"第一步思考"}}]}""",
                """{"choices":[{"delta":{},"server_tool_event":{"type":"step_reasoning_done","step":1}}]}""",
                """{"choices":[{"delta":{},"server_tool_event":{"type":"call_started","step":1,"call":{"kind":"search","status":"in_progress","action":{"type":"search","queries":["杭州天气"]}}}}]}""",
                """{"choices":[{"delta":{"reasoning_content":"末步整理"}}]}""",
                """{"choices":[{"delta":{"content":"今天晴。"}}]}""",
                """{"choices":[{"delta":{"server_tool_calls":$rawToolCalls},"annotations":[{"type":"url_citation","url_citation":{"url":"https://weather.example/hz","title":"杭州气象","start_index":0,"end_index":3}}],"finish_reason":"stop"}]}""",
                """{"choices":[],"usage":{"prompt_tokens":12,"completion_tokens":8,"total_tokens":20}}""",
            ),
        )

        val vm = createVm()
        vm.onInputChange("杭州今天天气")
        vm.send()
        waitUntil(vm) { !it.streaming && it.messages.size == 2 }

        val userUi = vm.state.value.messages[0]
        val assistantUi = vm.state.value.messages[1]
        assertNotNull("发送后 UI 用户消息应记录真实 dbId", userUi.dbId)
        assertNotNull("结束后 UI 助手消息应记录真实 dbId", assistantUi.dbId)
        assertNull("结束后应清空临时工具进度", assistantUi.toolProgressText)
        assertEquals("今天晴。", assistantUi.text)
        assertEquals("第一步思考\n\n末步整理", assistantUi.reasoning)
        assertEquals(
            listOf(Citation(url = "https://weather.example/hz", title = "杭州气象", startIndex = 0, endIndex = 3)),
            assistantUi.citations,
        )

        // 绑定一个普通文件到该用户消息，验证重开页面后 load() 恢复 fileNames / reasoning / citations
        val createdFile = container.attachments.createFileDraft(
            store = container.attachmentStore,
            mime = "text/plain",
            stream = ByteArrayInputStream("hello".toByteArray()),
            fileName = "天气备忘.txt",
            ownerThreadId = threadId,
        )
        container.attachments.markReady(createdFile.id)
        container.attachments.bindToMessage(listOf(createdFile.id), userUi.dbId!!)

        val reloadedVm = createVm()
        val reloadedUser = reloadedVm.state.value.messages[0]
        val reloadedAssistant = reloadedVm.state.value.messages[1]
        assertEquals(listOf("天气备忘.txt"), reloadedUser.fileNames)
        assertEquals("第一步思考\n\n末步整理", reloadedAssistant.reasoning)
        assertEquals(
            listOf(Citation(url = "https://weather.example/hz", title = "杭州气象", startIndex = 0, endIndex = 3)),
            reloadedAssistant.citations,
        )
    }

    @Test
    fun `retryMessage on failed placeholder reuses user message without inserting duplicate`() = runBlocking {
        // 首次失败（401 不触发瞬态重试，立刻失败）
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"error":{"message":"bad key"}}"""))
        server.enqueue(
            sseResponse(
                """{"choices":[{"delta":{"content":"重连成功。"},"finish_reason":"stop"}]}""",
            ).setHeadersDelay(350L, TimeUnit.MILLISECONDS),
        )

        val vm = createVm()
        vm.onInputChange("还在吗")
        vm.send()
        waitUntil(vm) { !it.streaming && it.messages.size == 2 && it.messages[1].failed }

        val failedPlaceholder = vm.state.value.messages[1]
        val originalUiId = failedPlaceholder.id
        assertEquals(ChatViewModel.REPLY_FAILED_PLACEHOLDER, failedPlaceholder.text)
        assertNotNull(failedPlaceholder.targetUserDbId)

        vm.retryMessage(originalUiId)
        // 第一调用同步占位后，重试/重生成交叉触发只能共享这一条请求。
        vm.regenerateMessage(originalUiId)
        vm.retryMessage(originalUiId)
        waitForRequestCount(2)
        waitUntil(vm) { !it.streaming && it.messages.size == 2 && !it.messages[1].failed && it.messages[1].text == "重连成功。" }
        assertEquals("连续重试与交叉重生成只能发一条新请求", 2, server.requestCount)

        val retried = vm.state.value.messages[1]
        assertEquals("原地重试不得更换 UI item id", originalUiId, retried.id)
        assertNotNull("重试成功后首次落库 assistant 行", retried.dbId)

        val dbRows = container.chat.chatTailInSegmentOrder(threadId, limit = 20)
        assertEquals("数据库只能有 1 条用户消息与 1 条助手消息", 2, dbRows.size)
        assertEquals("user", dbRows[0].message.role)
        assertEquals("assistant", dbRows[1].message.role)
        assertEquals("重连成功。", dbRows[1].message.content)
    }

    @Test
    fun `local optimistic user message cannot be deleted while Room append is waiting`() = runBlocking {
        server.enqueue(
            sseResponse(
                """{"choices":[{"delta":{"content":"已收到。"},"finish_reason":"stop"}]}""",
            ).setHeadersDelay(500L, TimeUnit.MILLISECONDS),
        )
        val vm = createVm()
        val transactionHeld = CompletableDeferred<Unit>()
        val releaseTransaction = CompletableDeferred<Unit>()
        val holder = launch(Dispatchers.IO) {
            container.database.withTransaction {
                transactionHeld.complete(Unit)
                releaseTransaction.await()
            }
        }
        try {
            transactionHeld.await()

            vm.onInputChange("保留这条本地消息")
            vm.send()
            waitUntil(vm) {
                it.streaming && it.messages.any { message -> message.role == "user" && message.dbId == null }
            }
            val localUser = vm.state.value.messages.first { it.role == "user" }
            vm.deleteMessage(localUser.id)
            assertTrue("发送期间本地占位消息仍应留在 UI", vm.state.value.messages.any { it.id == localUser.id })
            assertNull(localUser.dbId)
            assertEquals("Room 锁释放前不应发起生成请求", 0, server.requestCount)

            releaseTransaction.complete(Unit)
            holder.join()
            waitForRequestCount(1)
            waitUntil(vm) { !it.streaming }
            assertNotNull(vm.state.value.messages.first { it.id == localUser.id }.dbId)
            assertEquals(1, container.chat.chatTailInSegmentOrder(threadId, limit = 10).count { it.message.role == "user" })
        } finally {
            releaseTransaction.complete(Unit)
        }
    }

    @Test
    fun `Stop before Room grants user append fence leaves no durable row or request`() = runBlocking {
        val vm = createVm()
        val transactionHeld = CompletableDeferred<Unit>()
        val releaseTransaction = CompletableDeferred<Unit>()
        val holder = launch(Dispatchers.IO) {
            container.database.withTransaction {
                transactionHeld.complete(Unit)
                releaseTransaction.await()
            }
        }
        try {
            transactionHeld.await()

            vm.onInputChange("停止后不应落库")
            vm.send()
            waitUntil(vm) {
                it.streaming && it.messages.any { message -> message.role == "user" && message.dbId == null }
            }
            val localUser = vm.state.value.messages.first { it.role == "user" }
            vm.stop()
            assertFalse(vm.state.value.streaming)

            releaseTransaction.complete(Unit)
            holder.join()
            waitUntil(vm) { !it.streaming }
            assertNull(container.database.messageDao().byId(localUser.id))
            assertTrue(container.chat.chatTailInSegmentOrder(threadId, limit = 10).isEmpty())
            assertEquals(0, server.requestCount)
            assertTrue("本地发送内容在停止后仍保留", vm.state.value.messages.any { it.id == localUser.id })
        } finally {
            releaseTransaction.complete(Unit)
        }
    }

    @Test
    fun `wipe during a locked image send does not bind or restore message or attachment`() = runBlocking {
        // The fixture's default endpoint is MockWebServer; switch only the provider classification
        // so the image gate uses the same DeepSeek/model capability combination as production.
        // Remove its MockWebServer-bound key first so a regression cannot contact the official host.
        container.clearChatCredential()
        container.settings.setChatEndpoint("https://api.deepseek.com", "deepseek-chat")
        container.settings.setVisionProbe("deepseek-chat", supported = true)
        container.settings.setImageIntroSeen(true)
        val image = File(context.cacheDir, "wipe-send-${System.nanoTime()}.jpg")
        val bitmap = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
        try {
            FileOutputStream(image).use { output ->
                check(bitmap.compress(Bitmap.CompressFormat.JPEG, 90, output))
            }
        } finally {
            bitmap.recycle()
        }
        tempFiles += image

        val vm = createVm()
        vm.onImagePicked(Uri.fromFile(image))
        waitUntil(vm) { it.drafts.size == 1 && it.drafts.single().status == DraftStatus.READY }
        val draftId = vm.state.value.drafts.single().id
        val draftLocalPath = requireNotNull(container.database.attachmentDao().byId(draftId)?.localPath)

        val transactionHeld = CompletableDeferred<Unit>()
        val releaseTransaction = CompletableDeferred<Unit>()
        val holder = launch(Dispatchers.IO) {
            container.database.withTransaction {
                transactionHeld.complete(Unit)
                releaseTransaction.await()
            }
        }
        try {
            transactionHeld.await()

            vm.onInputChange("这张图不要在清空后复活")
            vm.send()
            waitUntil(vm) {
                it.streaming && it.messages.any { message -> message.role == "user" && message.dbId == null }
            }
            val localUser = vm.state.value.messages.first { it.role == "user" }
            assertEquals(listOf(draftLocalPath), localUser.imagePaths)

            // Match AppRoot's ordering: advance the generation before clearing Room.
            container.notifyUserDataWiped()
            val clear = async(Dispatchers.IO) {
                container.database.clearAllTables()
            }
            releaseTransaction.complete(Unit)
            holder.join()
            clear.await()
            waitUntil(vm) { !it.streaming }

            assertNull("清空后旧 UI 不得伪称本地占位已持久化", vm.state.value.messages.first { it.id == localUser.id }.dbId)
            assertNull(container.database.attachmentDao().byId(draftId))
            val draftFile = container.attachmentStore.path(draftLocalPath)
            assertTrue("Room 清表不能重新绑定或删除该测试草稿文件", draftFile.exists())
            container.attachmentStore.delete(draftLocalPath)
            assertFalse(draftFile.exists())
            assertEquals("清空期间不得发出生成请求", 0, server.requestCount)

            val (freshCharacter, _) = container.characters.createWithThread("空白对话", "新的空白对话")
            val reopened = createVm(freshCharacter.id)
            assertTrue(reopened.state.value.messages.isEmpty())
        } finally {
            releaseTransaction.complete(Unit)
            container.attachmentStore.delete(draftLocalPath)
        }
    }

    @Test
    fun `delete action blocks overlapping retry and regenerate requests`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"error":{"message":"bad key"}}"""))

        val vm = createVm()
        vm.onInputChange("需要保留操作顺序")
        vm.send()
        waitUntil(vm) { !it.streaming && it.messages.size == 2 && it.messages[1].failed }
        val user = vm.state.value.messages[0]
        val failedAssistant = vm.state.value.messages[1]
        val userDbId = requireNotNull(user.dbId)

        // deleteMessage reserves synchronously; these cross-calls must not launch another request.
        vm.deleteMessage(user.id)
        vm.retryMessage(failedAssistant.id)
        vm.regenerateMessage(failedAssistant.id)

        waitUntil(vm) { it.messages.none { message -> message.id == user.id } }
        assertEquals("删除占位期间 retry/regenerate 不得发出请求", 1, server.requestCount)
        assertNotNull(container.database.messageDao().byId(userDbId)?.deletedAt)
    }

    @Test
    fun `retry action blocks overlapping delete and regenerate`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"error":{"message":"bad key"}}"""))
        server.enqueue(
            sseResponse("""{"choices":[{"delta":{"content":"重试完成。"},"finish_reason":"stop"}]}""")
                .setHeadersDelay(350L, TimeUnit.MILLISECONDS),
        )

        val vm = createVm()
        vm.onInputChange("重试占位门禁")
        vm.send()
        waitUntil(vm) { !it.streaming && it.messages.size == 2 && it.messages[1].failed }
        val user = vm.state.value.messages[0]
        val failedAssistant = vm.state.value.messages[1]
        val userDbId = requireNotNull(user.dbId)

        vm.retryMessage(failedAssistant.id)
        vm.deleteMessage(user.id)
        vm.regenerateMessage(failedAssistant.id)

        waitForRequestCount(2)
        waitUntil(vm) { !it.streaming && it.messages.size == 2 && it.messages[1].text == "重试完成。" }
        assertEquals("重试占位期间 delete/regenerate 不得产生额外请求", 2, server.requestCount)
        assertNull("被门禁拒绝的删除不得软删用户行", container.database.messageDao().byId(userDbId)?.deletedAt)
        assertTrue(vm.state.value.messages.any { it.id == user.id })
    }

    @Test
    fun `regenerate failure keeps old reply across reopen then retry atomically replaces it`() = runBlocking {
        server.enqueue(
            sseResponse("""{"choices":[{"delta":{"content":"第一版回答"},"finish_reason":"stop"}]}"""),
        )
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"error":{"message":"bad key"}}"""))
        server.enqueue(
            sseResponse("""{"choices":[{"delta":{"content":"第二版回答"},"finish_reason":"stop"}]}"""),
        )

        val vm = createVm()
        vm.onInputChange("打个招呼")
        vm.send()
        waitUntil(vm) { !it.streaming && it.messages.size == 2 && it.messages[1].dbId != null }

        val firstAssistant = vm.state.value.messages[1]
        val firstAssistantDbId = firstAssistant.dbId!!

        vm.regenerateMessage(firstAssistant.id)
        vm.regenerateMessage(firstAssistant.id)
        waitForRequestCount(2)
        waitUntil(vm) {
            !it.streaming && it.error != null && it.messages.size == 2 && it.messages[1].text == "第一版回答"
        }

        assertNull("401 后旧回复必须仍有效", container.database.messageDao().byId(firstAssistantDbId)?.deletedAt)
        assertEquals("401 后数据库只能有原用户和原助手行", 2,
            container.chat.chatTailInSegmentOrder(threadId, limit = 20).size)
        val reopenedAfterFailure = createVm()
        assertEquals("重开后仍恢复原回复", "第一版回答", reopenedAfterFailure.state.value.messages[1].text)

        // 失败必须释放同步占位；再次调用可正常发出一次请求。
        vm.regenerateMessage(firstAssistant.id)
        waitForRequestCount(3)
        waitUntil(vm) { !it.streaming && it.messages.size == 2 && it.messages[1].text == "第二版回答" && it.messages[1].dbId != null }

        val oldRow = container.database.messageDao().byId(firstAssistantDbId)
        assertNotNull("旧助手行必须被软删", oldRow?.deletedAt)

        val liveRows = container.chat.chatTailInSegmentOrder(threadId, limit = 20)
        assertEquals(2, liveRows.size)
        assertEquals("打个招呼", liveRows[0].message.content)
        assertEquals("第二版回答", liveRows[1].message.content)
        assertEquals("不能留下两个有效助手回复", 1, liveRows.count { it.message.role == "assistant" })
    }

    @Test
    fun `deleteMessage SQLite abort keeps message attachment file and UI`() = runBlocking {
        server.enqueue(
            sseResponse("""{"choices":[{"delta":{"content":"收到"},"finish_reason":"stop"}]}"""),
        )
        val vm = createVm()
        vm.onInputChange("保留这条")
        vm.send()
        waitUntil(vm) { !it.streaming && it.messages.size == 2 && it.messages[0].dbId != null }

        val user = vm.state.value.messages[0]
        val attachment = container.attachments.createFileDraft(
            store = container.attachmentStore,
            mime = "text/plain",
            stream = ByteArrayInputStream("remain".toByteArray()),
            fileName = "remain.txt",
            ownerThreadId = threadId,
        )
        container.attachments.markReady(attachment.id)
        container.attachments.setRemoteFile(
            attachment.id,
            remoteFileId = "remote-still-live",
            expiresAt = null,
            remoteCredentialBindingId = "binding-a",
        )
        container.attachments.bindToMessage(listOf(attachment.id), user.dbId!!)
        val localFile = container.attachmentStore.path(attachment.localPath)
        assertTrue(localFile.isFile)
        val requestsBeforeDelete = server.requestCount
        container.database.openHelper.writableDatabase.execSQL(
            """CREATE TRIGGER abort_message_soft_delete
                BEFORE UPDATE OF deletedAt ON message
                WHEN NEW.deletedAt IS NOT NULL
                BEGIN SELECT RAISE(ABORT, 'injected message delete failure'); END""".trimIndent(),
        )

        vm.deleteMessage(user.id)
        waitUntil(vm) { it.error == ChatActionCopy.DELETE_FAILED }

        assertTrue("失败后原 UI 消息保留", vm.state.value.messages.any { it.id == user.id })
        assertNull("SQLite abort 后消息仍有效", container.database.messageDao().byId(user.dbId!!)?.deletedAt)
        assertNull("附件软删必须回滚", container.database.attachmentDao().byId(attachment.id)?.deletedAt)
        assertTrue("失败事务不得留下远端清理队列", container.remoteFileCleanup.entries().isEmpty())
        assertTrue("本地附件文件必须保留", localFile.isFile)
        assertEquals("失败后不调用远端删除", requestsBeforeDelete, server.requestCount)

        container.database.openHelper.writableDatabase.execSQL("DROP TRIGGER abort_message_soft_delete")
        vm.deleteMessage(user.id)
        waitUntil(vm) { it.messages.none { message -> message.id == user.id } && !localFile.exists() }
        assertNotNull("事务失败释放门禁后，用户可以再次删除", container.database.messageDao().byId(user.dbId!!)?.deletedAt)
        assertEquals(
            listOf("remote-still-live" to "binding-a"),
            container.remoteFileCleanup.entries().map { it.remoteFileId to it.credentialBindingId },
        )
    }

    @Test
    fun `regenerate SQLite abort rolls back old reply and restores UI`() = runBlocking {
        server.enqueue(
            sseResponse("""{"choices":[{"delta":{"content":"原回复"},"finish_reason":"stop"}]}"""),
        )
        server.enqueue(
            sseResponse("""{"choices":[{"delta":{"content":"新回复"},"finish_reason":"stop"}]}"""),
        )
        val vm = createVm()
        vm.onInputChange("回复事务")
        vm.send()
        waitUntil(vm) { !it.streaming && it.messages.size == 2 && it.messages[1].dbId != null }

        val original = vm.state.value.messages[1]
        val originalDbId = original.dbId!!
        container.database.openHelper.writableDatabase.execSQL(
            """CREATE TRIGGER abort_regenerated_reply
                BEFORE UPDATE OF deletedAt ON message
                WHEN NEW.deletedAt IS NOT NULL
                BEGIN SELECT RAISE(ABORT, 'injected replacement failure'); END""".trimIndent(),
        )

        vm.regenerateMessage(original.id)
        waitForRequestCount(2)
        waitUntil(vm) { !it.streaming && it.error == ChatActionCopy.REGENERATE_FAILED }

        assertEquals("SQLite abort 后原回复数据库内容不变", "原回复",
            container.database.messageDao().byId(originalDbId)?.content)
        assertNull(container.database.messageDao().byId(originalDbId)?.deletedAt)
        assertEquals("失败后 UI 保留原回复", "原回复", vm.state.value.messages[1].text)
        assertEquals("不能出现新旧两条有效回复", 1,
            container.chat.chatTailInSegmentOrder(threadId, limit = 20).count { it.message.role == "assistant" })
    }

    @Test
    fun `stopped regeneration restores old reply and stale cancellation cannot unlock next action`() = runBlocking {
        server.enqueue(
            sseResponse("""{"choices":[{"delta":{"content":"原回复"},"finish_reason":"stop"}]}"""),
        )
        server.enqueue(
            sseResponse("""{"choices":[{"delta":{"content":"迟到回复"},"finish_reason":"stop"}]}""")
                .setHeadersDelay(2L, TimeUnit.SECONDS),
        )
        server.enqueue(
            sseResponse("""{"choices":[{"delta":{"content":"重试成功"},"finish_reason":"stop"}]}"""),
        )
        val vm = createVm()
        vm.onInputChange("停止时保留")
        vm.send()
        waitUntil(vm) { !it.streaming && it.messages.size == 2 && it.messages[1].dbId != null }
        val original = vm.state.value.messages[1]
        val originalDbId = original.dbId!!

        vm.regenerateMessage(original.id)
        waitForRequestCount(2)
        vm.stop()
        assertEquals("停止后 UI 立即恢复原回复", "原回复", vm.state.value.messages[1].text)
        assertEquals("停止后旧回复仍有效", null, container.database.messageDao().byId(originalDbId)?.deletedAt)

        // 新操作在旧取消回调可能到达前开始；旧 token 不能释放或改写新操作。
        vm.regenerateMessage(original.id)
        waitForRequestCount(3)
        waitUntil(vm) { !it.streaming && it.messages[1].text == "重试成功" && it.messages[1].dbId != null }
        assertEquals("最终只能有一个有效助手回复", 1,
            container.chat.chatTailInSegmentOrder(threadId, limit = 20).count { it.message.role == "assistant" })
    }

    @Test
    fun `deleteMessage propagates soft delete and deletes local attachment file`() = runBlocking {
        server.enqueue(
            sseResponse("""{"choices":[{"delta":{"content":"收到"},"finish_reason":"stop"}]}"""),
        )

        val vm = createVm()
        vm.onInputChange("附带文件")
        vm.send()
        waitUntil(vm) { !it.streaming && it.messages.size == 2 && it.messages[0].dbId != null }

        val userUi = vm.state.value.messages[0]
        val userDbId = userUi.dbId!!
        val att = container.attachments.createFileDraft(
            store = container.attachmentStore,
            mime = "text/plain",
            stream = ByteArrayInputStream("content".toByteArray()),
            fileName = "secret.txt",
            ownerThreadId = threadId,
        )
        container.attachments.markReady(att.id)
        container.attachments.setRemoteFile(
            att.id,
            remoteFileId = "remote-file-1",
            expiresAt = null,
            remoteCredentialBindingId = "binding-a",
        )
        container.attachments.bindToMessage(listOf(att.id), userDbId)
        val localFile = container.attachmentStore.path(att.localPath)
        assertTrue(localFile.isFile)
        val requestsBeforeDelete = server.requestCount

        vm.deleteMessage(userUi.id)
        waitUntil(vm) { it.messages.none { m -> m.id == userUi.id } && !localFile.exists() }

        assertNotNull(container.database.messageDao().byId(userDbId)?.deletedAt)
        assertFalse("本地附件文件必须同步删除", localFile.exists())
        assertTrue(container.attachments.listForMessage(userDbId).isEmpty())
        assertEquals(
            listOf("remote-file-1" to "binding-a"),
            container.remoteFileCleanup.entries().map { it.remoteFileId to it.credentialBindingId },
        )
        // 非 DeepSeek 端点不得向远端发 DELETE /files 请求（QA B2 双重守卫）
        Thread.sleep(100L)
        assertEquals(requestsBeforeDelete, server.requestCount)
    }

    @Test
    fun `non vision DeepSeek splits file gate rejects oversized files and retains drafts on no-key failure`() = runBlocking {
        // 切到 DeepSeek 非识图端点并清空密钥（模拟未填密钥但允许选文件草稿）
        container.clearChatCredential()
        container.settings.setChatEndpoint("https://api.deepseek.com", "deepseek-chat")
        container.settings.setVisionProbe("deepseek-chat", supported = false)

        val vm = createVm()
        assertFalse(vm.state.value.imageInputEnabled)
        assertTrue("非识图 DeepSeek 端点必须开放文件入口", vm.state.value.fileInputEnabled)

        vm.onAddImage()
        assertTrue("非识图 DeepSeek 点 + 仍应打开附件面板", vm.state.value.showImageSource)

        // 1) 超过 32 MiB 的纯文本文件应在本地直接拦截
        val hugeTxt = File(context.cacheDir, "too-big.txt").apply {
            RandomAccessFile(this, "rw").use { it.setLength(FilesClient.MAX_USER_DATA_BYTES + 1L) }
        }
        tempFiles += hugeTxt
        vm.onFilePicked(Uri.fromFile(hugeTxt), context.contentResolver)
        waitUntil(vm) { it.notice == ChatActionCopy.FILE_TOO_LARGE_TEXT && it.fileDrafts.isEmpty() }

        // 2) 合法小文件进草稿 → 无密钥发送失败时保留输入与文件草稿
        val smallTxt = File(context.cacheDir, "notes.txt").apply { writeText("重点笔记") }
        tempFiles += smallTxt
        vm.onFilePicked(Uri.fromFile(smallTxt), context.contentResolver)
        waitUntil(vm) { it.fileDrafts.size == 1 && it.fileDrafts[0].status == DraftStatus.READY }

        vm.onInputChange("请总结文件")
        vm.send()
        waitUntil(vm) { it.error == ChatActionCopy.FILE_NO_KEY }

        assertEquals("上传失败时不得丢失输入正文", "请总结文件", vm.state.value.input)
        assertEquals("上传失败时不得丢失文件草稿", 1, vm.state.value.fileDrafts.size)

        val draftId = vm.state.value.fileDrafts[0].id
        vm.removeFileDraft(draftId)
        assertTrue(vm.state.value.fileDrafts.isEmpty())
    }
}
