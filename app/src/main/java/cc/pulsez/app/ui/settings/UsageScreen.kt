package cc.pulsez.app.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import cc.pulsez.app.ui.copy.VoiceCopy
import java.util.Locale

/**
 * 用量与花费。
 * 上限默认"不限制"，用户填数字才生效；改完立刻生效。
 */
@Composable
fun UsageScreen(
    state: UsageUiState,
    onBack: () -> Unit,
    onDailyLimit: (Int) -> Unit,
    onMonthlyLimit: (Int) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .imePadding()
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
                text = "用量与花费",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onBackground,
            )
        }

        Column(modifier = Modifier.padding(horizontal = 20.dp)) {
            Spacer(Modifier.height(12.dp))
            Text(
                text = "这个月 ${formatUsageYuan(state.snapshot.monthYuan)}",
                style = MaterialTheme.typography.displayLarge,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = "预估。实际以服务商账单为准。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(24.dp))
            Text(
                text = "今天 ${state.snapshot.todayMessages} 条消息，花了 ${formatUsageYuan(state.snapshot.todayYuan)}",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onBackground,
            )
            if (state.snapshot.savedRatio > 0) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "比每次都读全部旧聊天，省下约 ${(state.snapshot.savedRatio * 100).toInt()}%",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            // v0.5 识图：单列「图片」小项（验收 6）。
            // 数值是预估（每张最多 1024 输入 token），文案必须带"预估、以账单为准"，
            // 不能让用户以为是精确账单（文案纪律：不制造假数字）。
            if (state.snapshot.todayImageYuan > 0.0) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "其中图片 ¥%.4f（预估，以服务商账单为准）".format(state.snapshot.todayImageYuan),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            // v0.5.1 语音：与「其中图片」同构的小项，只在今天真的花过语音钱时出现（> 0）。
            // 金额按服务端返回的音频时长预估（`Pricing.asrEstimateYuan`），标「预估、以账单为准」，
            // 不能让用户以为是精确账单（` 数字纪律；` 分类记账口径）。
            if (state.snapshot.todayVoiceYuan > 0.0) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = VoiceCopy.voiceUsageLine(state.snapshot.todayVoiceYuan),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Spacer(Modifier.height(32.dp))
            Text(
                text = "要不要给自己定个上限？",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = "到了上限就会暂停发新消息，不会继续扣费；不填就是不限制。改完马上生效。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            LimitRow(
                title = "每天上限",
                yuan = state.dailyLimitYuan,
                onApply = onDailyLimit,
            )
            LimitRow(
                title = "每月上限",
                yuan = state.monthlyLimitYuan,
                onApply = onMonthlyLimit,
            )

            state.message?.let {
                Spacer(Modifier.height(12.dp))
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.height(48.dp))
        }
    }
}

@Composable
private fun LimitRow(title: String, yuan: Int, onApply: (Int) -> Unit) {
    var text by remember(yuan) { mutableStateOf(if (yuan <= 0) "" else yuan.toString()) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.weight(1f),
        )
        OutlinedTextField(
            value = text,
            onValueChange = { input -> text = input.filter { it.isDigit() }.take(5) },
            singleLine = true,
            label = { Text("不限") },
            suffix = { Text("元") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.size(width = 140.dp, height = 64.dp),
        )
        TextButton(onClick = { onApply(text.toIntOrNull() ?: 0) }) {
            Text("保存上限")
        }
    }
}

private fun formatUsageYuan(yuan: Double): String =
    if (yuan >= 100.0) {
        "¥%.0f".format(Locale.CHINA, yuan)
    } else {
        "¥%.2f".format(Locale.CHINA, yuan)
    }
