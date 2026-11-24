package cc.pulsez.app.ui.chat

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import cc.pulsez.app.data.attachments.AttachmentStore
import cc.pulsez.app.media.DraftImage
import cc.pulsez.app.media.DraftStatus
import cc.pulsez.app.media.ImageMath
import cc.pulsez.app.media.ThumbnailCache
import cc.pulsez.app.ui.copy.ImageCopy
import cc.pulsez.app.ui.copy.ReadCopy
import cc.pulsez.app.ui.copy.VoiceCopy
import cc.pulsez.app.ui.theme.DarkSurfaceHigh
import cc.pulsez.app.ui.theme.LightSurfaceHigh
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 聊天页与群聊页共用的视觉件。
 *
 * 抽出来的原因 要求群聊输入条"复用现有视觉"，
 * 不允许为了群聊复制一份 1:1 聊天页的输入条 / 三点 / 气泡（复制会立刻走样）。
 * 这里只是搬家，1:1 聊天页的视觉与行为保持不变（见 ChatScreen）。
 *
 * v0.5.0 识图：
 * - 用户气泡可带本地缩略图（手写 LRU 缓存，不引图片库）；
 * - 草稿行只在有图时出现，每张 48dp 删除、准备中/失败态可读；
 * - 选图面板沿用 `GroupPickerSheet` 的 `ModalBottomSheet` 先例（返回=收弹层）；
 * - 大图预览是 `Dialog`（返回先关预览，不退聊天页），带「再用这张图问一次」。
 *
 * 输入条还原（对齐官方 App 2.9.0；`）：
 * - 输入条改成官方同款**两行**圆角容器：第一行文本；第二行左胶囊、右下角 [＋][主按钮]；
 * - 「＋」不再放在输入条外左侧（v0.5.0 的位置与官方相反，作废 ` 的左侧建议）；
 * - 主按钮空输入 = 禁用箭头（不放假语音按钮），有字 = 实心圆 + 向上箭头，生成中 = 停止方块。
 */

// ---------------------------------------------------------------- 用户消息 / 图片

internal const val CHAT_COMPOSER_INPUT_TEST_TAG = "chat-composer-input"

/**
 * 用户消息：右对齐深色胶囊 + 可选本地图片缩略图。
 *
 * 颜色直接用 token 表：
 * - `userBubble` = ink（浅色 #0A0A0C / 深色 #F2F2F3）→ `colorScheme.onBackground`；
 * - `onUserBubble` = 页面底色（浅色 #FFFFFF / 深色 #08080A）→ `colorScheme.background`。
 * 不用 `primary`：那是强调色 明确 `signal` 只给"可点的动作和正在发生的事"。
 *
 * 纯图消息（正文是「[图片]」占位）不显示文字气泡，避免空气泡/重复。
 */
@Composable
fun UserMessageBubble(
    text: String,
    modifier: Modifier = Modifier,
    imagePaths: List<String> = emptyList(),
    onOpenImage: (String) -> Unit = {},
    imageMissingLabel: String = ImageCopy.IMAGE_MISSING,
) {
    Row(modifier = modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        Column(
            horizontalAlignment = Alignment.End,
            verticalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.widthIn(max = 300.dp),
        ) {
            imagePaths.forEach { localPath ->
                MessageImageThumbnail(
                    localPath = localPath,
                    onOpen = { onOpenImage(localPath) },
                    missingLabel = imageMissingLabel,
                )
            }
            // 「[图片]」是落库占位（导出/降级用），有缩略图时不再显示它，避免空气泡。
            val visibleText = text.takeIf { it.isNotBlank() && it != ImageCopy.IMAGE_PLACEHOLDER }
            if (visibleText != null) {
                Text(
                    text = visibleText,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.background,
                    modifier = Modifier
                        .clip(
                            RoundedCornerShape(
                                topStart = 20.dp,
                                topEnd = 20.dp,
                                bottomStart = 20.dp,
                                bottomEnd = 6.dp,
                            ),
                        )
                        .background(MaterialTheme.colorScheme.onBackground)
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                )
            }
        }
    }
}

/**
 * 消息里的本地图片缩略图（v0.5 识图）。
 *
 * 文件被清/解码失败 → 显示「图片已删除」占位，不阻塞滚动（研究 §2.3.4）。
 * 触控目标 220dp（远大于 48dp），读屏描述说清"这是你发的图，点开看大图"。
 */
@Composable
fun MessageImageThumbnail(
    localPath: String,
    onOpen: () -> Unit,
    missingLabel: String = ImageCopy.IMAGE_MISSING,
    modifier: Modifier = Modifier,
) {
    val bitmap = rememberLocalImage(localPath, ImageMath.THUMBNAIL_EDGE)
    val shape = RoundedCornerShape(16.dp)
    Box(
        modifier = modifier
            .size(width = 220.dp, height = 180.dp)
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(
                enabled = bitmap != null,
                role = Role.Button,
                onClickLabel = ImageCopy.IMAGE_CONTENT_DESCRIPTION,
                onClick = onOpen,
            ),
        contentAlignment = Alignment.Center,
    ) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap,
                contentDescription = ImageCopy.IMAGE_CONTENT_DESCRIPTION,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Text(
                text = missingLabel,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * 读一张本地图片到 Compose（IO 线程解码 + `ThumbnailCache` 内存缓存）。
 *
 * key = 路径 + mtime + 目标边长（`ThumbnailCache` 内部）：文件被替换/删除不会拿旧图。
 * 页面销毁/`onTrimMemory` 由 `ThumbnailCache` 释放（`ChatViewModel.onCleared` + 系统回调）。
 */
@Composable
fun rememberLocalImage(localPath: String, targetEdge: Int): ImageBitmap? {
    val store = remember { AttachmentStore.installedOrNull() }
    return produceState<ImageBitmap?>(
        null,
        localPath,
        targetEdge,
        store,
    ) {
        value = if (store == null || localPath.isBlank()) {
            null
        } else {
            withContext(Dispatchers.IO) {
                val file = runCatching { store.path(localPath) }.getOrNull()
                val bitmap = file?.let { ThumbnailCache.load(it, targetEdge) }
                bitmap?.asImageBitmap()
            }
        }
    }.value
}

// ---------------------------------------------------------------- 输入条 / ＋ / 草稿

/**
 * 输入条（按官方 App 2.9.0 还原）：**两行**圆角容器。
 *
 * 输入条分为两行：第一行是文本或占位，第二行包含思考/搜索选项、附件按钮和主按钮。
 * 添加按钮放在输入条左侧内部会显得拥挤，因此放在第二行右侧。
 *
 * 语音输入行为：
 * - 普通模式空输入：主按钮 = 语音入口（Canvas 自绘麦克风），**点一下切到语音模式**，不是按住即录；
 * - 语音模式：第一行换成「按住说话」大按钮（视觉 ≥56dp：按住开始、松手结束、上滑取消），
 *   第二行主按钮 = 键盘（点一下切回普通模式）；有文字时主按钮按发送规则处理；
 * - 转写成功：`ChatViewModel` 给 [ChatComposer] 的 `insertText` / `insertToken`，本组件在**当前光标处**
 *   插入（空输入即填入），**不自动发送**，并聚焦输入框让用户改完再发；
 * - TalkBack：大按钮是「按钮」语义，读屏名「按住说话」，另给系统无障碍点击动作
 *   「开始录音 / 结束录音」——**点按开始、再点结束**，作为触摸手势的替代操作；
 *   触摸屏主路径仍是按住说话）。
 *
 * 48dp 触控（官方圆钮视觉约 26–27dp 只是估算）：视觉圆钮 34dp，
 * 外面套 48dp 热区，两枚按钮的 48dp 盒子相邻不重叠，避免误触。
 *
 * @param primary 主按钮状态（由 `ChatScreen` 用 `ComposerMainButtonResolver.resolve` 算好）
 * @param chips 第二行左侧内容（深度思考 / 智能搜索胶囊；非 DeepSeek 端点传 null）
 * @param showAddImage 是否显示「＋」（沿用 v0.5.0 的 `imageInputVisible`：非 DeepSeek 端点隐藏）
 * @param addImageEnabled 视觉是否可用；不可用时置灰但**仍可点**，点了由 ViewModel 给一句解释
 *   （禁用态 TalkBack 会跳过，做「点了没反应」更糟，见 `SettingsScreen` 音色行的同一取舍）
 * @param showVoiceMic 第二行是否显示独立麦克风入口（v0.5.2）。由 `ChatScreen` 用
 *   `shouldShowVoiceMic(...)` 算好：只有「文字模式 + 有内容 + 未生成 + 门禁可见」才显示；
 *   空输入不显示（主按钮已是麦克风，避免两个麦克风）
 * @param showKeyboardSwitch 语音模式里是否显示独立「切换至键盘」按钮（v0.5.2）。带着已有文字进
 *   语音模式时主按钮按发送规则解析（有字 = ↑），不能让用户没有切回打字的出路；空输入时主按钮
 *   本身就是键盘，不重复显示
 * @param voiceMode 是否语音模式（第一行换成「按住说话」大按钮）
 * @param voicePhase 语音状态（录音 / 转写 / 失败…；决定大按钮的可见文字与可交互性）
 * @param insertText 转写结果；由 ViewModel 置入，插入后本组件回调 `onTranscriptInserted` 清空
 * @param insertToken 插入信号（每次转写成功 +1；0 = 没有待插入文本）
 * @param onSlideCancelArmedChange 上滑是否已进入「松手取消」：手势事件循环内**同步**回调
 *   ViewModel 权威状态（v0.5.2禁止用 `LaunchedEffect` 上报）
 */
@Composable
fun ChatComposer(
    value: String,
    onValueChange: (String) -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
    primary: ComposerMainButton,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    placeholder: String = VoiceCopy.PLACEHOLDER,
    chips: (@Composable () -> Unit)? = null,
    showAddImage: Boolean = false,
    addImageEnabled: Boolean = true,
    addImageContentDescription: String = ImageCopy.ADD,
    onAddImage: () -> Unit = {},
    onSwitchToVoice: () -> Unit = {},
    onSwitchToKeyboard: () -> Unit = {},
    showVoiceMic: Boolean = false,
    showKeyboardSwitch: Boolean = false,
    voiceMode: Boolean = false,
    voicePhase: VoiceUiPhase = VoiceUiPhase.IDLE,
    voiceElapsedMs: Long = 0L,
    voiceError: String? = null,
    insertText: String? = null,
    insertToken: Int = 0,
    onTranscriptInserted: () -> Unit = {},
    onVoicePressStart: () -> Unit = {},
    onVoicePressEnd: () -> Unit = {},
    onVoiceCancel: () -> Unit = {},
    onSlideCancelArmedChange: (Boolean) -> Unit = {},
    onVoiceAccessibilityToggle: () -> Unit = {},
    onVoiceRetry: () -> Unit = {},
    onVoiceTypeInstead: () -> Unit = {},
    onUserInteraction: () -> Unit = {},
) {
    val currentOnUserInteraction by rememberUpdatedState(onUserInteraction)
    // 文本用 TextFieldValue 而不再只用 String：转写结果要插到**光标处**（官方参考实现同此，
    // `）。外部 value 变化（发送后清空 / 转写插入后回写）时同步内部值；
    // 用户键入时先更新内部值再回调，避免「受控 + 光标」两份状态互相打架。
    var fieldValue by remember {
        mutableStateOf(TextFieldValue(value, selection = TextRange(value.length)))
    }
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(value) {
        if (fieldValue.text != value) {
            fieldValue = TextFieldValue(value, selection = TextRange(value.length))
        }
    }
    // 转写结果插入：纯函数决定插在哪（空输入 → 填入；有字 → 光标处；选中 → 替换）。
    // 只改输入框文本、不调用发送（「不自动发送」由这里和 ViewModel 共同保证）。
    LaunchedEffect(insertToken) {
        val transcript = insertText
        if (insertToken > 0 && !transcript.isNullOrBlank()) {
            val inserted = VoiceTranscriptInsert.insert(
                current = fieldValue.text,
                selectionStart = fieldValue.selection.start,
                selectionEnd = fieldValue.selection.end,
                transcript = transcript,
            )
            fieldValue = TextFieldValue(inserted.text, selection = TextRange(inserted.cursor))
            onValueChange(inserted.text)
            // 转写成功后聚焦输入框（用户改完再发）；输入框还没挂载时也不崩。
            runCatching { focusRequester.requestFocus() }
            onTranscriptInserted()
        }
    }

    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(24.dp),
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        Column {
            if (voiceMode) {
                // 第一行替换成「按住说话」大按钮（官方语音模式形态，`）。
                VoiceHoldToTalkRow(
                    phase = voicePhase,
                    elapsedMs = voiceElapsedMs,
                    enabled = enabled,
                    onPressStart = onVoicePressStart,
                    onPressEnd = onVoicePressEnd,
                    onCancel = onVoiceCancel,
                    onSlideCancelArmedChange = onSlideCancelArmedChange,
                    onAccessibilityToggle = onVoiceAccessibilityToggle,
                )
                if (voiceError != null) {
                    VoiceErrorRow(
                        message = voiceError,
                        onRetry = onVoiceRetry,
                        onTypeInstead = onVoiceTypeInstead,
                    )
                }
            } else {
                // 第一行：文本 / 占位。多行时容器自然增高，第二行按钮始终贴底（官方同款）。
                // 上 6 / 下 0：文字自带 8dp 内边距，和官方「第一行略紧凑、第二行留足按钮高度」一致。
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 16.dp, end = 16.dp, top = 6.dp),
                ) {
                    if (fieldValue.text.isEmpty()) {
                        Text(
                            text = placeholder,
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = 8.dp),
                        )
                    }
                    BasicTextField(
                        value = fieldValue,
                        enabled = enabled,
                        readOnly = !enabled,
                        onValueChange = { newValue ->
                            if (!enabled) return@BasicTextField
                            currentOnUserInteraction()
                            fieldValue = newValue
                            if (newValue.text != value) onValueChange(newValue.text)
                        },
                        textStyle = MaterialTheme.typography.bodyLarge.copy(
                            color = if (enabled) {
                                MaterialTheme.colorScheme.onSurface
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        ),
                        maxLines = 6,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Default),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 8.dp)
                            .pointerInput(enabled) {
                                if (!enabled) return@pointerInput
                                awaitEachGesture {
                                    awaitFirstDown(requireUnconsumed = false)
                                    currentOnUserInteraction()
                                }
                            }
                            .focusRequester(focusRequester)
                            .testTag(CHAT_COMPOSER_INPUT_TEST_TAG),
                    )
                }
            }
            // 第二行：左胶囊、右下 [＋][主按钮]。高度下限 48dp，按钮不随字号/窄屏缩小。
            // 左侧 16dp 与第一行文字左缘对齐（官方胶囊与文本同一条左线）。
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .padding(start = 16.dp, end = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // 胶囊区用 weight 吃掉剩余宽度，200% 字号/窄屏时在胶囊内部横向滚动，不挤按钮。
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .padding(end = 8.dp),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    if (chips != null) chips()
                }
                // v0.5.2：第二行的独立语音入口——有文字时也能继续口述。
                // 固定 48dp 兄弟节点，在 `weight(1f)` 胶囊之外：200% 字号/窄屏时胶囊内部横向滚动，
                // 不会把这个入口挤走或滚出屏幕；空输入不显示（此时主按钮已是麦克风）。
                if (showVoiceMic) {
                    ComposerSecondaryIconButton(
                        contentDescription = VoiceCopy.USE_VOICE,
                        enabled = enabled,
                        onClick = onSwitchToVoice,
                    ) {
                        ComposerMicIcon(color = MaterialTheme.colorScheme.onSurface)
                    }
                }
                // 语音模式 + 已有内容：主按钮按「有字=发送」解析（禁止改主按钮解析），
                // 所以这里补一个独立的「切换至键盘」，保证用户能回到文字输入。
                if (showKeyboardSwitch) {
                    ComposerSecondaryIconButton(
                        contentDescription = VoiceCopy.SWITCH_TO_KEYBOARD,
                        enabled = enabled,
                        onClick = onSwitchToKeyboard,
                    ) {
                        ComposerKeyboardIcon(color = MaterialTheme.colorScheme.onSurface)
                    }
                }
                if (showAddImage) {
                    AddImageButton(
                        onClick = onAddImage,
                        enabled = addImageEnabled && enabled,
                        clickableEnabled = enabled,
                        contentDescription = addImageContentDescription,
                    )
                }
                ComposerMainButtonView(
                    state = primary,
                    onClick = {
                        when (primary.action) {
                            ComposerMainAction.SEND -> onSend()
                            ComposerMainAction.STOP -> onStop()
                            ComposerMainAction.SWITCH_TO_VOICE -> onSwitchToVoice()
                            ComposerMainAction.SWITCH_TO_KEYBOARD -> onSwitchToKeyboard()
                        }
                    },
                )
            }
        }
    }
}

/**
 * 语音模式第一行：按住说话的大按钮（≥48dp 触控；视觉高度 56dp，比文字行更醒目）。
 *
 * 手势（纯判定在 [VoiceHoldGesture]，这里只读手指事件）：
 * - 按下即开始录音；
 * - 松手结束并上传转写；
 * - 上滑超过 64dp → 松手取消（不上传、不花钱）。
 *
 * 无障碍：整块是「按钮」语义，读屏名「按住说话」；另注册系统无障碍点击动作
 * 「开始录音 / 结束录音」，TalkBack 双击即可开始、再双击结束。触摸屏主路径不受影响
 *（无障碍点击动作只由 TalkBack / 开关控制等辅助服务触发，普通触摸不会走 onClick 语义）。
 *
 * v0.5.2：`slideCancelArmed` 的**权威状态在 ViewModel**——手势事件循环里每次变化都
 * 同步回调 [onSlideCancelArmedChange]（不用 `LaunchedEffect`，避免与 100ms 录音轮询产生帧级竞态）；
 * 这里的局部副本只用于即时标签（「松手取消」）与松手判定。
 */
@Composable
private fun VoiceHoldToTalkRow(
    phase: VoiceUiPhase,
    elapsedMs: Long,
    enabled: Boolean = true,
    onPressStart: () -> Unit,
    onPressEnd: () -> Unit,
    onCancel: () -> Unit,
    onSlideCancelArmedChange: (Boolean) -> Unit,
    onAccessibilityToggle: () -> Unit,
) {
    val density = LocalDensity.current
    val cancelThresholdPx = with(density) { VoiceHoldGesture.CANCEL_THRESHOLD_DP.dp.toPx() }
    val recording = phase == VoiceUiPhase.RECORDING
    val transcribing = phase == VoiceUiPhase.TRANSCRIBING
    val interactive = enabled && !transcribing && phase != VoiceUiPhase.REQUESTING_PERMISSION
    var pressed by remember { mutableStateOf(false) }
    var slideCancelArmed by remember { mutableStateOf(false) }
    // armed 变化时同步上报 ViewModel（去重：每帧同值不重复写状态）。
    val reportArmed: (Boolean) -> Unit = { armed ->
        if (slideCancelArmed != armed) {
            slideCancelArmed = armed
            onSlideCancelArmedChange(armed)
        }
    }
    val shape = RoundedCornerShape(18.dp)
    val background = if (recording) {
        MaterialTheme.colorScheme.onBackground
    } else {
        MaterialTheme.colorScheme.surface
    }
    val foreground = if (recording) {
        MaterialTheme.colorScheme.background
    } else {
        MaterialTheme.colorScheme.onSurface
    }
    val label = when {
        transcribing -> VoiceCopy.TRANSCRIBING
        recording && slideCancelArmed -> VoiceCopy.SLIDE_CANCEL_ARMED
        recording -> VoiceCopy.RECORDING_LABEL
        else -> VoiceCopy.HOLD_TO_TALK
    }
    val description = when {
        recording -> VoiceCopy.RECORDING_CONTENT_DESCRIPTION
        transcribing -> VoiceCopy.TRANSCRIBING
        else -> VoiceCopy.HOLD_TO_TALK
    }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = 8.dp)
            .heightIn(min = 56.dp)
            .clip(shape)
            .background(background)
            .then(
                if (recording) {
                    Modifier
                } else {
                    Modifier.border(1.dp, MaterialTheme.colorScheme.outline, shape)
                },
            )
            .alpha(
                when {
                    !interactive -> 0.6f
                    pressed && !recording -> 0.8f
                    else -> 1f
                },
            )
            .pointerInput(interactive) {
                if (!interactive) return@pointerInput
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    pressed = true
                    // 按下先清 armed（ViewModel 的 onVoicePressStart 也会清；两处同口径，
                    // 「启动窗口内快速上滑」不会留下上一次的残留状态）。
                    reportArmed(false)
                    // 按下即开始；已经在录时 ViewModel 会忽略这次 Start（重复按下不产生副作用）。
                    onPressStart()
                    var released = false
                    while (!released) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id }
                        if (change == null) {
                            released = true
                            break
                        }
                        val dragUp = (down.position.y - change.position.y).coerceAtLeast(0f)
                        // 每帧同步上报（不防抖、不用 LaunchedEffect）：60 秒自停那一刻
                        // ViewModel 必须已经看到 armed，才能丢弃而不是上传（§7.2 A/B）。
                        reportArmed(VoiceHoldGesture.shouldCancel(dragUp, cancelThresholdPx))
                        if (!change.pressed) {
                            released = true
                            if (slideCancelArmed) onCancel() else onPressEnd()
                        }
                    }
                    pressed = false
                    // 松手/指针丢失都回 false：ViewModel 侧松手、取消、离开页面时也会各清一次。
                    reportArmed(false)
                }
            }
            .semantics {
                contentDescription = description
                onClick(
                    label = if (recording) {
                        VoiceCopy.RECORD_STOP_ACTION
                    } else {
                        VoiceCopy.RECORD_START_ACTION
                    },
                ) {
                    onAccessibilityToggle()
                    true
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyLarge,
                color = foreground,
            )
            if (recording) {
                // 录音计时（0:00–1:00）；只剩 10 秒内多一句提醒（纯函数在 VoiceCopy）。
                Text(
                    text = VoiceCopy.elapsedLabel(elapsedMs),
                    style = MaterialTheme.typography.labelLarge,
                    color = foreground,
                )
                VoiceCopy.secondsLeftHint(elapsedMs)?.let { hint ->
                    Text(
                        text = hint,
                        style = MaterialTheme.typography.bodySmall,
                        color = foreground.copy(alpha = 0.75f),
                    )
                }
            }
        }
    }
}

/**
 * 语音失败行：一句人话 + 两条出路（再试一次 / 先打字）。
 *
 * 「不自动重试」是产品决策（重试会再发一次音频、可能重复计费，` 同一口径）；
 * 两个按钮都是 48dp 触控目标。
 */
@Composable
private fun VoiceErrorRow(
    message: String,
    onRetry: () -> Unit,
    onTypeInstead: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 8.dp, top = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = onRetry, modifier = Modifier.heightIn(min = 48.dp)) {
            Text(text = VoiceCopy.RETRY, style = MaterialTheme.typography.labelLarge)
        }
        TextButton(onClick = onTypeInstead, modifier = Modifier.heightIn(min = 48.dp)) {
            Text(text = VoiceCopy.TYPE_INSTEAD, style = MaterialTheme.typography.labelLarge)
        }
    }
}

/**
 * 第二行的次要圆形图标按钮（v0.5.2：独立麦克风入口 / 语音模式切回键盘）。
 *
 * 与 [AddImageButton] 同一套几何：视觉 34dp 描边圆 + 外层 48dp 热区（触控 ≥48dp，
 * ），图标复用本文件自绘的 [ComposerMicIcon] / [ComposerKeyboardIcon]。
 * 放在 `weight(1f)` 胶囊之外：窄屏/200% 字号时胶囊自己横向滚动，这个按钮不会被挤走。
 */
@Composable
private fun ComposerSecondaryIconButton(
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    icon: @Composable () -> Unit,
) {
    Box(
        modifier = modifier
            .size(48.dp)
            .clip(CircleShape)
            .clickable(enabled = enabled, role = Role.Button, onClickLabel = contentDescription, onClick = onClick)
            .alpha(if (enabled) 1f else 0.35f)
            .semantics { this.contentDescription = contentDescription },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(ComposerButtonVisualSize)
                .clip(CircleShape)
                .border(
                    width = 1.dp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                    shape = CircleShape,
                ),
            contentAlignment = Alignment.Center,
        ) {
            icon()
        }
    }
}

/**
 * 输入条右下角「＋」（附件入口，从输入条外左侧移入）。
 *
 * 官方「＋」在输入条**内部右下**、与主按钮同一行。
 * 视觉 34dp 描边圆，外面套 48dp 热区。读屏描述沿用 `ImageCopy.ADD`=「添加图片」
 * （PulseZ 只支持图片，不学官方写「上传文件」，避免让用户以为能传 PDF）。
 *
 * 不可用时仍可点：点了由 ViewModel 给一句「这个 AI 看不了图」的解释
 * （禁用态 TalkBack 会跳过，做「点了没反应」更糟，见 `SettingsScreen` 音色行的同一取舍）。
 */
@Composable
private fun AddImageButton(
    onClick: () -> Unit,
    enabled: Boolean,
    contentDescription: String,
    modifier: Modifier = Modifier,
    clickableEnabled: Boolean = true,
) {
    Box(
        modifier = modifier
            .size(48.dp)
            .clip(CircleShape)
            .clickable(
                enabled = clickableEnabled,
                role = Role.Button,
                onClickLabel = contentDescription,
                onClick = onClick,
            )
            .semantics { this.contentDescription = contentDescription },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(ComposerButtonVisualSize)
                .clip(CircleShape)
                // 官方「＋」是细描边圆环；`outline` 在 surfaceVariant 上对比太低（≈1.1:1），
                // 用 onSurfaceVariant 40% 压出来的灰环，仍是黑白灰 token，不新增颜色概念。
                .border(
                    width = 1.dp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                    shape = CircleShape,
                )
                .alpha(if (enabled) 1f else 0.35f),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Filled.Add,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.size(26.dp),
            )
        }
    }
}

/**
 * 草稿行：只用非空时出现。每张缩略图 + 48dp 删除按钮，
 * 准备中/失败态给可读状态；动图只取第一帧时在行下说明一句。
 */
@Composable
fun ImageDraftRow(
    drafts: List<DraftImage>,
    onRemove: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (drafts.isEmpty()) return
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = 4.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            drafts.forEach { draft ->
                DraftImageItem(draft = draft, onRemove = onRemove)
            }
        }
        if (drafts.any { it.status == DraftStatus.READY && it.firstFrameOnly }) {
            Text(
                text = ImageCopy.GIF_FIRST_FRAME,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

@Composable
private fun DraftImageItem(draft: DraftImage, onRemove: (String) -> Unit) {
    Box(modifier = Modifier.size(64.dp)) {
        Box(
            modifier = Modifier
                .size(56.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            val bitmap = if (draft.status == DraftStatus.READY) {
                rememberLocalImage(draft.localPath, ImageMath.THUMBNAIL_EDGE)
            } else {
                null
            }
            when {
                bitmap != null -> Image(
                    bitmap = bitmap,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
                draft.status == DraftStatus.READY -> Text(
                    text = ImageCopy.IMAGE_MISSING,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
                draft.status == DraftStatus.PREPARING -> Text(
                    text = ImageCopy.DRAFT_PREPARING,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
                else -> Text(
                    text = ImageCopy.DRAFT_FAILED,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 4.dp),
                )
            }
        }
        // 48dp 删除目标压在右上角（整块 64dp 内），读屏描述说清"删掉这张"。
        IconButton(
            onClick = { onRemove(draft.id) },
            modifier = Modifier
                .align(Alignment.TopEnd)
                .size(48.dp),
        ) {
            Icon(
                imageVector = Icons.Filled.Close,
                contentDescription = ImageCopy.DRAFT_REMOVE,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(16.dp),
            )
        }
    }
}

// ---------------------------------------------------------------- 选图面板 / 大图预览

/**
 * 「拍照 / 从相册选」底部面板（v0.5 识图；沿用 `GroupPickerSheet` 的 `ModalBottomSheet`）。
 *
 * 返回手势 = 收弹层（自己处理），底部导航条 inset 也由 处理
 * （要求验收"返回一次只收弹层，不退聊天页"）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImageSourceSheet(
    onCamera: () -> Unit,
    onGallery: () -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = if (isSystemInDarkTheme()) DarkSurfaceHigh else LightSurfaceHigh,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp)) {
            Text(
                text = ImageCopy.SOURCE_TITLE,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 8.dp),
            )
            Text(
                text = ImageCopy.SOURCE_DESCRIPTION,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 8.dp),
            )
            SheetAction(label = ImageCopy.SOURCE_CAMERA, onClick = onCamera)
            SheetAction(label = ImageCopy.SOURCE_GALLERY, onClick = onGallery)
        }
    }
}

@Composable
private fun SheetAction(label: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onBackground,
        )
    }
}

/**
 * 大图预览（v0.5 识图）。
 *
 * - `Dialog` 自带返回处理：系统返回 = 先关预览，不退聊天页；
 * - 关闭按钮 48dp（右上角），底部 48dp「再用这张图问一次」（重发同一本地文件）；
 * - 图片按 1280px 解码（`ThumbnailCache`），不整张原图进内存。
 */
@Composable
fun ImagePreviewDialog(
    localPath: String,
    onClose: () -> Unit,
    onResend: () -> Unit,
) {
    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
                // Dialog 是独立窗口：系统栏 inset 不继承聊天页，这里自己加，避免 48dp 关闭键压到状态栏。
                .systemBarsPadding(),
        ) {
            val bitmap = rememberLocalImage(localPath, ImageMath.PREVIEW_EDGE)
            if (bitmap != null) {
                Image(
                    bitmap = bitmap,
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(top = 56.dp, bottom = 72.dp),
                )
            } else {
                Text(
                    text = ImageCopy.IMAGE_MISSING,
                    color = Color.White,
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.align(Alignment.Center),
                )
            }
            IconButton(
                onClick = onClose,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(8.dp)
                    .size(48.dp),
            ) {
                Icon(
                    imageVector = Icons.Filled.Close,
                    contentDescription = ImageCopy.PREVIEW_CLOSE,
                    tint = Color.White,
                )
            }
            TextButton(
                onClick = onResend,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 24.dp)
                    .heightIn(min = 48.dp),
            ) {
                Text(
                    text = ImageCopy.PREVIEW_RESEND,
                    color = Color.White,
                    style = MaterialTheme.typography.labelLarge,
                )
            }
        }
    }
}

// ---------------------------------------------------------------- 其余共享视觉件

/**
 * AI 标识条：首次完整展示，可折叠，不可移除。
 * 1:1 与群聊的差别只在文案（单数 TA / 复数 TA 们）。
 */
@Composable
fun AiNoticeBar(
    text: String,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = 20.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = onDismiss, modifier = Modifier.size(40.dp)) {
            Icon(
                Icons.Filled.Close,
                contentDescription = "收起提示",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * TA 在写：三个点，全 App 唯一允许的循环动画。
 *
 * @param label TalkBack 文案（纯视觉状态必须有等价描述）。群聊里传「XX 正在写」。
 */
@Composable
fun TypingDots(
    modifier: Modifier = Modifier,
    label: String = "TA 正在写",
) {
    val transition = rememberInfiniteTransition(label = "typing")
    Row(
        modifier = modifier
            .padding(vertical = 6.dp)
            .semantics { this.contentDescription = label },
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        repeat(3) { index ->
            val alpha by transition.animateFloat(
                initialValue = 0.3f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(
                    animation = tween(durationMillis = 1200, delayMillis = index * 160, easing = LinearEasing),
                    repeatMode = RepeatMode.Reverse,
                ),
                label = "dot$index",
            )
            Box(
                modifier = Modifier
                    .size(5.dp)
                    .alpha(alpha)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.onSurfaceVariant),
            )
        }
    }
}

/**
 * 输入条右下角主按钮（`v0.5.1 加语音/键盘两态）。
 *
 * - 可点：实心 ink 圆 + 页面底色图标（浅色主题 = 黑底白图标，深色主题相反；官方也是
 *   实心深色圆 + 白图标）。不用 `signal` 紫：用户明确要黑白极简，官方也不是彩色；
 * - 禁用（图片还在处理）：透明圆 + `onSurfaceVariant` 灰箭头（空输入的禁用箭头已被语音入口替代）；
 * - 图标四态：↑ 发送 / ■ 停下 / 麦克风（切到语音模式）/ 键盘（切回文字模式），
 *   Crossfade 150ms（只做透明度，不做缩放回弹）；
 * - 图标全部 Canvas 自绘（不写 `↑`/emoji/不引图标库），`。
 *
 * 无障碍：整块 48dp 是一个「按钮」语义节点，`contentDescription` 随状态变成
 * 「发送」/「停下」/「切换至语音」/「切换至键盘」；`enabled = false` 时 TalkBack 额外念
 * 「已停用」，不会出现「念着能用却点不动」的假入口。
 */
@Composable
private fun ComposerMainButtonView(
    state: ComposerMainButton,
    onClick: () -> Unit,
) {
    // 黑白极简：可用 = 深色底 + 反色图标（深色主题自动反转）；禁用 = 透明底 + 弱化灰。
    val background = if (state.enabled) {
        MaterialTheme.colorScheme.onBackground
    } else {
        Color.Transparent
    }
    val foreground = if (state.enabled) {
        MaterialTheme.colorScheme.background
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    Box(
        modifier = Modifier
            .size(48.dp)
            .clip(CircleShape)
            .clickable(
                enabled = state.enabled,
                role = Role.Button,
                onClickLabel = state.contentDescription,
                onClick = onClick,
            )
            .semantics { contentDescription = state.contentDescription },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(ComposerButtonVisualSize)
                .clip(CircleShape)
                .background(background),
            contentAlignment = Alignment.Center,
        ) {
            Crossfade(
                targetState = state.icon,
                animationSpec = tween(durationMillis = ComposerIconSwitchMillis),
                label = "composer-main-icon",
            ) { icon ->
                // 四种图标都套在 24dp 盒子里居中，Crossfade 切换时不会跳位。
                Box(modifier = Modifier.size(24.dp), contentAlignment = Alignment.Center) {
                    when (icon) {
                        ComposerMainIcon.ARROW_UP -> ComposerArrowIcon(color = foreground)
                        ComposerMainIcon.STOP -> Box(
                            modifier = Modifier
                                .size(16.dp)
                                .background(foreground, RoundedCornerShape(2.dp)),
                        )
                        ComposerMainIcon.MIC -> ComposerMicIcon(color = foreground)
                        ComposerMainIcon.KEYBOARD -> ComposerKeyboardIcon(color = foreground)
                    }
                }
            }
        }
    }
}

/**
 * 向上箭头的自绘图标（官方发送图标就是「竖线 + 向上人字」，不是纸飞机）。
 *
 * 不用字符 `↑`：字符基线与粗细随系统字体变化、部分系统缺字形（明确不用 Unicode 符号）；
 * `material-icons-core` 里没有 `ArrowUpward`（1.7.6 实测只有 Add/Clear/Close/Send 等少数常用图标，
 * 不含完整图标集），又不引 material-icons-extended，所以按官方 path 的几何比例用 Canvas 画：
 * 以 20dp 为基准等比放大到 24dp 画布（unit = 1.2），基准线宽 2.1dp 在画布上实际约 2.52dp；
 * 箭头两斜边在基准 y≈8.6dp（画布上约 10.3dp）收口（研究 §5.2）。
 */
@Composable
private fun ComposerArrowIcon(color: Color) {
    Canvas(modifier = Modifier.size(24.dp)) {
        val unit = size.width / 20f
        val stroke = 2.1f * unit
        val centerX = size.width / 2f
        val apexY = 3f * unit
        val baseY = 8.6f * unit
        drawLine(
            color = color,
            start = Offset(centerX, apexY),
            end = Offset(centerX, 17f * unit),
            strokeWidth = stroke,
            cap = StrokeCap.Round,
        )
        drawLine(
            color = color,
            start = Offset(4.5f * unit, baseY),
            end = Offset(centerX, apexY),
            strokeWidth = stroke,
            cap = StrokeCap.Round,
        )
        drawLine(
            color = color,
            start = Offset(centerX, apexY),
            end = Offset(15.5f * unit, baseY),
            strokeWidth = stroke,
            cap = StrokeCap.Round,
        )
    }
}

/**
 * 麦克风的自绘图标（空输入主按钮 = 语音入口，v0.5.1）。
 *
 * 空输入主按钮用麦克风轮廓表示语音模式入口；图标由 24dp Canvas 绘制，不使用 emoji 或图标库，
 * 笔画粗细与 [ComposerArrowIcon] 同一量级（约 2dp）。
 */
@Composable
private fun ComposerMicIcon(color: Color) {
    Canvas(modifier = Modifier.size(24.dp)) {
        val unit = size.width / 24f
        val stroke = 1.9f * unit
        // 话筒头：圆角胶囊（描边）。
        drawRoundRect(
            color = color,
            topLeft = Offset(9f * unit, 3f * unit),
            size = Size(6f * unit, 10f * unit),
            cornerRadius = CornerRadius(3f * unit, 3f * unit),
            style = Stroke(width = stroke),
        )
        // 话筒托：下半弧。
        drawArc(
            color = color,
            startAngle = 0f,
            sweepAngle = 180f,
            useCenter = false,
            topLeft = Offset(6f * unit, 8f * unit),
            size = Size(12f * unit, 10f * unit),
            style = Stroke(width = stroke, cap = StrokeCap.Round),
        )
        // 支架竖线 + 底座横线。
        drawLine(
            color = color,
            start = Offset(12f * unit, 18f * unit),
            end = Offset(12f * unit, 21f * unit),
            strokeWidth = stroke,
            cap = StrokeCap.Round,
        )
        drawLine(
            color = color,
            start = Offset(8f * unit, 21f * unit),
            end = Offset(16f * unit, 21f * unit),
            strokeWidth = stroke,
            cap = StrokeCap.Round,
        )
    }
}

/**
 * 键盘的自绘图标（语音模式主按钮 = 切回文字模式，v0.5.1）。
 *
 * 对齐官方 `ic_keyboard_outline_26` 的语义：圆角矩形机身 + 一排按键 + 空格条，24dp 线稿。
 * 语音模式下用户要能一眼看出「点这个回到打字」，所以不用文字按钮（不占空间、不挤压胶囊）。
 */
@Composable
private fun ComposerKeyboardIcon(color: Color) {
    Canvas(modifier = Modifier.size(24.dp)) {
        val unit = size.width / 24f
        val stroke = 1.7f * unit
        drawRoundRect(
            color = color,
            topLeft = Offset(3f * unit, 6f * unit),
            size = Size(18f * unit, 12f * unit),
            cornerRadius = CornerRadius(2.5f * unit, 2.5f * unit),
            style = Stroke(width = stroke),
        )
        // 第一排三个键。
        listOf(7f, 12f, 17f).forEach { x ->
            drawLine(
                color = color,
                start = Offset(x * unit, 10f * unit),
                end = Offset((x + 0.9f) * unit, 10f * unit),
                strokeWidth = stroke,
                cap = StrokeCap.Round,
            )
        }
        // 空格条。
        drawLine(
            color = color,
            start = Offset(8.5f * unit, 14.5f * unit),
            end = Offset(15.5f * unit, 14.5f * unit),
            strokeWidth = stroke,
            cap = StrokeCap.Round,
        )
    }
}

/** 主按钮视觉圆直径；外面始终套 48dp 热区（官方 26–27dp 只是视觉估算）。*/
private val ComposerButtonVisualSize = 34.dp

/** 主按钮图标切换时长：轻量淡入淡出，不做夸张动画。*/
private const val ComposerIconSwitchMillis = 150

/**
 * 朗读播放提示（v0.5）：
 * 输入条上方一行「正在朗读…」+「停下」。
 *
 * - 放在 `ChatScreen` 的错误/轻提示同一区域：不打断消息列表、不做全屏播放器；
 * - 「停下」是 48dp 的 `TextButton`；整行用 `semantics(mergeDescendants = true)` 合并成**一个**
 *   读屏焦点，描述只说一次「正在朗读，点停下可以停止」——避免 Row 的描述和按钮的描述
 *   被 TalkBack 各播一遍。合并后按钮的点击语义仍在合并节点上，
 *   读屏双击即可停下；
 * - 只停这一次，不关自动朗读；
 * - 无动画：贴合 「不做弹跳/循环」，避免和消息流抢注意力。
 */
@Composable
fun ReadingHintBar(onStop: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = 20.dp, end = 12.dp, top = 4.dp, bottom = 4.dp)
            .heightIn(min = 48.dp)
            .semantics(mergeDescendants = true) {
                contentDescription = ReadCopy.READING_CONTENT_DESCRIPTION
            },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = ReadCopy.READING,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        TextButton(
            onClick = onStop,
            modifier = Modifier.heightIn(min = 48.dp),
        ) {
            Text(text = ReadCopy.STOP, style = MaterialTheme.typography.labelLarge)
        }
    }
}
