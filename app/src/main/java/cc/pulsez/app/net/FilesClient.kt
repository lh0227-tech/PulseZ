package cc.pulsez.app.net

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.Response
import java.io.File
import java.io.IOException
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * `POST {base}/files` 上传成功返回的远端文件元数据。
 *
 * DeepSeek Files API documentation: https://api-docs.deepseek.com/guides/files_api
 */
data class UploadedFile(
    val id: String,
    val bytes: Long,
    val filename: String,
    val purpose: String,
    val expiresAt: Long?,
)

/** The result of a remote file deletion; response bodies and credentials are never exposed. */
sealed class DeleteFileResult {
    data class Deleted(val fileId: String) : DeleteFileResult()

    data class Failed(
        val reason: FailureReason,
        val httpCode: Int? = null,
    ) : DeleteFileResult()

    enum class FailureReason {
        INVALID_INPUT,
        HTTP_ERROR,
        UNCONFIRMED_SUCCESS,
        NETWORK_ERROR,
    }
}

/**
 * DeepSeek Files API 客户端（`POST /files`、`DELETE /files/{file_id}`）。
 *
 * ## 协议约束（已核实官方文档 https://api-docs.deepseek.com/guides/files_api）
 * - 官方 OpenAI-compatible Files API 示例使用 `https://api.deepseek.com/files`；仅精确官方 origin 的
 *   根 base 或无 query 的标准 `/v1` base 使用根 `/files`，其他路径和 query 均保留；第三方仍沿用原 fallback；
 * - `multipart/form-data` 中 **`purpose` 必须排在 `file` 字段之前**，否则服务端返回 400；
 * - 官方文件上传时限按类型区分：文档 120 秒、其他文件 10 分钟。独立 HTTP client 的读、写和整次调用
 *   均最多允许 10 分钟，以免在官方允许的非文档上传窗口内提前超时；关闭自动重连避免重复上传；
 * - 删除为 best-effort，不因 HTTP/网络失败中断本地删除流程，但返回可区分结果；
 *   只有响应 `id` 与请求一致且 `deleted == true` 才确认成功，HTTP 404 不视为成功。
 *
 * DeepSeek Files API documentation: https://api-docs.deepseek.com/guides/files_api/（OpenAI-compatible 根路径示例与
 * DELETE 响应 `{ "id": ..., "deleted": true }`）。
 */
class FilesClient(
    http: OkHttpClient,
    baseUrl: String,
    apiKey: String,
) {

    private val apiKey: String = apiKey.trim()
    private val configuredBaseUrl: String = BaseUrl.normalize(baseUrl)

    @Volatile
    private var resolvedBaseUrl: String = BaseUrl.initial(baseUrl)

    val baseUrl: String get() = resolvedBaseUrl

    private val client: OkHttpClient = http.newBuilder()
        .writeTimeout(WRITE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .callTimeout(CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .retryOnConnectionFailure(false)
        .build()

    /**
     * 上传本地文件到 DeepSeek Files API 并等待服务端解析完成。
     *
     * @throws ApiException HTTP 错误（400/408/413/429/500/503/504 等）、网络异常或响应解析失败。
     * @throws CancellationException 调用方协程取消时立即中断底层 OkHttp Call。
     */
    suspend fun uploadFile(
        file: File,
        fileName: String,
        purpose: String = purposeForFile(fileName, null),
    ): UploadedFile {
        if (apiKey.isEmpty()) throw ApiException(ApiError.NoKey())
        if (resolvedBaseUrl.isEmpty()) throw ApiException(ApiError.BadResponse(raw = "base url is blank"))

        val safeFileName = sanitizeFileName(fileName)
        val effectivePurpose = purpose.trim().ifEmpty { purposeForFile(safeFileName, null) }

        val exists = withContext(Dispatchers.IO) { file.isFile }
        if (!exists) throw ApiException(ApiError.BadResponse(raw = "file missing"))

        var urlBase = resolvedBaseUrl
        val officialDeepSeek = isOfficialDeepSeekOrigin(urlBase)
        var v1Tried = false
        while (true) {
            val request = buildUploadRequest(
                urlBase = filesBase(urlBase),
                file = file,
                safeFileName = safeFileName,
                purpose = effectivePurpose,
            )
            val response = executeOnce(request)
            if (!response.isSuccessful) {
                // 只保留旧第三方兼容逻辑的一次 404 路径探测；不重试网络失败或其他上传响应，
                // 避免在服务端可能已接收 POST 时重复上传。官方 origin 不做这次 fallback。
                if (response.code == 404 && !officialDeepSeek && !BaseUrl.isV1(urlBase) && !v1Tried) {
                    v1Tried = true
                    urlBase = BaseUrl.withV1(urlBase)
                    continue
                }
                throw ApiException(ApiErrorMapper.fromHttp(response.code, response.body))
            }
            rememberResolvedBase(urlBase)
            return parseUploadedFile(
                body = response.body,
                httpCode = response.code,
                fallbackFilename = safeFileName,
                fallbackPurpose = effectivePurpose,
            )
        }
    }

    /**
     * Best-effort 删除远端文件（`DELETE {baseUrl}/files/{fileId}`）。
     *
     * HTTP / 网络失败通过 [DeleteFileResult.Failed] 返回，不阻断上层会话或消息删除；协程取消继续抛出。
     * 只有官方示例中的匹配 `id` 与 `deleted: true` 响应会返回 [DeleteFileResult.Deleted]。
     */
    suspend fun deleteFile(fileId: String): DeleteFileResult {
        val cleanId = fileId.trim()
        if (apiKey.isEmpty() || resolvedBaseUrl.isEmpty() || cleanId.isEmpty() || cleanId.contains('/')) {
            return DeleteFileResult.Failed(DeleteFileResult.FailureReason.INVALID_INPUT)
        }
        try {
            var urlBase = resolvedBaseUrl
            val officialDeepSeek = isOfficialDeepSeekOrigin(urlBase)
            var v1Tried = false
            while (true) {
                val request = buildDeleteRequest(filesBase(urlBase), cleanId)
                    ?: return DeleteFileResult.Failed(DeleteFileResult.FailureReason.INVALID_INPUT)
                val response = executeOnce(request)
                // 第三方保留旧的一次 404 路径探测；官方 /files 路径不把 404 当成重试或成功信号。
                if (response.code == 404 && !officialDeepSeek && !BaseUrl.isV1(urlBase) && !v1Tried) {
                    v1Tried = true
                    urlBase = BaseUrl.withV1(urlBase)
                    continue
                }
                if (!response.isSuccessful) {
                    return DeleteFileResult.Failed(
                        reason = DeleteFileResult.FailureReason.HTTP_ERROR,
                        httpCode = response.code,
                    )
                }
                if (!confirmsDeletion(response.body, cleanId)) {
                    return DeleteFileResult.Failed(
                        reason = DeleteFileResult.FailureReason.UNCONFIRMED_SUCCESS,
                        httpCode = response.code,
                    )
                }
                if (!officialDeepSeek) rememberResolvedBase(urlBase)
                return DeleteFileResult.Deleted(cleanId)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Best-effort：只分类失败，不返回/记录可能含敏感内容的异常文本。
            return DeleteFileResult.Failed(DeleteFileResult.FailureReason.NETWORK_ERROR)
        }
    }

    private fun buildUploadRequest(
        urlBase: String,
        file: File,
        safeFileName: String,
        purpose: String,
    ): Request {
        val endpoint = filesEndpoint(urlBase)
        // 官方要求：purpose 必须在 file 之前，否则服务端返回 400。
        val multipart = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart(FORM_PURPOSE, purpose)
            .addFormDataPart(
                FORM_FILE,
                safeFileName,
                file.asRequestBody(mediaTypeForFile(safeFileName).toMediaType()),
            )
            .build()
        return try {
            Request.Builder()
                .url(endpoint)
                .header("Authorization", "Bearer $apiKey")
                .header("Accept", "application/json")
                .post(multipart)
                .build()
        } catch (_: IllegalArgumentException) {
            throw ApiException(ApiError.BadResponse(raw = "invalid base url"))
        }
    }

    private fun buildDeleteRequest(urlBase: String, fileId: String): Request? {
        val baseFilesUrl = filesEndpoint(urlBase).toHttpUrlOrNull() ?: return null
        val targetUrl = baseFilesUrl.newBuilder()
            .addPathSegment(fileId)
            .build()
            .toString()
        return Request.Builder()
            .url(targetUrl)
            .header("Authorization", "Bearer $apiKey")
            .header("Accept", "application/json")
            .delete()
            .build()
    }

    private suspend fun executeOnce(request: Request): RawHttpResponse =
        suspendCancellableCoroutine { cont ->
            val call = client.newCall(request)
            cont.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (!cont.isActive) return
                    cont.resumeWithException(ApiException(ApiErrorMapper.fromThrowable(e)))
                }

                override fun onResponse(call: Call, response: Response) {
                    if (!cont.isActive) {
                        response.close()
                        return
                    }
                    val parsed = runCatching {
                        response.use { resp ->
                            RawHttpResponse(
                                code = resp.code,
                                isSuccessful = resp.isSuccessful,
                                body = runCatching { resp.body?.string().orEmpty() }.getOrDefault(""),
                            )
                        }
                    }
                    parsed.fold(
                        onSuccess = { value -> if (cont.isActive) cont.resume(value) },
                        onFailure = { error -> if (cont.isActive) cont.resumeWithException(error) },
                    )
                }
            })
        }

    private fun parseUploadedFile(
        body: String,
        httpCode: Int,
        fallbackFilename: String,
        fallbackPurpose: String,
    ): UploadedFile {
        val obj = JsonHttp.parseObject(body)
            ?: throw ApiException(ApiError.BadResponse(httpCode, errorSnippet(body)))
        if (obj["error"] != null) {
            throw ApiException(ApiError.BadResponse(httpCode, messageFromErrorBody(body)))
        }
        val id = obj.stringOrNull("id")?.trim()?.takeIf { it.isNotEmpty() }
            ?: throw ApiException(ApiError.BadResponse(httpCode, errorSnippet(body)))
        val bytes = parseLongOrNull(obj["bytes"])?.coerceAtLeast(0L) ?: 0L
        val filename = obj.stringOrNull("filename")?.trim()?.takeIf { it.isNotEmpty() } ?: fallbackFilename
        val purpose = obj.stringOrNull("purpose")?.trim()?.takeIf { it.isNotEmpty() } ?: fallbackPurpose
        val expiresAt = parseLongOrNull(obj["expires_at"])
        return UploadedFile(
            id = id,
            bytes = bytes,
            filename = filename,
            purpose = purpose,
            expiresAt = expiresAt,
        )
    }

    private fun parseLongOrNull(element: kotlinx.serialization.json.JsonElement?): Long? {
        val raw = (element as? JsonPrimitive)?.contentOrNull?.trim() ?: return null
        return raw.toLongOrNull() ?: raw.toDoubleOrNull()?.toLong()
    }

    private fun rememberResolvedBase(working: String) {
        if (working == resolvedBaseUrl) return
        resolvedBaseUrl = working
        BaseUrl.remember(configuredBaseUrl, working)
    }

    private fun confirmsDeletion(body: String, requestedId: String): Boolean {
        val response = JsonHttp.parseObject(body) ?: return false
        val responseId = response.stringOrNull("id")?.trim() ?: return false
        val deleted = (response["deleted"] as? JsonPrimitive)?.booleanOrNull ?: false
        return responseId == requestedId && deleted
    }

    private fun filesBase(base: String): String =
        if (isOfficialDeepSeekOrigin(base)) officialFilesBase(base) else base

    private fun officialFilesBase(base: String): String {
        val candidate = BaseUrl.withScheme(BaseUrl.normalize(base))
        val url = candidate.toHttpUrlOrNull() ?: return base
        if (!isOfficialDeepSeekOrigin(url) || url.encodedPath != "/v1" || url.encodedQuery != null) {
            return base
        }
        return url.newBuilder()
            .encodedPath("/")
            .build()
            .toString()
            .trimEnd('/')
    }

    private fun isOfficialDeepSeekOrigin(base: String): Boolean {
        val candidate = BaseUrl.withScheme(BaseUrl.normalize(base))
        val url = candidate.toHttpUrlOrNull() ?: return false
        return isOfficialDeepSeekOrigin(url)
    }

    private fun isOfficialDeepSeekOrigin(url: HttpUrl): Boolean =
        url.isHttps &&
            url.host == OFFICIAL_DEEPSEEK_HOST &&
            url.port == 443 &&
            url.username.isEmpty() &&
            url.password.isEmpty()

    private fun filesEndpoint(urlBase: String): String = BaseUrl.endpoint(filesBase(urlBase), FILES_PATH)

    private data class RawHttpResponse(
        val code: Int,
        val isSuccessful: Boolean,
        val body: String,
    )

    companion object {
        private const val OFFICIAL_DEEPSEEK_HOST = "api.deepseek.com"

        const val FILES_PATH: String = "files"
        const val FORM_PURPOSE: String = "purpose"
        const val FORM_FILE: String = "file"

        const val PURPOSE_TEXT_RECOGNITION: String = "text_recognition"
        const val PURPOSE_RICH_LAYOUT_RECOGNITION: String = "rich_layout_recognition"
        const val PURPOSE_USER_DATA: String = "user_data"

        /** 文档类（PDF / DOC / DOCX / PPT / PPTX）单文件上限：100 MiB（官方 Files API）。 */
        const val MAX_DOCUMENT_BYTES: Long = 100L * 1024L * 1024L

        /** 图片单文件上限：64 MiB（官方 Files API）。 */
        const val MAX_IMAGE_BYTES: Long = 64L * 1024L * 1024L

        /** 纯文本 / 表格 / 电子书单文件上限：32 MiB（官方 Files API）。 */
        const val MAX_USER_DATA_BYTES: Long = 32L * 1024L * 1024L

        const val WRITE_TIMEOUT_SECONDS: Long = 600L
        const val READ_TIMEOUT_SECONDS: Long = 600L
        const val CALL_TIMEOUT_SECONDS: Long = 600L

        /** Pure URL selection helper; it does not perform DNS lookup or send a request. */
        internal fun filesEndpointFor(baseUrl: String): String =
            BaseUrl.endpoint(
                if (isOfficialDeepSeekOrigin(baseUrl)) officialFilesBase(baseUrl) else baseUrl,
                FILES_PATH,
            )

        private fun isOfficialDeepSeekOrigin(baseUrl: String): Boolean {
            val candidate = BaseUrl.withScheme(BaseUrl.normalize(baseUrl))
            val url = candidate.toHttpUrlOrNull() ?: return false
            return isOfficialDeepSeekOrigin(url)
        }

        private fun isOfficialDeepSeekOrigin(url: HttpUrl): Boolean =
            url.isHttps &&
                url.host == OFFICIAL_DEEPSEEK_HOST &&
                url.port == 443 &&
                url.username.isEmpty() &&
                url.password.isEmpty()

        private fun officialFilesBase(baseUrl: String): String {
            val candidate = BaseUrl.withScheme(BaseUrl.normalize(baseUrl))
            val url = candidate.toHttpUrlOrNull() ?: return baseUrl
            // 仅标准根 /v1 base 映射到官方根 Files API；代理路径、其他版本路径和查询参数保留原样。
            if (!isOfficialDeepSeekOrigin(url) || url.encodedPath != "/v1" || url.encodedQuery != null) {
                return baseUrl
            }
            return url.newBuilder().encodedPath("/").build().toString().trimEnd('/')
        }

        private val DOCUMENT_EXTENSIONS = setOf("pdf", "doc", "docx", "ppt", "pptx")
        private val TEXT_RECOGNITION_IMAGE_EXTENSIONS = setOf("bmp", "tif", "tiff")
        private val TEXT_RECOGNITION_IMAGE_MIME_TYPES = setOf("image/bmp", "image/tiff")

        private val DOCUMENT_MIME_TYPES = setOf(
            "application/pdf",
            "application/msword",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            "application/vnd.ms-powerpoint",
            "application/vnd.openxmlformats-officedocument.presentationml.presentation",
        )

        private val EXTENSION_MIME_MAP = mapOf(
            "pdf" to "application/pdf",
            "doc" to "application/msword",
            "docx" to "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            "ppt" to "application/vnd.ms-powerpoint",
            "pptx" to "application/vnd.openxmlformats-officedocument.presentationml.presentation",
            "xls" to "application/vnd.ms-excel",
            "xlsx" to "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
            "txt" to "text/plain",
            "md" to "text/markdown",
            "csv" to "text/csv",
            "json" to "application/json",
            "epub" to "application/epub+zip",
            "mobi" to "application/x-mobipocket-ebook",
            "jpg" to "image/jpeg",
            "jpeg" to "image/jpeg",
            "png" to "image/png",
            "webp" to "image/webp",
            "gif" to "image/gif",
            "bmp" to "image/bmp",
            "tif" to "image/tiff",
            "tiff" to "image/tiff",
        )

        /**
         * 按文件名扩展名或 MIME 类型推导 DeepSeek Files API `purpose`：
         * - PDF / DOC / DOCX / PPT / PPTX / BMP / TIFF → `"text_recognition"`
         * - JPEG / PNG / WebP / GIF、文本 / 表格 / 电子书 → `"user_data"`
         */
        fun purposeForFile(fileName: String, mime: String? = null): String {
            val ext = extensionOf(fileName)
            if (ext in DOCUMENT_EXTENSIONS || ext in TEXT_RECOGNITION_IMAGE_EXTENSIONS) {
                return PURPOSE_TEXT_RECOGNITION
            }
            val cleanMime = mime?.substringBefore(';')?.trim()?.lowercase(Locale.ROOT)
            if (cleanMime in DOCUMENT_MIME_TYPES || cleanMime in TEXT_RECOGNITION_IMAGE_MIME_TYPES) {
                return PURPOSE_TEXT_RECOGNITION
            }
            return PURPOSE_USER_DATA
        }

        /**
         * 按 purpose-only 旧契约返回该 purpose 可收文件类别中的保守上限：`user_data` 为 32 MiB，
         * 两种识别 purpose 为 64 MiB。文件名和 MIME 可用时请改用 [maxBytesForFile]，文档才可用 100 MiB。
         */
        fun maxBytesForPurpose(purpose: String): Long = when (purpose) {
            // These purposes also accept images, whose official limit is lower than documents.
            PURPOSE_TEXT_RECOGNITION, PURPOSE_RICH_LAYOUT_RECOGNITION -> MAX_IMAGE_BYTES
            else -> MAX_USER_DATA_BYTES
        }

        /**
         * 按文件类别返回上传上限（字节）：文档 100 MiB、图片 64 MiB、纯文本/表格/电子书 32 MiB。
         * 如果扩展名和 MIME 指向不同类别，则保守回退到 32 MiB，避免元数据不一致时放宽上限。
         *
         * DeepSeek Files API documentation: https://api-docs.deepseek.com/guides/files_api/（Upload a File / Limits）。
         */
        fun maxBytesForFile(fileName: String, mime: String? = null): Long {
            val extension = extensionOf(fileName)
            val cleanMime = mime?.substringBefore(';')?.trim()?.lowercase(Locale.ROOT)
            val extensionCategory = categoryForExtension(extension)
            val mimeCategory = categoryForMime(cleanMime)
            if (extensionCategory != null && mimeCategory != null && extensionCategory != mimeCategory) {
                return MAX_USER_DATA_BYTES
            }
            return when (extensionCategory ?: mimeCategory) {
                FileCategory.DOCUMENT -> MAX_DOCUMENT_BYTES
                FileCategory.IMAGE -> MAX_IMAGE_BYTES
                null, FileCategory.OTHER -> MAX_USER_DATA_BYTES
            }
        }

        private enum class FileCategory { DOCUMENT, IMAGE, OTHER }

        private fun categoryForExtension(extension: String): FileCategory? = when (extension) {
            in DOCUMENT_EXTENSIONS -> FileCategory.DOCUMENT
            in IMAGE_EXTENSIONS -> FileCategory.IMAGE
            in OTHER_USER_DATA_EXTENSIONS -> FileCategory.OTHER
            else -> null
        }

        private fun categoryForMime(mime: String?): FileCategory? = when (mime) {
            in DOCUMENT_MIME_TYPES -> FileCategory.DOCUMENT
            in IMAGE_MIME_TYPES -> FileCategory.IMAGE
            in OTHER_USER_DATA_MIME_TYPES -> FileCategory.OTHER
            else -> null
        }

        private val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "webp", "gif", "bmp", "tif", "tiff")
        private val OTHER_USER_DATA_EXTENSIONS = setOf("txt", "md", "csv", "json", "xls", "xlsx", "epub", "mobi")
        private val IMAGE_MIME_TYPES = setOf(
            "image/jpeg", "image/png", "image/webp", "image/gif", "image/bmp", "image/tiff",
        )
        private val OTHER_USER_DATA_MIME_TYPES = setOf(
            "text/plain", "text/markdown", "text/csv", "application/json",
            "application/vnd.ms-excel",
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
            "application/epub+zip", "application/x-mobipocket-ebook",
        )

        private fun extensionOf(fileName: String): String =
            fileName.trim().substringAfterLast('.', "").lowercase(Locale.ROOT)

        private fun sanitizeFileName(fileName: String): String {
            val cleaned = fileName.trim().replace(Regex("[\\r\\n\"\\\\/]"), "_")
            return cleaned.ifBlank { "upload.bin" }
        }

        private fun mediaTypeForFile(fileName: String): String =
            EXTENSION_MIME_MAP[extensionOf(fileName)] ?: "application/octet-stream"
    }
}
