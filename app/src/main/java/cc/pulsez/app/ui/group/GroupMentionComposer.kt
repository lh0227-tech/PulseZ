package cc.pulsez.app.ui.group

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cc.pulsez.app.core.GroupMember
import cc.pulsez.app.core.MentionParser

internal const val GROUP_COMPOSER_INPUT_TEST_TAG = "group-composer-input"

/**
 * 群聊输入条 +「@」点名。
 *
 * 视觉沿用 1:1 的输入条（圆角 24、`surfaceVariant` 底）；发送键与单聊一样
 * **黑白化**（= `onBackground` 实心 + `background` 反色图标），不用 `primary`，
 * 只在左侧多一个 48dp 的「@」按钮；不复用 `ui/chat` 的输入条是因为它不支持
 * `VisualTransformation`（本文件是群聊专属的薄包装，不改 1:1 的行为）。
 *
 * 交互（「防误触四条」）：
 * - 点「@」才弹成员列表（不靠输入里打 `@` 自动弹，避免打断打字）；
 * - 选中后插入 `@名字 `，光标放在后面，**不自动发送**；
 * - 发送前不做任何校验：没匹配上的名字当普通文字发出去。
 *
 * @param members 当前群成员（成员列表弹层 + 输入框高亮都用它）
 */
@Composable
fun GroupComposer(
    value: String,
    members: List<GroupMemberUi>,
    isStreaming: Boolean,
    onValueChange: (String) -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "说点什么……",
    onUserInteraction: () -> Unit = {},
) {
    val currentOnUserInteraction by rememberUpdatedState(onUserInteraction)
    var showMentionPicker by remember { mutableStateOf(false) }
    // 输入框自己持有 TextFieldValue 才能做到「插入后光标在 @名字 后面」；
    // 外部值（发送后清空）变化时同步过来，并把光标夹回合法范围。
    var fieldValue by remember { mutableStateOf(TextFieldValue(value, TextRange(value.length))) }
    LaunchedEffect(value) {
        if (fieldValue.text != value) {
            val start = fieldValue.selection.start.coerceIn(0, value.length)
            val end = fieldValue.selection.end.coerceIn(0, value.length)
            fieldValue = TextFieldValue(value, TextRange(start, end))
        }
    }

    val highlightColor = MaterialTheme.colorScheme.primary
    val transformation = remember(members, highlightColor) {
        MentionHighlightTransformation(members.toCoreMembers(), highlightColor)
    }

    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(24.dp),
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        Row(
            modifier = Modifier.padding(start = 4.dp, end = 16.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            // 「@」入口：48dp 触控，点开成员列表，而不是直接发消息
            Box {
                IconButton(
                    onClick = { showMentionPicker = true },
                    modifier = Modifier
                        .size(48.dp)
                        .semantics { contentDescription = "提到谁" },
                ) {
                    Text(
                        text = "@",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                MentionMemberMenu(
                    expanded = showMentionPicker,
                    members = members,
                    onDismiss = { showMentionPicker = false },
                    onPick = { member ->
                        val inserted = insertMention(fieldValue, member.name)
                        fieldValue = inserted
                        onValueChange(inserted.text)
                        showMentionPicker = false
                    },
                )
            }

            Box(modifier = Modifier.weight(1f)) {
                if (value.isEmpty()) {
                    Text(
                        text = placeholder,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 8.dp),
                    )
                }
                BasicTextField(
                    value = fieldValue,
                    onValueChange = { updated ->
                        currentOnUserInteraction()
                        fieldValue = updated
                        onValueChange(updated.text)
                    },
                    textStyle = MaterialTheme.typography.bodyLarge.copy(
                        color = MaterialTheme.colorScheme.onSurface,
                    ),
                    maxLines = 6,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Default),
                    visualTransformation = transformation,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp)
                        .pointerInput(Unit) {
                            awaitEachGesture {
                                awaitFirstDown(requireUnconsumed = false)
                                currentOnUserInteraction()
                            }
                        }
                        .testTag(GROUP_COMPOSER_INPUT_TEST_TAG),
                )
            }

            Spacer(Modifier.width(8.dp))
            GroupSendButton(
                enabled = value.isNotBlank() || isStreaming,
                isStreaming = isStreaming,
                onClick = { if (isStreaming) onStop() else onSend() },
            )
        }
    }
}

/**
 * 成员列表弹层（复用底部弹层的行视觉：圆形首字头像 + 名字）。
 * 每项 ≥48dp（`DropdownMenuItem` 默认 48dp，这里再显式卡一次）。
 */
@Composable
private fun MentionMemberMenu(
    expanded: Boolean,
    members: List<GroupMemberUi>,
    onDismiss: () -> Unit,
    onPick: (GroupMemberUi) -> Unit,
) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        members.forEach { member ->
            DropdownMenuItem(
                text = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Box(
                            modifier = Modifier
                                .size(28.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.surfaceVariant),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = member.name.take(1),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Text(
                            text = member.name,
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onBackground,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                },
                onClick = { onPick(member) },
                modifier = Modifier.heightIn(min = 48.dp),
            )
        }
    }
}

/**
 * 在光标处插入 `@名字 `（不自动发送、光标放在后面）。
 *
 * 光标前如果刚打了半截 `@小`，先把这段替换掉，避免出现 `@小@阿淮`；
 * 前一个字符不是空白时补一个空格，保证 `你好@阿淮` 这种手打文本也能被解析成点名边界。
 */
internal fun insertMention(current: TextFieldValue, name: String): TextFieldValue {
    val text = current.text
    val cursor = current.selection.end.coerceIn(0, text.length)
    val atIndex = maxOf(
        text.lastIndexOf('@', cursor - 1),
        text.lastIndexOf('＠', cursor - 1),
    )
    // 只替换「光标前那个 @ 到光标」之间没有空白的半截 token；否则就插在光标处
    val replaceStart = if (
        atIndex >= 0 && text.substring(atIndex + 1, cursor).none { it.isWhitespace() }
    ) {
        atIndex
    } else {
        cursor
    }
    val before = text.substring(0, replaceStart)
    val after = text.substring(cursor)
    val token = buildString {
        if (before.isNotEmpty() && !before.last().isWhitespace()) append(' ')
        append('@').append(name)
        if (after.isEmpty() || after.first().isWhitespace()) return@buildString
        append(' ')
    }
    val newText = before + token + after
    return TextFieldValue(newText, TextRange(before.length + token.length))
}

/**
 * 输入框里的 `@名字` 高亮：`primary`（signal）色、不加粗、不加底色。
 * 用 [MentionParser.highlightRanges] 在渲染时按当前成员名重算，改名/退群只是不再高亮。
 */
private class MentionHighlightTransformation(
    private val members: List<GroupMember>,
    private val highlightColor: Color,
) : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText {
        val ranges = MentionParser.highlightRanges(text.text, members)
        if (ranges.isEmpty()) return TransformedText(text, OffsetMapping.Identity)
        val styled = buildAnnotatedString {
            append(text)
            for (range in ranges) {
                addStyle(SpanStyle(color = highlightColor), range.first, range.last + 1)
            }
        }
        // 只改颜色/样式，不增删字符，偏移映射保持恒等（光标与选区不会错位）
        return TransformedText(styled, OffsetMapping.Identity)
    }
}

/**
 * 群聊发送按钮（v0.5.4）：与单聊输入条主按钮同一套黑白配色
 * （`输入条主按钮不走 `signal`），不再用强调色 `primary` 填整枚圆钮。
 *
 * 颜色角色由纯函数 [GroupSendButtonColors.resolve] 决定（JVM 单测 `GroupSendButtonColorsTest`），
 * 本函数只把角色映射到 `ColorScheme`——可用 / 生成中 = `onBackground` 实心 + `background` 反色图标；
 * 禁用 = 透明底 + `onSurfaceVariant` 弱化图标。图标与几何保持原样（不顺手改 1:1 的视觉）。
 */
@Composable
private fun GroupSendButton(enabled: Boolean, isStreaming: Boolean, onClick: () -> Unit) {
    val colors = GroupSendButtonColors.resolve(enabled = enabled, streaming = isStreaming)
    val background = colors.background.toComposeColor()
    val foreground = colors.foreground.toComposeColor()
    val description = if (isStreaming) "停下" else "发送"
    Box(
        modifier = Modifier.size(48.dp),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(background),
            contentAlignment = Alignment.Center,
        ) {
            IconButton(
                onClick = onClick,
                enabled = enabled,
                modifier = Modifier
                    .size(48.dp)
                    .semantics { contentDescription = description },
            ) {
                if (isStreaming) {
                    Box(
                        modifier = Modifier
                            .size(12.dp)
                            .background(foreground, RoundedCornerShape(2.dp)),
                    )
                } else {
                    Text(text = "↑", color = foreground, style = MaterialTheme.typography.titleMedium)
                }
            }
        }
    }
}

/** 群聊发送按钮的颜色角色（纯数据、不依赖 Compose，JVM 单测可直接断言）。 */
internal enum class GroupSendButtonRole {
    /** `onBackground`：深色实心底（浅色主题黑底、深色主题浅底，随主题反色）。 */
    ON_BACKGROUND,

    /** `background`：页面底色，做反色图标 / 停止方块（与 [ON_BACKGROUND] 搭配）。 */
    BACKGROUND,

    /** `onSurfaceVariant`：弱化灰（禁用态图标）。 */
    ON_SURFACE_VARIANT,

    /** 透明（禁用态底）。 */
    TRANSPARENT,
}

/**
 * 群聊发送按钮三态的颜色角色（v0.5.4）。
 *
 * 依据 `「输入条主按钮不走 signal（v0.5.0 发布后黑白化）」：
 * 可用 = `onBackground` 深色实心 + `background` 反色上箭头；禁用 = 透明底 + `onSurfaceVariant`；
 * 生成中 = `onBackground` 底 + `background` ■「停下」。
 *
 * 与单聊 `ComposerMainButtonView`（`ui/chat/ChatComponents.kt:1064-1074`）逐角色对齐：那边只判
 * `state.enabled`，「停下」与「发送」共用同一套可用态配色。所以这里的 [resolve] 同理——
 * [streaming] 不改变颜色，保留在签名里是为了让调用点与测试把「可用 / 生成中 / 禁用」三态
 * 显式覆盖，防止以后有人误以为生成中该另配一套色、又退回 `primary`。
 */
internal data class GroupSendButtonColors(
    val background: GroupSendButtonRole,
    val foreground: GroupSendButtonRole,
) {
    companion object {
        /** 三态取色：不可点 → 透明 + 弱化灰；可点（含生成中「停下」）→ 深色实心 + 反色图标。 */
        fun resolve(enabled: Boolean, streaming: Boolean): GroupSendButtonColors = when {
            // 禁用：透明底 + 弱化灰上箭头。
            !enabled -> GroupSendButtonColors(
                background = GroupSendButtonRole.TRANSPARENT,
                foreground = GroupSendButtonRole.ON_SURFACE_VARIANT,
            )
            // 生成中：「停下」■ 也是可点的实心按钮，与单聊 STOP 同一套配色。
            streaming -> GroupSendButtonColors(
                background = GroupSendButtonRole.ON_BACKGROUND,
                foreground = GroupSendButtonRole.BACKGROUND,
            )
            // 可用：深色实心 + 反色上箭头。
            else -> GroupSendButtonColors(
                background = GroupSendButtonRole.ON_BACKGROUND,
                foreground = GroupSendButtonRole.BACKGROUND,
            )
        }
    }
}

/**
 * 颜色角色 → Compose 颜色（映射只写这一处，`GroupSendButton` 不直接引 ColorScheme 角色）。
 *
 * 两个组合都对应 ` 的黑白口径；**没有 `primary` 分支**——强调色不属于主按钮。
 */
@Composable
private fun GroupSendButtonRole.toComposeColor(): Color = when (this) {
    GroupSendButtonRole.ON_BACKGROUND -> MaterialTheme.colorScheme.onBackground
    GroupSendButtonRole.BACKGROUND -> MaterialTheme.colorScheme.background
    GroupSendButtonRole.ON_SURFACE_VARIANT -> MaterialTheme.colorScheme.onSurfaceVariant
    GroupSendButtonRole.TRANSPARENT -> Color.Transparent
}

/** UI 成员 → core 成员：解析器只依赖 `characterId` / `name`（`persona` 不参与点名）。 */
internal fun List<GroupMemberUi>.toCoreMembers(): List<GroupMember> = map {
    GroupMember(characterId = it.id, name = it.name, persona = "")
}
