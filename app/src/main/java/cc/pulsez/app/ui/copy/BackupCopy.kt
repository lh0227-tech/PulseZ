package cc.pulsez.app.ui.copy

import cc.pulsez.app.data.ExportOverLimit
import cc.pulsez.app.data.repo.RestoreResult
import cc.pulsez.app.importer.BackupIssue
import cc.pulsez.app.importer.BackupParser
import cc.pulsez.app.importer.BackupPreview
import cc.pulsez.app.mem.MemTime
import java.util.Locale

/**
 * 「备份导出 / 识别 / 恢复」的用户可见文案（v0.5.2）。
 *
 * 为什么收口成常量：Compose 页面里的字面量没法在纯 JVM 里逐字断言（同 `ClearCopy`/`VoiceCopy`），
 * 而这份文案同时承担四件产品承诺：
 * 1. **导出了什么、没导出什么**——图片本体/头像不跟着走（附件只带元数据，图片本体不带）；角色已删除的整段对话没进备份（但还在本机）；
 * 2. **备份真的能装回来**（起）——预览→恢复→结果；恢复会**新增一份副本、不覆盖**现在的内容；
 * 3. **哪 8 项不会跟着恢复**（H；运行清单见下方 `NOT_RESTORED_*` 共 8 条）——预览页永远展示，不让用户误以为是「完整备份」；
 * 4. **失败要给出路**——没导出成功时别用没写完的文件；没恢复成时手机上原来的数据没有动。
 *
 * 数字纪律：所有上限/计数数字都从常量经 [formatCount] 在运行期生成，
 * 源码里不出现第二份数字字面或千分位字面（测试做源码扫描）。
 *
 * 文案纪律：不出现 token / 上下文 / 窗口 / 向量 / embedding / 缓存 / 关键词 / 记忆检索；
 * 角色代词只用 TA；`restoreIndexMissing` 只使用「以后可能找不全」「可能会补上」等
 * 如实说明局限的措辞，不使用「可能恢复不了」这类推卸式说法。
 */
internal object BackupCopy {

    // ------------------------------------------------------------ 设置页「导出备份」入口（v0.5.4）

    /**
     * 设置页「关于」组里这一行的标题（v0.5.4）。
     *
     * 用户可见处只说「备份」、不再在右侧附带 `JSON` 文件格式缩写：格式是技术细节，
     * 用户需要知道的是「这是什么 / 要不要用 / 不用会怎样」，由 [SETTINGS_DESCRIPTION] 回答。
     */
    const val SETTINGS_TITLE = "导出备份"

    /**
     * 「导出备份」行下方说明（v0.5.4三问逐条落地）：
     * - 这是什么：把 TA、对话和记忆存成一个文件（点一下会走系统保存位置，见 `AppRoot` 导出接线）；
     * - 要不要用：换手机或重装后能装回来；
     * - 不用会怎样：不导出也能用，只是这些就带不走了；
     * - 范围排除项与导出回执 [EXPORT_SUCCESS] 及协议 §4 同口径（图片本体和头像、用量和花费、
     *   服务商配置都不在备份里），避免入口承诺比真实导出大（的教训）。
     */
    const val SETTINGS_DESCRIPTION =
        "把 TA、对话和记忆存成一个文件，换手机或重装后能装回来。" +
            "不导出也能用，只是这些就带不走了。" +
            "图片本体和头像、用量和花费、服务商配置不在里面。"

    // ------------------------------------------------------------ 备份预览

    /** 备份预览标题。 */
    const val PREVIEW_TITLE = "这是一份 PulseZ 备份"

    /** 预览首要事实（起：能装回来；具体限制见「不会恢复」清单）。*/
    const val PREVIEW_READY = "这一版能把 TA、对话和记忆装回这台手机。"

    /** 拒绝态标题：这份备份读不了（不降级为人设）。 */
    const val REJECT_TITLE = "这份备份读不了"

    // ------------------------------------------------------------ 备份两态出口（禁用编辑步的 onPickFile）

    /** 备份两态「换一个文件」按钮（起只服务**拒绝态**）。*/
    const val PICK_ANOTHER = "换一个文件"

    /** 备份两态「取消」按钮（回编辑/初始步）。 */
    const val CANCEL = "取消"

    // ------------------------------------------------------------ 恢复

    /** 预览态主按钮：真的恢复。 */
    const val RESTORE = "恢复"

    /** 恢复口径（J-1/J-3）：新增一份副本、不覆盖现有内容（每次恢复都新建一套 id）。 */
    const val RESTORE_NOTE = "恢复会新增一份 TA 和对话，不会覆盖现在的内容。"

    /** 执行中（进度未就绪时的兜底文案；`restoreProgress` 的 total<=0 分支也用它）。 */
    const val RESTORE_RUNNING = "正在恢复……"

    /** 结果成功标题。 */
    const val RESTORE_DONE_TITLE = "恢复好了"

    /** 空备份（没有任何能装回来的内容）。 */
    const val RESTORE_DONE_EMPTY = "这份备份里没有能装回来的内容。"

    /** 结果失败：只在事务前/事务内失败出现，此时「原来的数据没有动」成立。 */
    const val RESTORE_FAILED = "没恢复成。手机上原来的数据没有动，再试一次？"

    /** 失败重试按钮（`onRestore`）。 */
    const val RESTORE_RETRY = "再试一次"

    /** 结果页「好」→ 回首页（不自动跳进聊天）。 */
    const val RESTORE_OK = "好"

    /** 「不会恢复」清单标题（预览页**永远**展示，这是承诺的一部分 H）。*/
    const val NOT_RESTORED_TITLE = "这些不会跟着恢复："

    const val NOT_RESTORED_AVATAR = "头像（还是用名字首字）"
    const val NOT_RESTORED_FILES = "图片和文件本身（当时发过图的消息只剩文字）"
    const val NOT_RESTORED_SEMANTIC =
        "按意思找旧事（恢复后只能按原话找；要按意思找，去记忆面板点「让 TA 能按意思找旧事」）"
    const val NOT_RESTORED_DELETED = "已经删掉的消息和 TA"
    const val NOT_RESTORED_RELATION = "关系阶段、昵称和心情"
    const val NOT_RESTORED_USAGE = "用量和费用记录（恢复后从零开始记）"
    const val NOT_RESTORED_SETTINGS = "服务商地址和密钥（恢复后要重新填）"
    const val NOT_RESTORED_MEMORY_SOURCE = "每条记忆当时是从哪句话记下的（记忆本身会回来）"

    // ------------------------------------------------------------ 导出回执（确定性B-6）

    /** 导出成功：同时说清图片本体/头像不会跟着备份走，用量、花费和服务商配置也不在备份里。 */
    const val EXPORT_SUCCESS = "导出好了。图片本体和头像不会跟着备份走；用量、花费和服务商配置也不在备份里。"

    /** 写失败（SAF 流打不开/写一半失败）：明确让用户别用没写完的文件。 */
    const val EXPORT_WRITE_FAILED = "没导出成功。别用这个没写完的文件，再试一次。"

    /** 数据读取失败（payload 都没生成，目标文件里没有有效内容）。 */
    const val EXPORT_DATA_FAILED = "没导出成功。这次没读出数据，再试一次。"

    /** 文件入口读失败：文件太大（上限数字由 [BackupParser.FILE_MAX_MB] 拼，不能 const）。 */
    val READ_FILE_TOO_LARGE: String
        get() = "这个文件太大了（这一版支持到 ${BackupParser.FILE_MAX_MB} MB）。"

    /** 文件入口读失败：读不到这个文件（换一个再试）。 */
    const val READ_FILE_FAILED = "没读到这个文件。换一个再试？"

    // ------------------------------------------------------------ 计数与原因

    /** 备份里有什么：角色/对话/消息的规模（数字运行期千分位）。 */
    fun previewCounts(p: BackupPreview): String =
        "里面有 ${formatCount(p.characterCount)} 个 TA、" +
            "${formatCount(p.threadCount)} 段对话、${formatCount(p.messageCount)} 条消息。"

    /** 这份备份导出时因角色已删除而没进的对话数（与 [exportSkippedOrphans] 同口径）。 */
    fun previewSkippedOrphans(n: Int): String =
        "导出这份备份时，有 ${formatCount(n)} 段对话因为角色已删除，没有进这份备份。"

    /** 6 条拒绝原因，语义确定、互不相同（tag 见）。*/
    fun issueText(issue: BackupIssue): String = when (issue) {
        BackupIssue.DAMAGED -> "这个文件像是没写完，或者被改坏了。换一份备份再试？"
        BackupIssue.TOO_NEW -> "这份备份是新版本导出的，这一版认不了。先去更新 App，再回来装。"
        BackupIssue.TOO_LARGE -> "备份太大了（超过 ${BackupParser.FILE_MAX_MB} MB），这一版装不回来。"
        BackupIssue.TOO_MANY_MESSAGES ->
            "备份里的消息超过 ${formatCount(BackupParser.MAX_MESSAGES)} 条，这一版装不回来。"
        BackupIssue.TOO_MANY_THREADS ->
            "备份里的对话超过 ${formatCount(BackupParser.MAX_THREADS)} 段，这一版装不回来。"
        BackupIssue.TOO_MANY_CHARACTERS ->
            "备份里的 TA 超过 ${formatCount(BackupParser.MAX_CHARACTERS)} 个，这一版装不回来。"
    }

    /** 孤儿跳过回执（长 Toast）：说清哪段对话没进备份、但还在本机（不静默）。*/
    fun exportSkippedOrphans(n: Int): String =
        "导出好了，但有 ${formatCount(n)} 段对话因为角色已删除，没有进备份。" +
            "它们还在这台手机上。图片和头像也不在备份里。"

    /**
     * 超限回执（确定性，不用「可能」）：4 条按原因分开；与孤儿跳过同时成立时**同一句追加**，
     * 不得只剩超限文案。
     */
    fun exportOverLimit(reason: ExportOverLimit, skippedOrphanThreads: Int = 0): String {
        val base = when (reason) {
            ExportOverLimit.BYTES ->
                "导出好了。不过这份备份超过 ${BackupParser.FILE_MAX_MB} MB，这一版装不回来。"
            ExportOverLimit.MESSAGES ->
                "导出好了。不过消息超过 ${formatCount(BackupParser.MAX_MESSAGES)} 条，这一版装不回来。"
            ExportOverLimit.THREADS ->
                "导出好了。不过对话超过 ${formatCount(BackupParser.MAX_THREADS)} 段，这一版装不回来。"
            ExportOverLimit.CHARACTERS ->
                "导出好了。不过 TA 超过 ${formatCount(BackupParser.MAX_CHARACTERS)} 个，这一版装不回来。"
        }
        if (skippedOrphanThreads <= 0) return base
        return base + "另有 ${formatCount(skippedOrphanThreads)} 段对话因为角色已删除，没有进备份。"
    }

    // ------------------------------------------------------------ 恢复文案

    /** `"导出时间：2026-11-21 20:31"`；解析不到 → `"导出时间：不明"`（`mem/MemTime.kt:78-84`）。 */
    fun exportedAtText(exportedAt: Long?): String =
        "导出时间：${MemTime.formatDateTime(exportedAt ?: 0L) ?: "不明"}"

    /** `"正在恢复……（3/12 段对话）"`；`total <= 0` 时返回 [RESTORE_RUNNING]，避免 "0/0"。 */
    fun restoreProgress(done: Int, total: Int): String =
        if (total <= 0) {
            RESTORE_RUNNING
        } else {
            "正在恢复……（${formatCount(done)}/${formatCount(total)} 段对话）"
        }

    /** `"装回来 3 个 TA、2 段对话、128 条消息、24 条记忆。"`（数字走 [formatCount]）。 */
    fun restoreDoneCounts(r: RestoreResult.Restored): String =
        "装回来 ${formatCount(r.characterCount)} 个 TA、" +
            "${formatCount(r.threadCount)} 段对话、" +
            "${formatCount(r.messageCount)} 条消息、" +
            "${formatCount(r.memoryCount)} 条记忆。"

    /** `"有 1 段对话的 TA 已经不在备份里，没有装回来。"` */
    fun restoreSkippedOrphans(n: Int): String =
        "有 ${formatCount(n)} 段对话的 TA 已经不在备份里，没有装回来。"

    /** `"有 2 条内容找不到原来属于哪个 TA，已经略过。"`（members+speakers+memories 聚合）。 */
    fun restoreIgnoredRefs(n: Int): String =
        "有 ${formatCount(n)} 条内容找不到原来属于哪个 TA，已经略过。"

    /** `"备份里有 3 张图，图本身不在备份里，装不回来。"`（结果页） */
    fun restoreFilesNotRestored(n: Int): String =
        "备份里有 ${formatCount(n)} 张图，图本身不在备份里，装不回来。"

    /** `"备份里有 3 张图；图本身不会跟着恢复。"`（预览页） */
    fun previewFilesNotRestored(n: Int): String =
        "备份里有 ${formatCount(n)} 张图；图本身不会跟着恢复。"

    /**
     * 索引不完整（rev7 块数口径 + rev8 文案，逐字锁死）：`n` = `Restored.indexSkipped` 的**块数**。
     *
     * 缺的是检索块本身；记忆面板的「补建」只处理已存在的块，所以不承诺一定补齐、也不引导去面板；
     * 唯一可执行的出路是「再恢复一次」并如实说明会新增副本。不含任何禁词（`TECH_BANNED` 全扫）。
     */
    fun restoreIndexMissing(n: Int): String =
        "有 ${formatCount(n)} 处旧事这次没整理好，以后可能找不全。" +
            "再恢复一次这份备份（会新增副本），可能会补上；不会覆盖现在的内容。"

    /**
     * 数字的**唯一**格式化入口（千分位在运行期生成，源码里不许出现千分位字面）。
     *
     * 用 `Locale.US` 固定成逗号分隔，避免跟随系统区域变成 `15.000`/`15 000` 让文案两套。
     */
    internal fun formatCount(n: Int): String = String.format(Locale.US, "%,d", n)
}
