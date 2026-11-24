package cc.pulsez.app.ui.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.speech.tts.TextToSpeech
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import cc.pulsez.app.audio.ReaderState
import cc.pulsez.app.audio.ReaderStatus
import cc.pulsez.app.data.settings.Settings
import cc.pulsez.app.data.settings.isDeepSeekProvider
import cc.pulsez.app.ui.config.ChatConfigCopy
import cc.pulsez.app.ui.copy.BackupCopy
import cc.pulsez.app.ui.copy.ImageCopy
import cc.pulsez.app.ui.copy.ReadCopy
import cc.pulsez.app.ui.copy.UpdateCopy
import cc.pulsez.app.ui.copy.VoiceCopy
import cc.pulsez.app.update.UpdateUiState
import cc.pulsez.app.update.VersionManifest

/**
 * 设置页。
 *
 * 文案纪律：不出现 token / 上下文 / 模型参数；"上限"默认不限制，由用户自己填。
 *
 * v0.4 新增「创作模式」开关。
 * - 默认关、用户主动开；**第一次打开时**给一次说明（普通 `AlertDialog`，非阻断，
 *   按钮「开启 / 先不用」），之后不再弹；
 * - 关掉不弹窗（"随时能关"）；聊天页顶栏另有「出戏」一键退出。
 * - 开关视觉是 Material 3 默认（开启态 `primary` = signal），不引入新视觉/动效。
 */
@Composable
fun SettingsScreen(
    settings: Settings,
    onBack: () -> Unit,
    /** 「聊天」行 → 聊天服务商设置页（`Route.Config`）。 */
    onOpenConnection: () -> Unit,
    /** 「让 TA 更会找旧事」行 → 找旧事独立设置页（`Route.FindOldThings`，用户指令 2026-11-21）。 */
    onOpenFindOldThings: () -> Unit,
    onOpenUsage: () -> Unit,
    onWindowTierChange: (Int) -> Unit,
    onOpenAgreement: () -> Unit,
    onExport: () -> Unit,
    onClearAll: () -> Unit,
    onReasoningEffortChange: (String) -> Unit = {},
    /**
     * 「创作模式」开关变化（只传目标值，不区分是开关本身还是弹窗里的「开启」）。
     *
     * 约定：**先** [onCreativeModeChange]，**再** [onCreativeModeIntroSeen]（由本页「开启」按钮保证）。
     * 反过来的话，只记了"说明已看过"、模式却没开，用户会以为开关坏了。
     */
    onCreativeModeChange: (Boolean) -> Unit = {},
    /** 首次说明已经给过（开启 / 先不用 / 点外面关掉都算），之后不再弹。 */
    onCreativeModeIntroSeen: () -> Unit = {},
    /**
     * 朗读器状态（v0.5）。
     *
     * 由 AppRoot 从 `AppContainer.reader` 收集后传入：音色行要用 `voices`，
     * 初始化失败时要在「声音」组里显示「这台手机暂时不能朗读」+ 出路。
     */
    readState: ReaderState = ReaderState(),
    /** 自动朗读开关变化（只传目标值；首用说明里的「开启」也走这里）。 */
    onAutoReadChange: (Boolean) -> Unit = {},
    /** 首用说明已看过（开启 / 先不用 / 点外面关掉都算）。 */
    onReadIntroSeen: () -> Unit = {},
    /** 语速档位变化（×100，0.8–1.5）。 */
    onReadRateChange: (Int) -> Unit = {},
    /** 音色变化（空串 = 系统默认/第一个可用中文音色）。 */
    onReadVoiceChange: (String) -> Unit = {},
    /**
     * 「检查更新」状态（v0.5.1）。
     *
     * 由 AppRoot 从 `AppContainer.updateRepository.state` 收集后传入：页面本身**不主动联网**，
     * 进设置只展示上次/启动时的检查结果（「用户打开设置页 → 读内存结果，不自动再请求」）。
     */
    updateState: UpdateUiState = UpdateUiState.Idle,
    /** 点「检查更新」行：手动检查（不受 24h 节流限制）。 */
    onCheckUpdate: () -> Unit = {},
    /** 用户点「稍后」：记下被跳过的那个新版本（同版本不再自动提示）。 */
    onIgnoreUpdate: (VersionManifest) -> Unit = {},
    /**
     * 点「去下载」：用浏览器打开清单里的 apkUrl。
     *
     * 返回**是否成功打开浏览器**（v0.5.2）：false 时对话框正文显示下载地址 +
     * 「复制地址」出路，不再只给一句 Toast。依据。
     */
    onDownloadUpdate: (String) -> Boolean = { false },
) {
    // 首次说明只在"还没看过"时由开关打开动作触发；点外面/返回关闭等同「先不用」。
    var showCreativeIntro by rememberSaveable { mutableStateOf(false) }
    // 朗读首用说明（v0.5）。两种打开方式：
    // - 第一次把开关从关打开：带「开启 / 先不用」两个按钮；
    // - 设置里的「朗读说明 · 再看一遍」：只有「知道了」，不改开关。
    var showReadIntro by rememberSaveable { mutableStateOf(false) }
    var readIntroFirstUse by rememberSaveable { mutableStateOf(false) }
    // v0.5 识图：「发图片说明 · 再看一遍」的回看弹窗（只读，不改任何设置）。
    var showImageIntro by rememberSaveable { mutableStateOf(false) }
    // 「检查更新」的非阻断对话框（v0.5.1）：只在用户点「有新版本」行时打开；
    // 本轮不承诺 App 内下载/安装，点「去下载」只跳浏览器（Phase 1）。
    var updateDialogManifest by remember { mutableStateOf<VersionManifest?>(null) }
    // 浏览器打不开的失败态（v0.5.2）：true 时对话框正文显示地址 + 「复制地址」。
    // 关窗 / 「稍后」/ 重试成功后复位，不残留到下次打开。
    var updateDownloadFailed by remember { mutableStateOf(false) }
    // 「去装语音包」的系统入口（修）：拿不到就 null，不显示假入口。
    val context = LocalContext.current
    val ttsInstallDataIntent = remember(context) { resolveTtsInstallDataIntent(context) }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack, modifier = Modifier.size(48.dp)) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
            }
            Text(
                text = "设置",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onBackground,
            )
        }

        // 「检查更新」放在设置最上方、所有分组之前（线框）：
        // - 值始终先显示当前版本（从 BuildConfig 读，不手写），有新版时同一行给「有新版本 X」+ 圆点；
        // - 点行 = 手动检查（Checking 时不可点）；有新版本时点行打开非阻断对话框（不新开页面）。
        // 依据 零新权限。
        UpdateRow(
            state = updateState,
            installedVersionName = cc.pulsez.app.BuildConfig.VERSION_NAME,
            onClick = {
                when (val current = updateState) {
                    // 有新版本：打开对话框（看更新内容 / 去下载 / 稍后），不重复请求。
                    is UpdateUiState.Available -> updateDialogManifest = current.manifest
                    // 检查中：整行已不可点（见 UpdateCopy.rowClickable），这里兜底不重复请求。
                    UpdateUiState.Checking -> Unit
                    // 没查过 / 已是最新 / 已跳过 / 上次没连上：都当作「手动再查一次」。
                    // 已跳过时也允许手动再查，不另加一个查看已跳过版本的入口。
                    else -> onCheckUpdate()
                }
            },
        )

        // 「聊天」组放最前，让用户能在首次说明后立即调整思考强度；该行必须首屏可见。
        // 「思考强度」只在 DeepSeek 端点显示；
        // 「创作模式」与端点无关，所有服务商都显示。
        GroupTitle("聊天")
        // 创作模式状态由 `Settings.creativeMode` 和 `Settings.creativeModeIntroSeen` 保存。
        CreativeModeRow(
            checked = settings.creativeMode,
            onCheckedChange = { enabled ->
                when {
                    // 关：直接关，不弹说明（「随时能关」）。
                    !enabled -> onCreativeModeChange(false)
                    // 说明只给一次：已经看过就直接开。
                    settings.creativeModeIntroSeen -> onCreativeModeChange(true)
                    // 第一次开：先给一次说明，用户在弹窗里点「开启」才真正打开。
                    else -> showCreativeIntro = true
                }
            },
        )
        if (settings.isDeepSeekProvider()) {
            ReasoningEffortSection(
                thinkingEnabled = settings.thinkingEnabled,
                selectedEffort = settings.reasoningEffort,
                onSelect = onReasoningEffortChange,
            )
        }

        // 「声音」组（v0.5 建议放在「聊天」组下方）。
        // 自动朗读与端点无关（系统 TTS），所以所有服务商都显示。
        GroupTitle(ReadCopy.GROUP)
        ReadToggleRow(
            checked = settings.autoRead,
            onCheckedChange = { enabled ->
                when {
                    // 关：立即关，不弹说明（随时能关）。正在读的那条由 AppRoot 负责停。
                    !enabled -> onAutoReadChange(false)
                    // 说明只给一次：已经看过就直接开。
                    settings.readIntroSeen -> onAutoReadChange(true)
                    // 第一次开：先给一次说明，用户在弹窗里点「开启」才真正打开。
                    else -> {
                        readIntroFirstUse = true
                        showReadIntro = true
                    }
                }
            },
        )
        SettingRow(
            title = ReadCopy.RATE_TITLE,
            value = ReadCopy.rateLabel(settings.readRateX100),
            description = ReadCopy.RATE_DESCRIPTION,
            onClick = { onReadRateChange(ReadCopy.nextRate(settings.readRateX100)) },
        )
        SettingRow(
            title = ReadCopy.VOICE_TITLE,
            value = ReadCopy.voiceValue(settings.readVoiceId, readState.voices),
            description = if (readState.voices.isEmpty()) {
                ReadCopy.VOICE_DESCRIPTION_EMPTY
            } else {
                ReadCopy.VOICE_DESCRIPTION
            },
            // 引擎没给出可选音色时不可点：不要做成“点了没反应”的空按钮
            // （也不置灰——禁用态 TalkBack 会跳过，改用行下说明表达）。
            onClick = if (readState.voices.isEmpty()) {
                null
            } else {
                { onReadVoiceChange(ReadCopy.nextVoiceId(settings.readVoiceId, readState.voices)) }
            },
        )
        SettingRow(
            title = ReadCopy.METHOD_TITLE,
            value = ReadCopy.METHOD_VALUE,
            description = ReadCopy.METHOD_DESCRIPTION,
            // 纯说明行：不可点（点进连接页会让用户误以为「声音」跟聊天服务商有关）。
            onClick = null,
        )
        SettingRow(
            title = ReadCopy.INTRO_ENTRY_TITLE,
            value = ReadCopy.INTRO_ENTRY_VALUE,
            onClick = {
                readIntroFirstUse = false
                showReadIntro = true
            },
        )
        // 初始化失败 / 没有中文语音数据：说清事实 + 出路（验收 4）。
        // 不用 error 色：这不是用户做错了什么，也不是聊天失败。
        if (readState.status == ReaderStatus.UNAVAILABLE) {
            Text(
                text = ReadCopy.UNAVAILABLE,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 8.dp),
            )
            // 把文案里的「去系统设置装语音包」做成真入口。
            // 系统/朗读引擎没有这个界面时不显示（不造不存在的入口）；返回 App 时
            // AppRoot 的 ON_START 会对 UNAVAILABLE 重试一次初始化，装完不必重进页面。
            if (ttsInstallDataIntent != null) {
                SettingRow(
                    title = READ_INSTALL_DATA_ENTRY,
                    value = "",
                    description = READ_INSTALL_DATA_DESCRIPTION,
                    onClick = {
                        // 安装数据由系统/引擎自己的界面负责，结果不可预期（官方文档原文：
                        // 「the application shouldn't expect successful installation upon
                        // return from that intent」）；打不开时静默降级，不弹错误。
                        runCatching { context.startActivity(ttsInstallDataIntent) }
                    },
                )
            }
        }

        GroupTitle("连接")
        // 聊天行与「找旧事」行都允许先保存地址和模型、之后再补密钥。
        // 地址已保存时状态行应如实显示，不能再写「还没配置」。
        // 三态判定/取值复用 [CredentialRowCopy]（与找旧事行同一个纯函数），逐态单测锁死。
        val chatRowStatus = CredentialRowCopy.status(
            hasKey = settings.hasChatKey,
            baseUrl = settings.chatBaseUrl,
        )
        SettingRow(
            title = "聊天",
            value = CredentialRowCopy.value(
                status = chatRowStatus,
                model = settings.chatModel,
                notConfigured = CredentialRowCopy.CHAT_VALUE_NOT_CONFIGURED,
            ),
            description = "填上你自己的 AI 服务地址和密钥后才能开始聊；不填聊不了。",
            onClick = onOpenConnection,
        )
        // 思考 / 搜索是 DeepSeek 私有能力：其它服务商不显示胶囊，这里说明一句原因，
        // 避免用户以为开关丢了。
        // 拆分后这句紧跟在「聊天」行后面：它是聊天能力说明，不能贴到「找旧事」行下面
        // （用户指令 2026-11-21：两套服务不能混在一起）。
        if (!settings.isDeepSeekProvider()) {
            Text(
                // 同一句话收口到聊天页常量，测试锁一份即可。
                text = ChatConfigCopy.DEEPSEEK_ONLY_HINT,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 8.dp),
            )
            // v0.5 识图：非 DeepSeek 端点入口隐藏，这里给一句原因（不要假开关）。
            Text(
                text = ImageCopy.OTHER_PROVIDER,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 8.dp),
            )
            // v0.5.1 语音输入：同样只支持 DeepSeek 端点，与识图同样式展示原因。
            Text(
                text = VoiceCopy.OTHER_PROVIDER,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 8.dp),
            )
        } else {
            // 首用说明的常驻回看入口（验收 5「可回看」）：
            // 真实入口，不跳页、不改状态，只把同一份逐字说明再显示一次。
            SettingRow(
                title = ImageCopy.INTRO_ENTRY_TITLE,
                value = ImageCopy.INTRO_ENTRY_VALUE,
                onClick = { showImageIntro = true },
            )
        }
        // 「找旧事」行单独进自己的设置页（用户指令 2026-11-21：不再复用聊天页的展开区）。
        // （v0.5.3）：允许「先存地址+模型、密钥后补」（` 方案丙），
        // 但状态行必须如实——保存成功退出后不能再显示「没配」（QC 复现的正是这个自相矛盾）。
        // 三态判定复用聊天行同一函数 [CredentialRowCopy.status]（两行口径一致），逐态单测锁死
        // （`ConfigEmbeddingTest`）；「没配」句子与行下说明仍由 [FindOldThingsCopy] 自己的文案常量决定。
        val findOldThingsStatus = CredentialRowCopy.status(
            hasKey = settings.hasEmbedKey,
            baseUrl = settings.embedBaseUrl,
        )
        SettingRow(
            // 名称+说明要能回答三问：这是什么 / 要不要用 / 不用会怎样（-11-20 修订）。
            // 用户可见文案不出现"记忆检索/关键词/向量"。
            title = FindOldThingsCopy.TITLE,
            value = FindOldThingsCopy.value(findOldThingsStatus, settings.embedModel),
            description = FindOldThingsCopy.description(findOldThingsStatus),
            onClick = onOpenFindOldThings,
        )

        GroupTitle("记忆")
        SettingRow(
            title = "TA 记得多清楚",
            value = WindowTierCopy.label(settings.windowTier),
            // 行下一句回答「要不要用 / 不用会怎样」：大的收益与大代价同句说清，不改也行。
            description = WindowTierCopy.DESCRIPTION,
            onClick = {
                // 四档循环：32k → 64k → 300k → 64k…（"极致"留在高级里手填）
                val next = when (settings.windowTier) {
                    Settings.TIER_32K -> Settings.TIER_64K
                    Settings.TIER_64K -> Settings.TIER_300K
                    else -> Settings.TIER_32K
                }
                onWindowTierChange(next)
            },
        )

        GroupTitle("花费")
        SettingRow(
            title = "用量与花费",
            value = "",
            description = "看看最近发了多少条消息、大概花了多少钱。",
            onClick = onOpenUsage,
        )
        SettingRow(
            title = "每天上限",
            value = limitLabel(settings.dailyLimitYuanX100),
            description = "到了每天设的金额就暂停发新消息；不填就不限制。",
            onClick = onOpenUsage,
        )
        SettingRow(
            title = "每月上限",
            value = limitLabel(settings.monthlyLimitYuanX100),
            description = "到了每月设的金额就暂停发新消息；不填就不限制。",
            onClick = onOpenUsage,
        )

        GroupTitle("关于")
        SettingRow(title = "服务协议 · 隐私说明", value = "", onClick = onOpenAgreement)
        // 导出不含图片本体、用量/花费或服务商地址和模型，因此标题使用「导出备份」，不承诺包含全部数据。
        // 说明文案集中在 `BackupCopy`，讲清备份是什么、是否需要使用以及不用时的影响。
        SettingRow(
            title = BackupCopy.SETTINGS_TITLE,
            value = "",
            description = BackupCopy.SETTINGS_DESCRIPTION,
            onClick = onExport,
        )
        SettingRow(title = "清空所有数据", value = "", onClick = onClearAll)

        Spacer(Modifier.height(24.dp))
        Text(
            // 版本号从构建配置读，避免每版手改文案（此前停在 0.1.1，落后于实际版本）。
            text = "PulseZ ${cc.pulsez.app.BuildConfig.VERSION_NAME} · 开源在 GitHub",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
        )
        Text(
            text = "这是一个实验项目。你填的密钥和产生的费用都是你自己的。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
        )
        Spacer(Modifier.height(32.dp))
    }

    // 创作模式首次说明（文案逐字）：
    // - 普通 AlertDialog，非阻断、不锁任何操作；不做"确认不会伤害自己"那类承诺；
    // - 只出现一次：开启 / 先不用 / 点外面 / 返回关闭，四种走法都会把「已看过」落盘；
    // - 按钮触控 ≥48dp。
    if (showCreativeIntro) {
        CreativeModeIntroDialog(
            onConfirm = {
                showCreativeIntro = false
                // 顺序：先开模式、再记"说明已看过"（见 [onCreativeModeChange] 的约定）。
                onCreativeModeChange(true)
                onCreativeModeIntroSeen()
            },
            onDismiss = {
                showCreativeIntro = false
                onCreativeModeIntroSeen()
            },
        )
    }

    // 自动朗读首用说明（v0.5 **逐字**）：
    // - 只在「第一次把自动朗读从关打开」时弹；「先不用」/点外面/返回关闭都记已看过（只提示一次）；
    // - 从「朗读说明 · 再看一遍」打开时只读内容，不重复触发开关（按钮只有「知道了」）；
    // - 非阻断：不选「开启」也能继续用 App，聊天照常。
    if (showReadIntro) {
        AlertDialog(
            onDismissRequest = {
                showReadIntro = false
                if (readIntroFirstUse) onReadIntroSeen()
            },
            title = { Text(ReadCopy.DIALOG_TITLE) },
            text = {
                Column {
                    ReadCopy.INTRO_LINES.forEachIndexed { index, line ->
                        if (index > 0) Spacer(Modifier.height(8.dp))
                        Text(
                            text = line,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
            },
            confirmButton = {
                if (readIntroFirstUse) {
                    TextButton(
                        onClick = {
                            showReadIntro = false
                            // 顺序：先开开关、再记"说明已看过"（同创作模式的约定，避免只记了看过没开）。
                            onAutoReadChange(true)
                            onReadIntroSeen()
                        },
                        modifier = Modifier.heightIn(min = 48.dp),
                    ) {
                        Text(ReadCopy.CONFIRM)
                    }
                } else {
                    TextButton(
                        onClick = { showReadIntro = false },
                        modifier = Modifier.heightIn(min = 48.dp),
                    ) {
                        Text(ReadCopy.CLOSE)
                    }
                }
            },
            dismissButton = {
                if (readIntroFirstUse) {
                    TextButton(
                        onClick = {
                            showReadIntro = false
                            onReadIntroSeen()
                        },
                        modifier = Modifier.heightIn(min = 48.dp),
                    ) {
                        Text(ReadCopy.DISMISS)
                    }
                }
            },
        )
    }

    // 发图片说明回看（v0.5 逐字）：只读内容，不改任何设置；
    // 按钮只有「知道了」（首用弹窗在聊天页、"知道了 / 以后再说"，这里不重复触发开关）。
    if (showImageIntro) {
        AlertDialog(
            onDismissRequest = { showImageIntro = false },
            title = { Text(ImageCopy.DIALOG_TITLE) },
            text = {
                Column {
                    ImageCopy.INTRO_LINES.forEachIndexed { index, line ->
                        if (index > 0) Spacer(Modifier.height(8.dp))
                        Text(
                            text = line,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = { showImageIntro = false },
                    modifier = Modifier.heightIn(min = 48.dp),
                ) {
                    Text(ImageCopy.CLOSE)
                }
            },
        )
    }

    // 「有新版本」非阻断对话框（v0.5.1）：
    // - 标题/正文/按钮逐字在 `UpdateCopy`；正文回答三问（是什么 / 要不要用 / 不用会怎样）；
    // - 「稍后」记忽略版本（同版本不再自动提示），「去下载」只跳浏览器（本轮不做 App 内安装）；
    // - 点外面/返回 = 只关对话框，不记「稍后」（最后一行）。
    updateDialogManifest?.let { manifest ->
        UpdateAvailableDialog(
            manifest = manifest,
            installedVersionName = cc.pulsez.app.BuildConfig.VERSION_NAME,
            // 失败态做进同一对话框正文（不叠 Dialog）：打不开浏览器时显示地址 + 复制按钮。
            onDownload = { updateDownloadFailed = !onDownloadUpdate(manifest.apkUrl) },
            onCopyUrl = { copyDownloadUrl(context, manifest.apkUrl) },
            downloadFailed = updateDownloadFailed,
            onLater = {
                updateDialogManifest = null
                updateDownloadFailed = false
                onIgnoreUpdate(manifest)
            },
            onDismiss = {
                updateDialogManifest = null
                updateDownloadFailed = false
            },
        )
    }
}

/**
 * 设置页最上方的「检查更新」行（v0.5.1）。
 *
 * - 整行 ≥48dp 可点（可在行内任意位置点，不要求瞄准标题）；
 * - 右侧值始终先显示当前版本（由调用方从 `BuildConfig.VERSION_NAME` 传入，不手写版本号），
 *   有新版时同一行显示「0.5.0 · 有新版本 0.5.1」+ 8dp 圆点（无数字，不制造待办压力）；
 * - 检查中不可点（`UpdateCopy.rowClickable`），其它状态可点（重查 / 打开对话框）；
 * - 状态变化用 `liveRegion` 播报一次（无障碍）；圆点本身不做焦点。
 */
@Composable
private fun UpdateRow(
    state: UpdateUiState,
    installedVersionName: String,
    onClick: () -> Unit,
) {
    val rowModifier = if (UpdateCopy.rowClickable(state)) {
        Modifier.clickable(role = Role.Button, onClick = onClick)
    } else {
        Modifier
    }
    Column(modifier = rowModifier) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = UpdateCopy.ROW_TITLE,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = UpdateCopy.rowValue(state, installedVersionName),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                // 检查完成时读屏播报一次「已是最新 / 有新版本 X」。
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
            if (UpdateCopy.rowHasBadge(state)) {
                Spacer(Modifier.width(6.dp))
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary),
                )
            }
        }
        Text(
            text = UpdateCopy.rowDescription(state, installedVersionName),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 12.dp),
        )
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    }
}

/**
 * 「有新版本」非阻断对话框（设置页与 AppRoot 启动提示共用；v0.5.1）。
 *
 * 文案全部来自 `UpdateCopy`（逐字 + 禁用词由 `UpdateCopyTest` 锁）；这里只负责布局与按钮动作：
 * - 主按钮「去下载」：[onDownload] 由调用方用浏览器打开下载页（不做 App 内安装）；
 * - 次按钮「稍后」：[onLater] 记忽略版本并关窗；点外面/返回只关窗（[onDismiss]），不算「稍后」；
 * - 浏览器打不开（[downloadFailed] = true，v0.5.2）：正文追加失败说明 + **清单里
 *   apkUrl 原文** + 「复制地址」按钮（48dp），给用户真能执行的下一步；主按钮仍是「去下载」（重试）。

 * - 按钮高 ≥48dp；对话框不阻断任何其它操作，返回手势先关对话框。
 */
@Composable
internal fun UpdateAvailableDialog(
    manifest: VersionManifest,
    installedVersionName: String,
    onDownload: () -> Unit,
    onLater: () -> Unit,
    onDismiss: () -> Unit,
    /** 浏览器打不开时为 true：正文追加地址与复制按钮（默认 false = 现状）。 */
    downloadFailed: Boolean = false,
    /** 点「复制地址」。 */
    onCopyUrl: () -> Unit = {},
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(UpdateCopy.dialogTitle(manifest.versionName)) },
        text = {
            Column {
                UpdateCopy.dialogLines(installedVersionName, manifest.changelog)
                    .forEachIndexed { index, line ->
                        if (index > 0) Spacer(Modifier.height(8.dp))
                        Text(
                            text = line,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                // 失败出路：不再只给 Toast——地址就显示在这里，旁边一键复制。
                if (downloadFailed) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = UpdateCopy.BROWSER_UNAVAILABLE,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Spacer(Modifier.height(4.dp))
                    // 地址原文展示，用户可自己长按选中；旁边给一键复制。
                    Text(
                        text = manifest.apkUrl,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    TextButton(
                        onClick = onCopyUrl,
                        modifier = Modifier.heightIn(min = 48.dp), // 触控 ≥48dp
                    ) {
                        Text(UpdateCopy.COPY_URL)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = onDownload,
                modifier = Modifier.heightIn(min = 48.dp),
            ) {
                Text(UpdateCopy.DOWNLOAD)
            }
        },
        dismissButton = {
            TextButton(
                onClick = onLater,
                modifier = Modifier.heightIn(min = 48.dp),
            ) {
                Text(UpdateCopy.LATER)
            }
        },
    )
}

/**
 * 把下载地址写进系统剪贴板并给一句确认；空地址不写、返回 false。
 *
 * 依据 「复制地址」。
 * 不新增权限：ClipboardManager 是系统服务，零权限。
 * 标签也走 `UpdateCopy`：剪贴板标签在 Android 13+ 的系统浮层里对用户可见（§5-6/§10 #20）。
 */
internal fun copyDownloadUrl(context: Context, url: String): Boolean {
    if (url.isBlank()) return false
    val clipboard = context.getSystemService(ClipboardManager::class.java) ?: return false
    clipboard.setPrimaryClip(ClipData.newPlainText(UpdateCopy.CLIP_LABEL, url))
    Toast.makeText(context, UpdateCopy.URL_COPIED, Toast.LENGTH_SHORT).show()
    return true
}

@Composable
private fun GroupTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 20.dp, top = 20.dp, bottom = 8.dp),
    )
}

@Composable
private fun SettingRow(
    title: String,
    value: String,
    description: String? = null,
    onClick: (() -> Unit)? = null,
) {
    // onClick = null 的纯说明行不加 clickable：可聚焦却点不动的“空按钮”是已知无障碍缺陷
    // 。只有真正能进入下一页/切换值的行才传 onClick。
    val rowModifier = if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier
    Column(modifier = rowModifier) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.weight(1f),
            )
            if (value.isNotBlank()) {
                Text(
                    text = value,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (description != null) {
            // 说明放在行下方、仍在整行可点范围里：点它也能进连接页，不用瞄准标题。
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 12.dp),
            )
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    }
}

/**
 * 凭证设置行的三种状态（v0.5.3聊天行与「找旧事」行**共用**）。
 *
 * 为什么不是布尔「配了/没配」：允许**先存地址和模型、密钥后补**；
 * `KeySaveDecision.NoKeyYet` 只写地址和模型并返回成功。
 * 旧实现只看 `hasKey && 地址非空`，于是「保存成功退出 → 设置行仍写没配 / 还没配置」，与刚发生的
 * 保存自相矛盾。聊天行和「找旧事」行使用同一判断口径。
 *
 * 三态如实：
 * - [CONFIGURED]：地址已存 + 密钥可用（密钥标记在、地址非空）→ 显示模型名；
 * - [ENDPOINT_ONLY]：地址（+模型）已存，但密钥还没填/不可用 → 如实说「地址已存，还没填密钥」；
 * - [NOT_CONFIGURED]：地址也没存 → 各自行的「没配」句子（见 [CredentialRowCopy.value] 的入参）。
 *
 * `CredentialSaveWorkflow.writeEndpointOnly` 在 `NoKeyYet` 时只保存地址和模型。
 */
internal enum class CredentialRowStatus {
    /** 地址 + 模型已存，密钥可用：显示模型名（空则兜底「已配置」）。 */
    CONFIGURED,

    /** 地址 + 模型已存，但还没有可用的密钥：如实说明「还没填密钥」。 */
    ENDPOINT_ONLY,

    /** 地址都没存：还没开始配。 */
    NOT_CONFIGURED,
}

/**
 * 聊天行与「找旧事」行共用的三态判定与取值（v0.5.3两行同口径）。
 *
 * 为什么收口成一个入口：两行的「已配置」与「地址已存，还没填密钥」必须逐字一致，否则两页口径
 * 会再次分叉（下一轮还会抓）；只有「未配置」的句子按行保留——聊天是「还没配置」，
 * 找旧事是「没配（也能用）」（后者额外强调「也能用」，是该行三问的一部分，逐字已由测试锁定）。
 *
 * 纯函数 + 常量：不读 Compose / DataStore，JVM 单测可直接断言（`ConfigEmbeddingTest`）。
 */
internal object CredentialRowCopy {
    /** 地址+模型已存、密钥还没填（两行共用同一句，v0.5.3）。*/
    const val VALUE_ENDPOINT_ONLY = "地址已存，还没填密钥"

    /** 已配置但模型名为空时的兜底（两行共用，旧行为不变）。 */
    const val VALUE_CONFIGURED_FALLBACK = "已配置"

    /** 聊天行未配置值（找旧事行有自己更完整的一句，见 [FindOldThingsCopy.VALUE_NOT_CONFIGURED]）。 */
    const val CHAT_VALUE_NOT_CONFIGURED = "还没配置"

    /**
     * 三态判定（纯函数，JVM 单测直接断言；不读 Compose / DataStore）。
     *
     * 判定顺序就是「地址 → 密钥」：地址没存 = 什么都用不上（[CredentialRowStatus.NOT_CONFIGURED]）；
     * 地址存了但密钥标记不在 = 用户/升级流程留下的「地址已存，密钥后补」态
     * （[CredentialRowStatus.ENDPOINT_ONLY]）。
     * `hasKey=true` + 地址空不归只存地址态：没有地址就没有可用服务（ChatEngine 对空地址零出网），
     * 且保存路径走不出这个形状，保持旧行为归未配置。
     */
    fun status(hasKey: Boolean, baseUrl: String): CredentialRowStatus = when {
        baseUrl.isBlank() -> CredentialRowStatus.NOT_CONFIGURED
        hasKey -> CredentialRowStatus.CONFIGURED
        else -> CredentialRowStatus.ENDPOINT_ONLY
    }

    /**
     * 设置行右侧值：按状态取文案（已配置时显示模型名，空则兜底「已配置」）。
     *
     * @param notConfigured 未配置时该行**自己的**句子：两行三态逻辑共用，措辞按行保留。
     */
    fun value(status: CredentialRowStatus, model: String, notConfigured: String): String = when (status) {
        CredentialRowStatus.CONFIGURED -> model.ifBlank { VALUE_CONFIGURED_FALLBACK }
        CredentialRowStatus.ENDPOINT_ONLY -> VALUE_ENDPOINT_ONLY
        CredentialRowStatus.NOT_CONFIGURED -> notConfigured
    }
}

/**
 * 「让 TA 更会找旧事」设置行文案（v0.4.1 WP-find-old-things；v0.5.3 加三态）。
 *
 * 三问对应（-11-20 修订）：
 * - 这是什么：另配一个服务，TA 会按意思找到以前的聊天；
 * - 要不要用：可选；
 * - 不用会怎样：不配也能用，只是找得没那么准。
 *
 * 三态判定/取值复用 [CredentialRowCopy]（与聊天行同一纯函数）；本对象只保留**找旧事特有的**
 * 标题、未配置句子与行下说明。
 *
 * 禁用词：不出现 token / 上下文 / 窗口 / 向量 / embedding / 记忆检索 / 关键词；
 * 角色代词只用 TA（不用第三人称的性别代词）；不写"没做/暂不支持"——找旧事是正式能力，配不配由用户选
 * 。
 *
 * 文案收口成常量的原因同 [CreativeModeCopy]：纯 JVM 逐字断言 + 禁用词回归（`ConfigEmbeddingTest`）。
 */
internal object FindOldThingsCopy {
    /** 设置行标题：说清"它让你更会找旧事"，不是一个看不懂的名词。 */
    const val TITLE = "让 TA 更会找旧事"

    /** 未配置时的右侧灰字：短、不吓人，且明确"也能用"。 */
    const val VALUE_NOT_CONFIGURED = "没配（也能用）"

    /** 行下方说明：这是什么 / 可选 / 不用会怎样，一句说完。 */
    const val DESCRIPTION = "可选：另配一个服务，TA 会按意思找到以前的聊天；不配也能用，只是找得没那么准。"

    /**
     * 地址已存、密钥还没填时的行下方说明（v0.5.3）。
     *
     * 只说当前事实与后果、不吓人：密钥没填之前 TA 不会去按意思找，但按原话找照旧可用
     * （「按原话找」沿用 [cc.pulsez.app.ui.config.EmbedConfigCopy.OPTIONAL_NOTE] 的既有用户口径）。
     */
    const val DESCRIPTION_ADDRESS_ONLY = "还没填密钥：填上之前，TA 还不能按意思找旧事，仍能按原话找。"

    /** 设置行右侧灰字：复用共享三态取值，未配置句子用本行更完整的「没配（也能用）」。 */
    fun value(status: CredentialRowStatus, embedModel: String): String =
        CredentialRowCopy.value(status, embedModel, VALUE_NOT_CONFIGURED)

    /** 设置行下方说明：只有「地址已存、密钥还没填」需要换一句如实解释。 */
    fun description(status: CredentialRowStatus): String = when (status) {
        CredentialRowStatus.ENDPOINT_ONLY -> DESCRIPTION_ADDRESS_ONLY
        CredentialRowStatus.CONFIGURED,
        CredentialRowStatus.NOT_CONFIGURED,
        -> DESCRIPTION
    }
}

/**
 * 「创作模式」开关一行（v0.4）。
 *
 * - 整行 ≥48dp 可点、语义 `Role.Switch`；右侧 [Switch] 只做视觉（`onCheckedChange = null`），
 *   点击由整行承担，避免"点字没反应、只有点小开关才生效"。
 * - 视觉用 Material 3 默认开关、开启态走 `primary`（= signal），不加新样式/动效。
 * - 说明文案逐字对齐 不出现 token / 上下文等禁用词。
 */
@Composable
private fun CreativeModeRow(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .toggleable(
                value = checked,
                role = Role.Switch,
                onValueChange = onCheckedChange,
            )
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = CreativeModeCopy.SETTING_TITLE,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Text(
                text = CreativeModeCopy.SETTING_DESCRIPTION,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = null)
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
}

/**
 * 「自动朗读 TA 的回复」开关一行（v0.5）。
 *
 * 与 [CreativeModeRow] 保持同一套视觉/语义：
 * - 整行 ≥48dp 可点、语义 `Role.Switch`；右侧 [Switch] 只做视觉（`onCheckedChange = null`）；
 * - 标题 + 一行说明回答「是什么 / 不用会怎样」；
 * - 默认关：开的时候要过首用说明（见 `SettingsScreen` 里的 `showReadIntro`）。
 */
@Composable
private fun ReadToggleRow(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .toggleable(
                value = checked,
                role = Role.Switch,
                onValueChange = onCheckedChange,
            )
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = ReadCopy.SETTING_TITLE,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Text(
                text = ReadCopy.SETTING_DESCRIPTION,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = null)
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
}

/**
 * 「思考强度」三档单选。
 *
 * - 折叠态：一行标题 + 当前值 + 展开箭头；行下一句「聊天建议用低」。
 * - 展开态：**就地展开 3 个 radio**，每项一行说明；不用 SegmentedButton。
 * - 总开关关着时**不置灰**：禁用态不能被聚焦、读屏会跳过，用户既不知道那里有东西、
 *   也不知道为什么点不了（「禁用态」、§5.2）。改用一行常驻说明表达从属关系。
 * - 组底部说明用 `caption` + `inkMuted`（colorScheme.onSurfaceVariant），**不用红/黄**
 *   （禁彩色文字强调、§2 限制 signal 面积）。
 * - 文案逐字对齐 不出现 token。
 */
@Composable
private fun ReasoningEffortSection(
    thinkingEnabled: Boolean,
    selectedEffort: String,
    onSelect: (String) -> Unit,
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .clickable(role = Role.Button) { expanded = !expanded }
                .padding(horizontal = 20.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "思考强度",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = effortLabel(selectedEffort),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Icon(
                imageVector = if (expanded) {
                    Icons.Filled.KeyboardArrowUp
                } else {
                    Icons.Filled.KeyboardArrowDown
                },
                contentDescription = if (expanded) "收起思考强度" else "展开思考强度",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .padding(start = 4.dp)
                    .size(24.dp),
            )
        }
        Text(
            text = "只在聊天打开「深度思考」时生效，平时用低就好",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 8.dp),
        )
        AnimatedVisibility(
            visible = expanded,
            enter = fadeIn(tween(SECTION_ANIM_MILLIS, easing = SectionEasing)) +
                expandVertically(tween(SECTION_ANIM_MILLIS, easing = SectionEasing)),
            exit = fadeOut(tween(SECTION_ANIM_MILLIS, easing = SectionEasing)) +
                shrinkVertically(tween(SECTION_ANIM_MILLIS, easing = SectionEasing)),
        ) {
            Column {
                Settings.REASONING_EFFORTS.forEach { effort ->
                    EffortOptionRow(
                        effort = effort,
                        selected = selectedEffort == effort,
                        onClick = { onSelect(effort) },
                    )
                }
                Text(
                    text = "高和极高会让 TA 多想几步，更费钱也更慢，聊复杂剧情或理清长故事时再用。平时聊天用低就够了。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 4.dp),
                )
                if (!thinkingEnabled) {
                    // 不置灰，用一行常驻说明表达从属关系。
                    Text(
                        text = "只在打开「深度思考」时生效。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 4.dp),
                    )
                }
                Spacer(Modifier.height(12.dp))
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    }
}

/**
 * 一个强度选项：radio + 标题 + 一行说明，整行 ≥48dp 可点。
 *
 * 选中态只靠 radio 圆点表达、文字不换色「状态不靠颜色表达」、
 * 「不用彩色文字强调」。
 */
@Composable
private fun EffortOptionRow(effort: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .selectable(
                selected = selected,
                role = Role.RadioButton,
                onClick = onClick,
            )
            .padding(start = 12.dp, end = 20.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(
            selected = selected,
            onClick = null,
            modifier = Modifier.size(24.dp),
            colors = RadioButtonDefaults.colors(
                selectedColor = MaterialTheme.colorScheme.primary,
                unselectedColor = MaterialTheme.colorScheme.onSurfaceVariant,
            ),
        )
        Spacer(Modifier.width(8.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = effortLabel(effort),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Text(
                text = effortDescription(effort),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** 展开/收起时长与曲线（200ms，同一条曲线，不做弹跳）。*/
private val SectionEasing = CubicBezierEasing(0.2f, 0.8f, 0.2f, 1f)
private const val SECTION_ANIM_MILLIS = 200

/** 三档说明，逐字对齐 （每项一行 ≤18 字左右，费钱/慢的完整解释只在组底部说一次）。*/
private fun effortDescription(effort: String): String = when (effort) {
    Settings.EFFORT_HIGH -> "想得更细，慢一点，也多花一点钱。"
    Settings.EFFORT_MAX -> "最细，也最慢最贵。"
    else -> "回得快，也省。平时聊天用这个。"
}

private fun effortLabel(effort: String): String = when (effort) {
    Settings.EFFORT_HIGH -> "高"
    Settings.EFFORT_MAX -> "极高"
    else -> "低"
}

/**
 * 「TA 记得多清楚」值文案与行下说明（v0.4.1 文案修复，` 三·#9）。
 *
 * 第八条三问：标题问「记多清楚」，值必须答「记多少」，不能答成本词（原「最省 / 省一点」）。
 * 四档用 ` 的档位名；行下说明补上「大的代价」与「不改也行」：
 * 档位越大，每轮带给 TA 的旧内容越多 —— 越记得住最近的细节，也越贵。
 *
 * 收口成常量的原因同 [CreativeModeCopy]：纯 JVM 逐字断言（`WarmCopyTest`）。
 */
internal object WindowTierCopy {
    /** 32k：最省，记性一般。*/
    const val TIER_SAVE_MORE = "省得更多"

    /** 64k：聊得快，也省。 */
    const val TIER_SAVE = "省着用"

    /** 300k：默认，记得住最近几个月的细节。 */
    const val TIER_DEFAULT = "默认"

    /**
     * 手填上限（` 的「极致」档）。
     *
     * 设置行只循环 32k/64k/300k 三个标准值（手填入口在高级里，v0.4 未实现），
     * 因此这里只对「已有非标准值」显示；**不为了凑四档去改档位逻辑**。
     */
    const val TIER_MAX = "极致"

    /** 行下说明：说清代价 + 「不改也行」（不用也不影响聊天，只是记得少一点）。 */
    const val DESCRIPTION = "点一下可切换「省得更多 / 省着用 / 默认」：档位越大，TA 越记得住最近的细节，每句也越贵。不改也行。"

    /** 档位数值 → 值文案（未知值按手填的「极致」档显示，不显示内部数字）。 */
    fun label(tier: Int): String = when (tier) {
        Settings.TIER_32K -> TIER_SAVE_MORE
        Settings.TIER_64K -> TIER_SAVE
        Settings.TIER_300K -> TIER_DEFAULT
        else -> TIER_MAX
    }
}

private fun limitLabel(yuanX100: Int): String =
    if (yuanX100 <= 0) "不限制" else "¥${yuanX100 / 100}"

/**
 * 创作模式用户可见文案（v0.4 **逐字**对齐）。
 *
 * 收口成常量有两个原因：
 * 1. 文案是产品/合规定稿，改动必须显式（`CreativeModeCopyTest` 逐字断言，防手滑改字）；
 * 2. 禁用词与角色代词 TA需要能被纯 JVM 测试覆盖——
 *    Compose 页面里的字面量没法反射收集，只能靠常量入口。
 *
 * 边界：这里**不写**任何"承诺不伤害自己""检测到深度沉浸"，
 * 说明页也**不出现热线号码**（号码只在真的出现风险信号时的危机兜底里，见 `CrisisDetector`）。
 */
internal object CreativeModeCopy {
    /** 设置页「聊天」组里的开关标题。 */
    const val SETTING_TITLE = "创作模式"

    /** 开关下一行说明。*/
    const val SETTING_DESCRIPTION = "角色扮演、强冲突剧情或写长故事时打开：回复更长更入戏；平时闲聊不用开。"

    /** 首次说明弹窗标题。 */
    const val DIALOG_TITLE = "创作模式"

    /** 首次说明第 1 句：这是什么。 */
    const val INTRO_LINE_1 = "写长故事或强冲突角色扮演时，TA 会一直在戏里接住你。"

    /** 首次说明第 2 句：唯一会打断的情况（说人话，不给号码）。 */
    const val INTRO_LINE_2 = "只有你说要伤害自己时，这里会先停下来，给几个能马上找到人的方式。"

    /** 首次说明第 3 句：怎么关。 */
    const val INTRO_LINE_3 = "平时闲聊不用开。随时能从聊天页右上角点「出戏」关掉。"

    /** 弹窗正文三行，顺序即展示顺序（测试断言恰好三行）。 */
    val INTRO_LINES: List<String> = listOf(INTRO_LINE_1, INTRO_LINE_2, INTRO_LINE_3)

    /** 主按钮：真正打开创作模式。 */
    const val CONFIRM = "开启"

    /** 次按钮：不开启，但也不再提示（只提示一次）。 */
    const val DISMISS = "先不用"
}

// ---------------------------------------------------------------- 朗读「去装语音包」入口

/**
 * 「声音」组里 UNAVAILABLE 时的可点入口标题（修）。
 *
 * 不放进 `ReadCopy` 的写作用域只允许动本文件的 入口，`ui/copy/ReadCopy.kt` 文案
 * 不在允许范围内；这里两句新文案只服务于本入口。
 */
private const val READ_INSTALL_DATA_ENTRY = "去装语音包"

/** 入口行下说明：装完回来会自动重试（由 AppRoot 的 ON_START 对 UNAVAILABLE 重试兑现）。 */
private const val READ_INSTALL_DATA_DESCRIPTION = "装完回到这里，会自动再试一次。"

/**
 * 解析「装朗读数据」的系统界面（修）。
 *
 * 依据：`TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA` 是官方 Activity Action，
 * 「Triggers the platform TextToSpeech engine to start the activity that installs the
 * resource files on the device that are required for TTS to be operational」；
 * AOSP 常量值 `"android.speech.tts.engine.INSTALL_TTS_DATA"`：
 * https://developer.android.com/reference/android/speech/tts/TextToSpeech.Engine#ACTION_INSTALL_TTS_DATA
 * https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/java/android/speech/tts/TextToSpeech.java
 *
 * **先解析再显示**：没有系统/引擎界面处理这个 action 的机型不显示这一行，避免“点了没反应”的
 * 假入口（「不造不存在入口」）。
 * 例外：`resolveActivity(Intent, Int)` 在 API 33 起 deprecated，但 minSdk 31 需要这个重载
 * （新签名 `ResolveInfoFlags` 是 API 33+），这里显式 suppress。
 */
@Suppress("DEPRECATION")
private fun resolveTtsInstallDataIntent(context: Context): Intent? = runCatching {
    Intent(TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA)
        .takeIf { intent -> context.packageManager.resolveActivity(intent, 0) != null }
}.getOrNull()

/**
 * 创作模式首次说明弹窗（v0.4 抽出复用，供 SettingsScreen 与 ChatScreen 共用）。
 *
 * 沿用 [CreativeModeCopy] 原样逐字文案与 `>= 48.dp` 触控目标；
 * 依据：app/src/main/java/cc/pulsez/app/ui/settings/SettingsScreen.kt:460
 */
@Composable
internal fun CreativeModeIntroDialog(
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(CreativeModeCopy.DIALOG_TITLE) },
        text = {
            Column {
                CreativeModeCopy.INTRO_LINES.forEachIndexed { index, line ->
                    if (index > 0) Spacer(Modifier.height(8.dp))
                    Text(
                        text = line,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                modifier = Modifier.heightIn(min = 48.dp),
            ) {
                Text(CreativeModeCopy.CONFIRM)
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.heightIn(min = 48.dp),
            ) {
                Text(CreativeModeCopy.DISMISS)
            }
        },
    )
}
