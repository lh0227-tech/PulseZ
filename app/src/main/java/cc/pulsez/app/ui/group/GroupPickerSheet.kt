package cc.pulsez.app.ui.group

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import cc.pulsez.app.ui.theme.DarkSurfaceHigh
import cc.pulsez.app.ui.theme.LightSurfaceHigh
import cc.pulsez.app.ui.theme.PulsezTheme
import kotlinx.coroutines.launch

/** 成员选择弹层里的一位候选角色。只是 UI 模型：名字来自现有 character 列表。 */
data class GroupPickerMember(
    val id: String,
    val name: String,
)

/**
 * 成员选择底部弹层：
 * - 列出所有角色，复选；
 * - 选够 2 人才可「一起聊」，最多 4 人；已选满 4 人后未选项置灰，并提示「最多 4 个，再多聊不过来」；
 * - 内容多时内部滚动，整层高度 ≤ 屏高 80%。
 *
 * 状态全部由外部持有（members / selectedIds），方便纯内存 fake 渲染。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GroupPickerSheet(
    members: List<GroupPickerMember>,
    selectedIds: Set<String>,
    onToggle: (String) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    // 先播完收起动画再回调（底部弹层 260ms），避免页面一帧被硬切
    val confirmAndHide: () -> Unit = {
        scope.launch { sheetState.hide() }.invokeOnCompletion { onConfirm() }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = if (isSystemInDarkTheme()) DarkSurfaceHigh else LightSurfaceHigh,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
    ) {
        GroupPickerSheetContent(
            members = members,
            selectedIds = selectedIds,
            onToggle = onToggle,
            onConfirm = confirmAndHide,
        )
    }
}

/**
 * 弹层内容（抽出来是为了 @Preview 能渲染——ModalBottomSheet 是 Dialog，预览里看不到）。
 */
@Composable
fun GroupPickerSheetContent(
    members: List<GroupPickerMember>,
    selectedIds: Set<String>,
    onToggle: (String) -> Unit,
    onConfirm: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val screenHeight = LocalConfiguration.current.screenHeightDp.dp
    // 拖柄 + 底部安全区约 88dp：先扣掉再卡 80%，保证整层高度不超过屏高 80%
    val maxHeight = (screenHeight * 0.8f - 88.dp).coerceAtLeast(240.dp)

    val selectedCount = selectedIds.size
    val atLimit = selectedCount >= GroupRules.MAX_MEMBERS
    val canConfirm = selectedCount in GroupRules.MIN_MEMBERS..GroupRules.MAX_MEMBERS
    val tooFewMembers = members.size < GroupRules.MIN_MEMBERS

    Column(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(max = maxHeight),
    ) {
        Text(
            text = "想让谁一起来？",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 8.dp),
        )

        val hint = when {
            tooFewMembers -> "至少两个角色，才聊得起来。"
            atLimit -> "最多 4 个，再多聊不过来"
            else -> null
        }
        Spacer(Modifier.height(4.dp))
        // 先说明"不吵 + 成本"，再说人数规则：用户不需要理解群聊策略（文案纪律）
        Text(
            text = "选的角色越多，每条消息越贵；默认每次只有一个 TA 接你的话",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 20.dp),
        )
        if (hint != null) {
            Spacer(Modifier.height(4.dp))
            Text(
                text = hint,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 20.dp),
            )
        }

        if (members.isEmpty()) {
            Text(
                text = "还没有角色。先建一个，再来一起聊。",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 20.dp),
            )
        } else {
            LazyColumn(
                // fill = false：内容少时弹层按内容高度收，内容多时才顶到 80% 上限并内部滚动
                modifier = Modifier.weight(1f, fill = false),
                contentPadding = PaddingValues(vertical = 8.dp),
            ) {
                items(members, key = { it.id }) { member ->
                    val selected = member.id in selectedIds
                    val blocked = atLimit && !selected
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            // 触控目标 ≥ 48dp，这里给到 56dp
                            .heightIn(min = 56.dp)
                            .clickable(enabled = !blocked) { onToggle(member.id) }
                            .padding(horizontal = 20.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            modifier = Modifier
                                .size(32.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.surfaceVariant),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = member.name.take(1),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Spacer(Modifier.width(12.dp))
                        Text(
                            text = member.name,
                            style = MaterialTheme.typography.labelLarge,
                            color = if (blocked) {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            } else {
                                MaterialTheme.colorScheme.onBackground
                            },
                            modifier = Modifier.weight(1f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Checkbox(
                            checked = selected,
                            onCheckedChange = null,
                            enabled = !blocked,
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(4.dp))
        Button(
            onClick = onConfirm,
            enabled = canConfirm,
            shape = RoundedCornerShape(999.dp),
            colors = ButtonDefaults.buttonColors(
                // 主按钮黑底白字，不用强调色
                containerColor = MaterialTheme.colorScheme.onBackground,
                contentColor = MaterialTheme.colorScheme.background,
                disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            ),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 12.dp)
                .height(48.dp),
        ) {
            Text(
                text = "和这 $selectedCount 个角色一起聊",
                style = MaterialTheme.typography.labelLarge,
            )
        }
    }
}

// ---------------------------------------------------------------- 预览用假状态

private val previewMembers = listOf(
    GroupPickerMember(id = "m1", name = "阿淮"),
    GroupPickerMember(id = "m2", name = "林野"),
    GroupPickerMember(id = "m3", name = "小满"),
    GroupPickerMember(id = "m4", name = "老周"),
    GroupPickerMember(id = "m5", name = "阿蓝"),
)

@Preview(showBackground = true, widthDp = 360, heightDp = 720)
@Composable
private fun GroupPickerSheetPreview() {
    PulsezTheme(darkTheme = false) {
        Surface(color = MaterialTheme.colorScheme.background) {
            GroupPickerSheetContent(
                members = previewMembers,
                selectedIds = setOf("m1", "m2"),
                onToggle = {},
                onConfirm = {},
            )
        }
    }
}

@Preview(showBackground = true, widthDp = 360, heightDp = 720)
@Composable
private fun GroupPickerSheetAtLimitPreview() {
    PulsezTheme(darkTheme = true) {
        Surface(color = MaterialTheme.colorScheme.background) {
            GroupPickerSheetContent(
                members = previewMembers,
                selectedIds = setOf("m1", "m2", "m3", "m4"),
                onToggle = {},
                onConfirm = {},
            )
        }
    }
}
