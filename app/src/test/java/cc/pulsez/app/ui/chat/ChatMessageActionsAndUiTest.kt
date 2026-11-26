package cc.pulsez.app.ui.chat

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import cc.pulsez.app.media.DraftStatus
import cc.pulsez.app.net.Citation
import cc.pulsez.app.net.FilesClient
import cc.pulsez.app.ui.copy.ChatActionCopy
import cc.pulsez.app.ui.copy.ImageCopy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 1:1 聊天消息操作（长按菜单、复制、重新生成、失败重试、单条删除）、
 * 引用列表去重编号与非识图 DeepSeek 文件面板的离线单测与 Compose UI 测试。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ChatMessageActionsAndUiTest {

    @get:Rule
    val composeRule = createComposeRule()

    // ------------------------------------------------------------ 纯 Kotlin 判定与编解码

    @Test
    fun `chatMessageActions enforces copy regenerate and delete rules`() {
        val userMsg = ChatUiMessage(id = "u1", role = "user", text = "你好")
        val oldAssistant = ChatUiMessage(id = "a1", role = "assistant", text = "旧回复")
        val lastAssistant = ChatUiMessage(id = "a2", role = "assistant", text = "新回复")
        val failedAssistant = ChatUiMessage(id = "a3", role = "assistant", text = "TA 没回上来。", failed = true)
        val imagePlaceholderUser = ChatUiMessage(
            id = "u-img",
            role = "user",
            text = ImageCopy.IMAGE_PLACEHOLDER,
            imagePaths = listOf("attachments/1.jpg"),
        )
        val filePlaceholderUser = ChatUiMessage(
            id = "u-file",
            role = "user",
            text = ChatActionCopy.FILE_PLACEHOLDER,
            fileNames = listOf("notes.pdf"),
        )
        val list = listOf(userMsg, oldAssistant, lastAssistant)

        assertTrue(ChatMessageActions.canCopy(userMsg))
        assertTrue(ChatMessageActions.canCopy(lastAssistant))
        assertFalse(ChatMessageActions.canCopy(failedAssistant))
        assertFalse(ChatMessageActions.canCopy(imagePlaceholderUser))
        assertFalse(ChatMessageActions.canCopy(filePlaceholderUser))
        assertNull(ChatMessageActions.copyableText(ChatUiMessage("empty", "assistant", "   ")))

        assertFalse("非最后一条不可重新生成", ChatMessageActions.canRegenerate(list, oldAssistant, isStreaming = false, isAwakening = false))
        assertFalse("用户消息不可重新生成", ChatMessageActions.canRegenerate(list, userMsg, isStreaming = false, isAwakening = false))
        assertTrue("非流式最后一条助手消息可重新生成", ChatMessageActions.canRegenerate(list, lastAssistant, isStreaming = false, isAwakening = false))
        assertFalse("流式期间不可重新生成", ChatMessageActions.canRegenerate(list, lastAssistant, isStreaming = true, isAwakening = false))
        assertFalse("苏醒期间不可重新生成", ChatMessageActions.canRegenerate(list, lastAssistant, isStreaming = false, isAwakening = true))

        assertTrue(ChatMessageActions.canDelete(isStreaming = false, isAwakening = false))
        assertFalse(ChatMessageActions.canDelete(isStreaming = true, isAwakening = false))
        assertFalse(ChatMessageActions.canDelete(isStreaming = false, isAwakening = true))
    }

    @Test
    fun `citationCodec and reasoningRestore round-trip and tolerate corrupt json`() {
        val citations = listOf(
            Citation(url = "https://a.example", title = "来源甲", startIndex = 0, endIndex = 12),
            Citation(url = "https://b.example", title = null, startIndex = 12, endIndex = 24),
        )
        val encoded = CitationCodec.encode(citations)
        assertEquals(citations, CitationCodec.decode(encoded))
        assertNull(CitationCodec.encode(emptyList()))
        assertTrue(CitationCodec.decode("not-json").isEmpty())

        val combined = ReasoningRestore.combine(
            reasoningContent = "最终总结",
            serverToolCallsJson = """[{"index":0,"reasoning_content":"先查资料"},{"index":1,"reasoning":"再读网页"}]""",
        )
        assertEquals("先查资料\n\n再读网页\n\n最终总结", combined)
        assertEquals("单独思考", ReasoningRestore.combine("单独思考", "invalid-json"))
        assertNull(ReasoningRestore.combine("   ", null))
    }

    @Test
    fun `shouldEnableReadUrl triggers on DeepSeek with webSearch or http url`() {
        assertFalse(ChatViewModel.shouldEnableReadUrl(deepSeek = false, webSearchEnabled = true, userText = "https://a.example"))
        assertFalse(ChatViewModel.shouldEnableReadUrl(deepSeek = true, webSearchEnabled = false, userText = "普通聊天"))
        assertTrue(ChatViewModel.shouldEnableReadUrl(deepSeek = true, webSearchEnabled = true, userText = "普通聊天"))
        assertTrue(ChatViewModel.shouldEnableReadUrl(deepSeek = true, webSearchEnabled = false, userText = "看看 https://a.example"))
        assertTrue(ChatViewModel.shouldEnableReadUrl(deepSeek = true, webSearchEnabled = false, userText = "看看 http://a.example"))
    }

    // ------------------------------------------------------------ Compose UI 交互

    @Test
    fun `long press last assistant opens action sheet with 48dp targets and delete confirmation`() {
        val messages = listOf(
            ChatUiMessage(id = "u1", dbId = "u1", role = "user", text = "你好"),
            ChatUiMessage(id = "a1", dbId = "a1", role = "assistant", text = "早安，世界。"),
        )
        val deletedIds = mutableListOf<String>()
        val regeneratedIds = mutableListOf<String>()

        composeRule.setContent {
            MaterialTheme {
                ChatScreen(
                    characterName = "阿淮",
                    messages = messages,
                    input = "",
                    isStreaming = false,
                    showAiBadge = false,
                    onInputChange = {},
                    onSend = {},
                    onStop = {},
                    onBack = {},
                    onOpenMemory = {},
                    onDismissAiBadge = {},
                    onRegenerateMessage = { regeneratedIds += it },
                    onDeleteMessage = { deletedIds += it },
                )
            }
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithText("早安，世界。").performTouchInput { longClick() }
        composeRule.waitForIdle()

        composeRule.onNodeWithText(ChatActionCopy.COPY)
            .assertIsDisplayed()
            .assertHeightIsAtLeast(48.dp)
            .assertHasClickAction()
        val regenNode = composeRule.onNodeWithText(ChatActionCopy.REGENERATE)
            .assertIsDisplayed()
            .assertHeightIsAtLeast(48.dp)
            .assertHasClickAction()
        composeRule.onNodeWithText(ChatActionCopy.DELETE)
            .assertIsDisplayed()
            .assertHeightIsAtLeast(48.dp)
            .assertHasClickAction()

        // M3 ModalBottomSheet 在 Robolectric 下使用 edgeToEdge Dialog 窗口，performClick 指针映射会偏离
        // Dialog 宿主坐标；此处直接验证节点具备 OnClick 语义并触发回调（与真实点击共用同一 clickable 动作）。
        regenNode.performSemanticsAction(SemanticsActions.OnClick)
        composeRule.waitForIdle()
        assertEquals(listOf("a1"), regeneratedIds)

        // 再次长按点删除 → 弹出二次确认框 → 确认后触发删除回调
        composeRule.onNodeWithText("早安，世界。").performTouchInput { longClick() }
        composeRule.waitForIdle()
        composeRule.onNodeWithText(ChatActionCopy.DELETE)
            .performSemanticsAction(SemanticsActions.OnClick)
        composeRule.waitForIdle()

        composeRule.onNodeWithText(ChatActionCopy.DELETE_TITLE).assertIsDisplayed()
        composeRule.onNodeWithText(ChatActionCopy.DELETE_CONFIRM).performClick()
        composeRule.waitForIdle()
        assertEquals(listOf("a1"), deletedIds)
    }

    @Test
    fun `long press earlier assistant hides regenerate`() {
        val messages = listOf(
            ChatUiMessage(id = "u1", role = "user", text = "第一句"),
            ChatUiMessage(id = "a1", role = "assistant", text = "旧回答"),
            ChatUiMessage(id = "u2", role = "user", text = "第二句"),
            ChatUiMessage(id = "a2", role = "assistant", text = "新回答"),
        )
        composeRule.setContent {
            MaterialTheme {
                ChatScreen(
                    characterName = "阿淮",
                    messages = messages,
                    input = "",
                    isStreaming = false,
                    showAiBadge = false,
                    onInputChange = {},
                    onSend = {},
                    onStop = {},
                    onBack = {},
                    onOpenMemory = {},
                    onDismissAiBadge = {},
                )
            }
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithText("旧回答").performTouchInput { longClick() }
        composeRule.waitForIdle()

        composeRule.onNodeWithText(ChatActionCopy.COPY).assertIsDisplayed()
        composeRule.onNodeWithText(ChatActionCopy.DELETE).assertIsDisplayed()
        composeRule.onNodeWithText(ChatActionCopy.REGENERATE).assertDoesNotExist()
    }

    @Test
    fun `failed placeholder renders 48dp retry button and invokes callback`() {
        val retried = mutableListOf<String>()
        val messages = listOf(
            ChatUiMessage(id = "u1", dbId = "u1", role = "user", text = "在吗"),
            ChatUiMessage(
                id = "local-ai-1",
                role = "assistant",
                text = ChatViewModel.REPLY_FAILED_PLACEHOLDER,
                failed = true,
                targetUserDbId = "u1",
            ),
        )
        composeRule.setContent {
            MaterialTheme {
                ChatScreen(
                    characterName = "阿淮",
                    messages = messages,
                    input = "",
                    isStreaming = false,
                    showAiBadge = false,
                    onInputChange = {},
                    onSend = {},
                    onStop = {},
                    onBack = {},
                    onOpenMemory = {},
                    onDismissAiBadge = {},
                    onRetryMessage = { retried += it },
                )
            }
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithText(ChatActionCopy.RETRY)
            .assertIsDisplayed()
            .assertHeightIsAtLeast(48.dp)
            .performClick()
        assertEquals(listOf("local-ai-1"), retried)
    }

    @Test
    fun `citationsBlock deduplicates urls and keeps 48dp rows`() {
        val messages = listOf(
            ChatUiMessage(
                id = "a1",
                role = "assistant",
                text = "杭州今天晴。",
                citations = listOf(
                    Citation(url = "https://weather.example/hz", title = "杭州气象", startIndex = 0, endIndex = 5),
                    Citation(url = "https://weather.example/hz", title = "杭州气象", startIndex = 2, endIndex = 6),
                    Citation(url = "https://park.example/westlake", title = "西湖公告", startIndex = 5, endIndex = 6),
                ),
            ),
        )
        composeRule.setContent {
            MaterialTheme {
                ChatScreen(
                    characterName = "阿淮",
                    messages = messages,
                    input = "",
                    isStreaming = false,
                    showAiBadge = false,
                    onInputChange = {},
                    onSend = {},
                    onStop = {},
                    onBack = {},
                    onOpenMemory = {},
                    onDismissAiBadge = {},
                )
            }
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithText("参考了 2 个来源").assertIsDisplayed().performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithText("[1] 杭州气象 - https://weather.example/hz")
            .assertIsDisplayed()
            .assertHeightIsAtLeast(48.dp)
        composeRule.onNodeWithText("[2] 西湖公告 - https://park.example/westlake")
            .assertIsDisplayed()
            .assertHeightIsAtLeast(48.dp)
    }

    @Test
    fun `non vision DeepSeek still opens attachment sheet with enabled file action and dimmed image items`() {
        val showSheet = mutableStateOf(false)
        composeRule.setContent {
            MaterialTheme {
                ChatScreen(
                    characterName = "阿淮",
                    messages = emptyList(),
                    input = "",
                    isStreaming = false,
                    showAiBadge = false,
                    onInputChange = {},
                    onSend = {},
                    onStop = {},
                    onBack = {},
                    onOpenMemory = {},
                    onDismissAiBadge = {},
                    deepSeekProvider = true,
                    imageInputVisible = true,
                    imageInputEnabled = false,
                    fileInputEnabled = true,
                    showImageSource = showSheet.value,
                    onAddImage = { showSheet.value = true },
                    onDismissImageSource = { showSheet.value = false },
                )
            }
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithContentDescription(ChatActionCopy.ADD_FILE)
            .assertIsDisplayed()
            .performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithText(ChatActionCopy.VISION_DISABLED_IN_SHEET).assertIsDisplayed()
        composeRule.onNodeWithText(ImageCopy.SOURCE_CAMERA).assertIsNotEnabled()
        composeRule.onNodeWithText(ImageCopy.SOURCE_GALLERY).assertIsNotEnabled()
        composeRule.onNodeWithText(ChatActionCopy.SOURCE_FILE)
            .assertIsDisplayed()
            .assertIsEnabled()
            .assertHeightIsAtLeast(48.dp)
    }

    @Test
    fun `uploadingFiles displays progress copy and disables file draft removal`() {
        val draft = DraftFile(
            id = "f1",
            localPath = "attachments/f1.pdf",
            fileName = "合同.pdf",
            mime = "application/pdf",
            sizeBytes = 1024L,
            purpose = FilesClient.PURPOSE_TEXT_RECOGNITION,
            status = DraftStatus.READY,
        )
        composeRule.setContent {
            MaterialTheme {
                ChatScreen(
                    characterName = "阿淮",
                    messages = emptyList(),
                    input = "",
                    isStreaming = false,
                    showAiBadge = false,
                    onInputChange = {},
                    onSend = {},
                    onStop = {},
                    onBack = {},
                    onOpenMemory = {},
                    onDismissAiBadge = {},
                    deepSeekProvider = true,
                    fileInputEnabled = true,
                    fileDrafts = listOf(draft),
                    uploadingFiles = true,
                )
            }
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithText(ChatActionCopy.UPLOADING_FILES).assertIsDisplayed()
        composeRule.onNodeWithContentDescription(ChatActionCopy.FILE_DRAFT_REMOVE).assertIsNotEnabled()
    }
}
