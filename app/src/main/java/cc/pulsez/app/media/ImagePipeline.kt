package cc.pulsez.app.media

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.core.content.FileProvider
import cc.pulsez.app.data.attachments.AttachmentStore
import cc.pulsez.app.data.db.AttachmentEntity
import cc.pulsez.app.data.db.AttachmentKind
import cc.pulsez.app.data.repo.AttachmentRepository
import cc.pulsez.app.util.Ids
import java.io.File
import java.io.IOException
import java.io.InputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 图片导入流水线（v0.5.0）。
 *
 * 调用时序（关键）：
 * 1. 选择器/相机回调里**先拷贝到 app 私有目录**（SAF/Photo Picker 的临时授权只在回调期间可靠，
 *    见 `AppRoot` 里 ZIP 导入的正确先例）；
 * 2. IO 线程 EXIF 转正 → 长边 ≤1536 → JPEG 0.8（[ImageCompressor]）；
 * 3. 压缩后的字节写进 `filesDir/attachments/<id>.jpg`，DB 里落 DRAFT → READY；
 * 4. 失败抛 [ImageProcessingException]，草稿 UI 显示 FAILED + 人话。
 *
 * 串行：调用方（`ChatViewModel`）用互斥锁串行调用，避免 3 张图同时解码的内存峰值 ×3。
 *
 * 隐私：只处理本地文件，**不打印路径/内容**；临时文件处理完立刻删，启动兜底清 24h 残留。
 *
 * @param repo 附件仓储（落盘 + DB 行）
 * @param compressor 可注入（测试/替换）
 */
class ImagePipeline(
    private val context: Context,
    private val repo: AttachmentRepository,
    private val compressor: ImageCompressor = ImageCompressor(),
) {

    /** 导入成功的结果：READY 附件 + 是否只取了动图第一帧（UI 要说明一句）。 */
    data class Imported(
        val attachment: AttachmentEntity,
        val firstFrameOnly: Boolean,
    )

    private val tempDir: File
        get() = File(context.cacheDir, TEMP_DIR_NAME)

    /**
     * 从相册/文档选择器的 `Uri` 导入。
     *
     * 先整份拷到 `cacheDir/camera/` 私有临时文件（边拷边限 10MB），再压缩；
     * 无论成败临时文件都删掉。进程被杀留下的残留由 [cleanupStaleTemp] 兜底（24h）。
     *
     * @param ownerThreadId single-chat thread owning the resulting draft, including while it is unsent
     * @throws ImageProcessingException `TOO_LARGE` / `BAD_IMAGE` / `OOM` / `IO`
     */
    suspend fun importPicked(uri: Uri, ownerThreadId: String): Imported = withContext(Dispatchers.IO) {
        cleanupStaleTemp()
        val mime = runCatching { context.contentResolver.getType(uri) }.getOrNull()
        val fileName = displayName(uri)
        val temp = File(ensureTempDir(), "pick-${Ids.new()}.bin")
        try {
            copyLimited(uri, temp)
            importLocalFile(file = temp, sourceMime = mime, fileName = fileName, ownerThreadId = ownerThreadId)
        } finally {
            runCatching { temp.delete() }
        }
    }

    /**
     * 从相机临时文件导入（`TakePicture` 写到我们提供的 `cacheDir/camera/<id>.jpg`）。
     *
     * 相机输出已经是私有目录里的文件，不需要再拷一次；处理完（成功/失败）都删掉。
     *
     * @param ownerThreadId single-chat thread owning the resulting draft, including while it is unsent
     * @throws ImageProcessingException 同 [importPicked]
     */
    suspend fun importCamera(file: File, ownerThreadId: String): Imported = withContext(Dispatchers.IO) {
        cleanupStaleTemp()
        try {
            importLocalFile(file = file, sourceMime = "image/jpeg", fileName = null, ownerThreadId = ownerThreadId)
        } finally {
            runCatching { file.delete() }
        }
    }

    // ------------------------------------------------------------ 内部

    private suspend fun importLocalFile(
        file: File,
        sourceMime: String?,
        fileName: String?,
        ownerThreadId: String,
    ): Imported {
        val result = compressor.compress(file)
        val store = AttachmentStore.required()
        // 压缩产物一律 JPEG（`ImageCompressor` 保证），其它格式的 mime 只用于判断"是不是动图"。
        // Keep new unbound drafts attributable to their single chat so role deletion can remove them.
        // Basis: AttachmentDao.forCharacterSingleChats and CharacterDeletionRepository.
        val entity = repo.createDraft(
            store = store,
            kind = AttachmentKind.IMAGE,
            mime = "image/jpeg",
            bytes = result.bytes,
            fileName = fileName,
            width = result.width,
            height = result.height,
            ownerThreadId = ownerThreadId,
        )
        val ready = try {
            repo.markReady(entity.id)
        } catch (t: Throwable) {
            // 标记 READY 失败：把刚落的行/文件回滚掉，不留孤儿（启动清理也会兜底）。
            runCatching { repo.discardDraft(entity.id, store) }
            throw t
        }
        return Imported(
            attachment = ready,
            firstFrameOnly = ImageMath.isAnimatedMime(sourceMime),
        )
    }

    /** 把 `Uri` 流拷进临时文件，边拷边限 [ImageMath.MAX_INPUT_BYTES]，超限立刻中止。 */
    private fun copyLimited(uri: Uri, target: File) {
        val input: InputStream = try {
            context.contentResolver.openInputStream(uri)
        } catch (e: IOException) {
            throw ImageProcessingException(ImageErrorCodes.IO, e)
        } ?: throw ImageProcessingException(ImageErrorCodes.BAD_IMAGE)
        var total = 0L
        try {
            input.use { source ->
                target.outputStream().use { output ->
                    val buffer = ByteArray(COPY_CHUNK)
                    while (true) {
                        val read = source.read(buffer)
                        if (read < 0) break
                        if (read == 0) continue
                        total += read
                        if (total > ImageMath.MAX_INPUT_BYTES) {
                            throw ImageProcessingException(ImageErrorCodes.TOO_LARGE)
                        }
                        output.write(buffer, 0, read)
                    }
                }
            }
        } catch (e: ImageProcessingException) {
            throw e
        } catch (e: IOException) {
            throw ImageProcessingException(ImageErrorCodes.IO, e)
        }
    }

    private fun ensureTempDir(): File {
        val dir = tempDir
        if (!dir.isDirectory && !dir.mkdirs() && !dir.isDirectory) {
            throw ImageProcessingException(ImageErrorCodes.IO)
        }
        return dir
    }

    /** 清掉 24h 前的拍照/选择临时文件（进程被杀留下的残留；best-effort）。 */
    fun cleanupStaleTemp() {
        val dir = tempDir
        if (!dir.isDirectory) return
        val cutoff = System.currentTimeMillis() - TEMP_TTL_MS
        runCatching {
            dir.listFiles()?.forEach { file ->
                if (file.isFile && file.lastModified() < cutoff) file.delete()
            }
        }
    }

    /** 选中文件的显示名（仅展示用，不参与落盘路径；拿不到返回 null）。 */
    private fun displayName(uri: Uri): String? = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor ->
                if (!cursor.moveToFirst()) return@use null
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index >= 0 && !cursor.isNull(index)) cursor.getString(index) else null
            }
    }.getOrNull()?.takeIf { it.isNotBlank() }

    companion object {
        /** 拍照/选择临时目录名（`res/xml/file_paths.xml` 只暴露这个子目录）。 */
        const val TEMP_DIR_NAME: String = "camera"

        /** 临时文件保留上限：24h。 */
        private const val TEMP_TTL_MS: Long = 24 * 60 * 60 * 1000L

        private const val COPY_CHUNK = 64 * 1024
    }
}

/**
 * 拍照的临时文件与 FileProvider `Uri`（v0.5.0；官方 `TakePicture` + FileProvider 用法）。
 *
 * 权限语义（重要，`）：
 * - **manifest 不声明 `CAMERA`**：用系统相机 intent 时不需要相机权限；一旦声明反而必须运行时申请，
 *   还会破坏 App「网络和麦克风两个权限」的隐私基线；
 * - FileProvider authority 用 `${applicationId}.fileprovider`（`applicationId` 在 debug 带 `.debug`，
 *   两个变体同装不冲突）；
 * - `res/xml/file_paths.xml` 只暴露 `cacheDir/camera/`，不共享 filesDir/附件目录。
 *
 * 依据：https://developer.android.com/training/secure-file-sharing/setup-sharing
 * （FileProvider 配置模板）；https://developer.android.com/reference/androidx/activity/result/contract/ActivityResultContracts.TakePicture
 * （"take a picture saving it into the provided content-Uri"）；
 * https://developer.android.com/reference/android/provider/MediaStore（`ACTION_IMAGE_CAPTURE` 的 CAMERA 声明语义）。
 */
object CameraCapture {

    /** FileProvider authority：与 manifest 里的 `android:authorities` 必须逐字一致。 */
    fun authority(context: Context): String = "${context.packageName}.fileprovider"

    /** 新建一个相机输出文件（在 `cacheDir/camera/` 下；只暴露这个子目录给 FileProvider）。 */
    fun newOutputFile(context: Context): File {
        val dir = File(context.cacheDir, ImagePipeline.TEMP_DIR_NAME)
        if (!dir.isDirectory) dir.mkdirs()
        return File(dir, "capture-${Ids.new()}.jpg")
    }

    /** 把相机输出文件包成给系统相机的 `content://` Uri。 */
    fun uriFor(context: Context, file: File): Uri =
        FileProvider.getUriForFile(context, authority(context), file)
}
