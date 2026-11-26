package cc.pulsez.app.ui.copy

import cc.pulsez.app.net.ApiError
import cc.pulsez.app.net.ApiException
import cc.pulsez.app.net.LlmEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v0.6.10 1:1 聊天操作、联网进度与文件上传文案回归（纯 JVM）。
 *
 * 锁定禁用词红线：绝不出现「模型」「deepseek-flash」「token」「上下文」「窗口」「向量」「缓存」「她」「他」等词。
 */
class ChatActionCopyTest {

    companion object {
        private val TECH_BANNED: List<String> = listOf(
            "模型", "deepseek-flash",
            "token", "上下文", "窗口", "向量", "embedding", "Embedding",
            "记忆检索", "关键词", "缓存", "TTS", "语音合成", "合成引擎",
        )

        private val PRONOUN_BANNED: List<String> = listOf("她", "他")

        private val PROMISE_BANNED: List<String> = listOf(
            "免费", "不额外收费", "永久", "绝对", "本版未提供", "我们", "全新升级",
        )

        private fun allCopy(): List<Pair<String, String>> = listOf(
            "复制" to ChatActionCopy.COPY,
            "重新生成" to ChatActionCopy.REGENERATE,
            "重试" to ChatActionCopy.RETRY,
            "删除" to ChatActionCopy.DELETE,
            "剪贴板标签" to ChatActionCopy.CLIP_LABEL,
            "已复制" to ChatActionCopy.COPIED,
            "删除标题" to ChatActionCopy.DELETE_TITLE,
            "删除正文" to ChatActionCopy.DELETE_BODY,
            "删除确认" to ChatActionCopy.DELETE_CONFIRM,
            "删除取消" to ChatActionCopy.DELETE_CANCEL,
            "缺用户消息" to ChatActionCopy.RETRY_MISSING_USER,
            "正在搜索" to ChatActionCopy.SEARCHING_WEB,
            "正在读网页" to ChatActionCopy.READING_PAGE,
            "正在整理" to ChatActionCopy.ORGANIZING_REPLY,
            "链接打开失败" to ChatActionCopy.OPEN_LINK_FAILED,
            "链接不支持" to ChatActionCopy.OPEN_LINK_UNSUPPORTED,
            "附件读屏" to ChatActionCopy.ADD_ATTACHMENT,
            "添加文件读屏" to ChatActionCopy.ADD_FILE,
            "发文件" to ChatActionCopy.SOURCE_FILE,
            "面板内不能看图提示" to ChatActionCopy.VISION_DISABLED_IN_SHEET,
            "文件占位" to ChatActionCopy.FILE_PLACEHOLDER,
            "文件准备中" to ChatActionCopy.FILE_DRAFT_PREPARING,
            "文件失败" to ChatActionCopy.FILE_DRAFT_FAILED,
            "移除文件读屏" to ChatActionCopy.FILE_DRAFT_REMOVE,
            "文件上限" to ChatActionCopy.FILE_LIMIT_REACHED,
            "文件准备中禁发" to ChatActionCopy.FILE_SEND_BLOCKED_PREPARING,
            "非 DeepSeek 文件提示" to ChatActionCopy.FILE_OTHER_PROVIDER,
            "未配置密钥文件提示" to ChatActionCopy.FILE_NO_KEY,
            "连接快照不明" to ChatActionCopy.FILE_SESSION_UNAVAILABLE,
            "文件归属不明" to ChatActionCopy.FILE_BINDING_UNAVAILABLE,
            "连接凭据变化" to ChatActionCopy.FILE_BINDING_CHANGED,
            "文件归属保存失败" to ChatActionCopy.FILE_BIND_SAVE_FAILED,
            "草稿失效" to ChatActionCopy.FILE_DRAFT_UNAVAILABLE,
            "草稿移除失败" to ChatActionCopy.FILE_REMOVE_FAILED,
            "上传结果未确认" to ChatActionCopy.FILE_UPLOAD_CANCELLED_UNCONFIRMED,
            "上传期间移除门禁" to ChatActionCopy.FILE_REMOVE_BLOCKED,
            "正在上传文件" to ChatActionCopy.UPLOADING_FILES,
            "文档超 100MB" to ChatActionCopy.FILE_TOO_LARGE_DOCUMENT,
            "文件超 32MB" to ChatActionCopy.FILE_TOO_LARGE_TEXT,
            "文件读取失败" to ChatActionCopy.FILE_READ_FAILED,
            "文件上传失败" to ChatActionCopy.FILE_UPLOAD_FAILED,
            "文件上传超限" to ChatActionCopy.FILE_UPLOAD_TOO_LARGE,
            "文件格式不支持" to ChatActionCopy.FILE_UPLOAD_UNSUPPORTED,
            "搜索查询进度" to ChatActionCopy.toolProgressText(
                LlmEvent.ToolProgress(kind = "search", status = "in_progress", queries = listOf("天气")),
            ),
            "搜索结果进度" to ChatActionCopy.toolProgressText(
                LlmEvent.ToolProgress(kind = "search", status = "completed", sourceCount = 5),
            ),
            "读网页进度" to ChatActionCopy.toolProgressText(
                LlmEvent.ToolProgress(kind = "open_page", status = "in_progress", url = "https://example.com"),
            ),
            "读网页完成进度" to ChatActionCopy.toolProgressText(
                LlmEvent.ToolProgress(kind = "open_page", status = "completed", url = "https://example.com"),
            ),
            "上传认证失败" to ChatActionCopy.fileUploadFailure(ApiException(ApiError.Auth(401))),
            "上传额度不足" to ChatActionCopy.fileUploadFailure(ApiException(ApiError.Quota(429))),
            "上传断网" to ChatActionCopy.fileUploadFailure(ApiException(ApiError.Network(raw = "timeout"))),
            "上传 413" to ChatActionCopy.fileUploadFailure(ApiException(ApiError.BadResponse(413, "too big"))),
            "上传 400" to ChatActionCopy.fileUploadFailure(ApiException(ApiError.BadResponse(400, "bad type"))),
        )
    }

    @Test
    fun allCopyAvoidsBannedTechnicalPronounAndPromiseWords() {
        val all = allCopy()
        assertTrue(all.isNotEmpty())
        all.forEach { (label, text) ->
            assertTrue("$label 不能为空", text.isNotBlank())
            TECH_BANNED.forEach { banned ->
                assertFalse("$label 包含技术禁用词「$banned」：$text", text.contains(banned))
            }
            PRONOUN_BANNED.forEach { pronoun ->
                assertFalse("$label 出现性别代词「$pronoun」：$text", text.contains(pronoun))
            }
            PROMISE_BANNED.forEach { promise ->
                assertFalse("$label 包含禁用词「$promise」：$text", text.contains(promise))
            }
        }
    }

    @Test
    fun fileTooLargeSelectsLimitByFileCategory() {
        // FilesClient.maxBytesForFile determines the limit from the file extension and MIME type.
        assertEquals(
            ChatActionCopy.FILE_TOO_LARGE_DOCUMENT,
            ChatActionCopy.fileTooLarge("report.pdf", "application/pdf"),
        )
        assertEquals(
            ChatActionCopy.FILE_TOO_LARGE_IMAGE,
            ChatActionCopy.fileTooLarge("photo.png", "image/png"),
        )
        assertEquals(
            ChatActionCopy.FILE_TOO_LARGE_TEXT,
            ChatActionCopy.fileTooLarge("notes.txt", "text/plain"),
        )
    }

    @Test
    fun toolProgressTextFormatsSearchAndPageStates() {
        assertEquals(
            "正在上网搜「杭州天气」……",
            ChatActionCopy.toolProgressText(
                LlmEvent.ToolProgress(kind = "search", status = "in_progress", queries = listOf("杭州天气")),
            ),
        )
        assertEquals(
            "搜到 3 个结果",
            ChatActionCopy.toolProgressText(
                LlmEvent.ToolProgress(kind = "search", status = "completed", sourceCount = 3),
            ),
        )
        assertEquals(
            ChatActionCopy.READING_PAGE,
            ChatActionCopy.toolProgressText(
                LlmEvent.ToolProgress(kind = "open_page", status = "in_progress", url = "https://example.com"),
            ),
        )
        assertEquals(
            ChatActionCopy.ORGANIZING_REPLY,
            ChatActionCopy.toolProgressText(
                LlmEvent.ToolProgress(kind = "open_page", status = "completed", url = "https://example.com"),
            ),
        )
    }
}
