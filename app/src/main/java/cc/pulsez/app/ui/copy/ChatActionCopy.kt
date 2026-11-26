package cc.pulsez.app.ui.copy

import cc.pulsez.app.net.ApiError
import cc.pulsez.app.net.ApiException
import cc.pulsez.app.net.FilesClient
import cc.pulsez.app.net.LlmEvent

/**
 * 1:1 聊天交互操作、联网工具进度与文件上传的用户可见文案（v0.6.10）。
 *
 * 每条文案都要让用户清楚知道当前操作会做什么：
 * - 说人话，绝不出现「模型」「deepseek-flash」「token」「上下文」「窗口」「向量」「缓存」等词；
 * - 角色代词只用 **TA**，不用「她」「他」；
 * - 失败说明给事实与出路，不指责用户。
 */
internal object ChatActionCopy {

    // ------------------------------------------------------------ 消息长按菜单 / 复制 / 重试 / 删除

    const val COPY = "复制"
    const val REGENERATE = "重新生成"
    const val RETRY = "重试"
    const val DELETE = "删除"

    const val CLIP_LABEL = "聊天消息"
    const val COPIED = "已复制。"

    const val DELETE_TITLE = "删掉这条消息？"
    const val DELETE_BODY = "删掉之后，TA 之后聊天就不会再参考它；里面带的图片和文件也会一起清掉。"
    const val DELETE_CONFIRM = "删掉"
    const val DELETE_CANCEL = "先不删"

    const val RETRY_MISSING_USER = "找不到刚才那句话了，重新发一次？"
    const val RETRY_FAILED = "这次没能重新发出去，稍后再试？"
    const val DELETE_FAILED = "这条消息没能删掉，原内容还在。稍后再试？"
    const val REGENERATE_FAILED = "重新生成没成功，原来的回复还在。稍后再试？"
    const val REGENERATE_EMPTY = "这次没有收到新回复，原来的回复还在。可以稍后再试。"
    const val REGENERATE_CANCELLED = "已停止重新生成，原来的回复还在。"

    // ------------------------------------------------------------ 联网搜索进度 / 引用链接

    const val SEARCHING_WEB = "正在上网搜……"
    const val READING_PAGE = "正在看网页……"
    const val ORGANIZING_REPLY = "正在整理回答……"

    const val OPEN_LINK_FAILED = "打不开这个网页链接。"
    const val OPEN_LINK_UNSUPPORTED = "这个链接打不开。"

    fun toolProgressText(event: LlmEvent.ToolProgress): String {
        val kind = event.kind.lowercase()
        val status = event.status.lowercase()
        val isOpenPage = kind.contains("page") || kind.contains("url") || !event.url.isNullOrBlank()
        if (isOpenPage) {
            return if (status == "completed") ORGANIZING_REPLY else READING_PAGE
        }
        if (status == "completed" && event.sourceCount > 0) {
            return "搜到 ${event.sourceCount} 个结果"
        }
        val firstQuery = event.queries.firstOrNull()?.trim()?.takeIf { it.isNotEmpty() }
        return if (firstQuery != null) {
            "正在上网搜「${firstQuery.take(24)}」……"
        } else {
            SEARCHING_WEB
        }
    }

    // ------------------------------------------------------------ 文件附件与上传

    const val ADD_ATTACHMENT = "添加图片或文件"
    const val ADD_FILE = "添加文件"
    const val SOURCE_FILE = "发文件"
    const val VISION_DISABLED_IN_SHEET =
        "现在这个 AI 看不了图，可以去「设置 → 聊天」换一个能看图的 AI；发文件可以直接用。"

    const val FILE_PLACEHOLDER = "[文件]"
    const val FILE_DRAFT_PREPARING = "正在准备……"
    const val FILE_DRAFT_FAILED = "没读出来"
    const val FILE_DRAFT_REMOVE = "删掉这个文件"

    const val FILE_LIMIT_REACHED = "一轮最多发 3 个文件。"
    const val FILE_SEND_BLOCKED_PREPARING = "文件还在准备，等一下再发。"
    const val FILE_OTHER_PROVIDER = "发文件只对 DeepSeek 的地址有效。"
    const val FILE_NO_KEY = "还没连上 AI。去「设置 → 聊天」填一下？"
    const val FILE_SESSION_UNAVAILABLE = "暂时无法确认当前连接，没开始上传；本地文件还在。请重试。"
    const val FILE_BINDING_UNAVAILABLE = "暂时无法确认文件归属，没开始上传；本地文件还在。检查连接后再试？"
    const val FILE_BINDING_CHANGED = "上传期间连接凭据变了，文件还在本地，请重新发送。"
    const val FILE_DRAFT_UNAVAILABLE = "这份文件已不在本地列表里，重新选一下再发？"
    const val FILE_BIND_SAVE_FAILED = "文件已上传，但本地没能确认归属；文件仍在本地，请稍后重试。"
    const val FILE_REMOVE_FAILED = "文件没能移除，原文件还在。稍后再试？"
    const val FILE_UPLOAD_CANCELLED_UNCONFIRMED = "已停止上传；服务商那边的结果还没确认，文件仍在本地。"
    const val FILE_REMOVE_BLOCKED = "文件正在准备发送，等一下再移除。"
    const val UPLOADING_FILES = "正在准备并上传文件给 TA 看……"

    const val FILE_TOO_LARGE_DOCUMENT = "文档超过 100MB 了，换一个小一点的？"
    const val FILE_TOO_LARGE_IMAGE = "图片超过 64MB 了，换一张小一点的？"
    const val FILE_TOO_LARGE_TEXT = "这个文件超过 32MB 了，换一个小一点的？"
    const val FILE_READ_FAILED = "这个文件没读出来。换一个试试？"
    const val FILE_UPLOAD_FAILED = "文件没传上去。检查一下网络，再试一次？"
    const val FILE_UPLOAD_TOO_LARGE = "文件太大了，服务商收不下。换一个小一点的？"
    const val FILE_UPLOAD_UNSUPPORTED = "服务商不收这种格式的文件。换成 PDF、Word 或纯文本试试？"

    fun fileTooLarge(fileName: String, mime: String?): String = when (
        FilesClient.maxBytesForFile(fileName, mime)
    ) {
        FilesClient.MAX_DOCUMENT_BYTES -> FILE_TOO_LARGE_DOCUMENT
        FilesClient.MAX_IMAGE_BYTES -> FILE_TOO_LARGE_IMAGE
        else -> FILE_TOO_LARGE_TEXT
    }

    fun fileUploadFailure(throwable: Throwable): String {
        val apiError = (throwable as? ApiException)?.error ?: return FILE_UPLOAD_FAILED
        return when (apiError.kind) {
            ApiError.Kind.NO_KEY -> FILE_NO_KEY
            ApiError.Kind.AUTH -> "密钥好像不对。去设置里改一下？"
            ApiError.Kind.QUOTA -> "这个密钥的额度用完了，或者被限流了。稍后再试？"
            ApiError.Kind.NETWORK -> "网络中断，服务商那边的结果还没确认；本地文件还在，可以重试。"
            ApiError.Kind.BAD_RESPONSE -> when (apiError.httpCode) {
                413 -> FILE_UPLOAD_TOO_LARGE
                400 -> FILE_UPLOAD_UNSUPPORTED
                else -> "服务商没确认文件结果；本地文件还在，可以重试。"
            }
            else -> FILE_UPLOAD_FAILED
        }
    }
}
