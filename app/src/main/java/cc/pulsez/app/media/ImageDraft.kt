package cc.pulsez.app.media

/**
 * 草稿里一张图的状态（v0.5.0 识图；`）。
 *
 * - [PREPARING]：正在拷贝/压缩（IO 线程），此时禁止发送；
 * - [READY]：压缩落盘完成，可以发送；
 * - [FAILED]：处理失败，显示原因 + 让用户删掉/换一张，不阻塞打字发送。
 */
enum class DraftStatus {
    PREPARING,
    READY,
    FAILED,
}

/**
 * 草稿里的一张图（UI 状态，不是 DB 实体）。
 *
 * @param id 附件 id（PREPARING 阶段是临时 id，成功后替换成 DB 里的附件 id）
 * @param localPath DB 相对路径（`attachments/<id>.jpg`）；PREPARING 阶段为空串
 * @param status 见 [DraftStatus]
 * @param errorCode 失败原因码（[ImageErrorCodes] / 仓储错误码），UI 换成 人话
 * @param firstFrameOnly 动图只取了第一帧（UI 说明一句「动图只发第一帧」）
 */
data class DraftImage(
    val id: String,
    val localPath: String,
    val status: DraftStatus,
    val errorCode: String? = null,
    val firstFrameOnly: Boolean = false,
)

/**
 * 草稿规则：最多 3 张、准备中禁止发送、失败态不参与发送（v0.5.0 识图）。
 *
 * 纯 Kotlin、无 Android 依赖：直接 JVM 单测（`ImageDraftRulesTest`）。
 * 这些规则 UI 与发送链路共用同一份，避免"按钮能点、发送却被拒"两套口径。
 */
object DraftRules {

    /** 一轮最多 3 张。*/
    const val MAX_IMAGES: Int = ImageMath.MAX_IMAGES

    /** 能不能再加一张：准备中/已就绪的都占名额，失败的不占（用户可以删掉换新的）。 */
    fun canAdd(drafts: List<DraftImage>): Boolean =
        drafts.count { it.status != DraftStatus.FAILED } < MAX_IMAGES

    /** 占名额的张数（准备中 + 已就绪）。 */
    fun activeCount(drafts: List<DraftImage>): Int =
        drafts.count { it.status != DraftStatus.FAILED }

    /**
     * 准备发送的附件 id（只含 READY，顺序与草稿顺序一致）。
     *
     * 说明（v0.5.0 `）：发送链路目前是直接对 READY 的
     * [DraftImage] 取 `id`/`localPath`（还要读体积、拼缩略图路径），没有再走这个函数；
     * 但它与 [readyPaths] 是同一份"只有 READY 参与发送"的规则，留给后续需要纯 id 列表的
     * 调用点（如上传/重试），删掉会让规则散回调用方。**不是死代码，别当垃圾清掉。**
     */
    fun bindableIds(drafts: List<DraftImage>): List<String> =
        drafts.filter { it.status == DraftStatus.READY }.map { it.id }

    /** 已就绪的图片本地路径（UI 立即显示缩略图用）。 */
    fun readyPaths(drafts: List<DraftImage>): List<String> =
        drafts.filter { it.status == DraftStatus.READY }.map { it.localPath }

    fun isPreparing(drafts: List<DraftImage>): Boolean =
        drafts.any { it.status == DraftStatus.PREPARING }

    fun hasReady(drafts: List<DraftImage>): Boolean =
        drafts.any { it.status == DraftStatus.READY }

    /** 发送被阻塞的原因（`null` = 可以点发送）。 */
    enum class SendBlock {
        /** 还没有文字也没有就绪图片。 */
        EMPTY,

        /** 图片还在处理中：等它完成再发（对齐官方 App「请在上传完成后发送」）。 */
        IMAGES_PREPARING,
    }

    /**
     * 能不能点发送。
     *
     * @param hasText 输入框是否有非空文字
     * @param streaming 正在生成（流式中发送按钮变「停下」，不走这里）
     */
    fun sendBlock(drafts: List<DraftImage>, hasText: Boolean, streaming: Boolean): SendBlock? {
        if (streaming) return null // 流式时按钮是「停下」，发送判定不参与
        if (isPreparing(drafts)) return SendBlock.IMAGES_PREPARING
        if (hasText || hasReady(drafts)) return null
        return SendBlock.EMPTY
    }

    /** 发送前体积检查（口径；`null` = 通过）。*/
    fun sendLimitProblem(drafts: List<DraftImage>, sizeOf: (DraftImage) -> Long): ImageMath.SendLimitProblem? =
        ImageMath.sendLimitProblem(
            drafts.filter { it.status == DraftStatus.READY }.map(sizeOf),
        )
}
