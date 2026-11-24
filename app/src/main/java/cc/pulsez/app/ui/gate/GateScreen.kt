package cc.pulsez.app.ui.gate

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import cc.pulsez.app.data.settings.Settings
import cc.pulsez.app.ui.components.PulsezPrimaryButton
import cc.pulsez.app.ui.copy.LegalCopy

/**
 * 首启门槛。
 * 文案严格遵守 说人话、不吓人、不啰嗦。
 */
@Composable
fun GateScreen(
    settings: Settings,
    onAgree: () -> Unit,
    onUnderage: () -> Unit,
    onOpenAgreement: () -> Unit = {},
) {
    var showUnderage by remember { mutableStateOf(false) }

    if (showUnderage) {
        UnderageNotice(onBack = { showUnderage = false })
        return
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 32.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = "在开始之前",
            style = MaterialTheme.typography.displayLarge,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Spacer(Modifier.height(16.dp))
        Text(
            // v0.4 （附录 A 4.2）：只说 AI 身份 + 控制权，不承诺记忆。
            text = "这里没有真人。和你说话的是一个 AI。\n怎么聊、记不记得，你说了算。",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Spacer(Modifier.height(12.dp))
        Text(
            text = "TA 会用你填写的 AI 服务。你发的文字会送到服务商；服务商怎么保存和使用，由服务商自己的政策决定。费用由你自己承担。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(36.dp))

        PulsezPrimaryButton(onClick = onAgree, modifier = Modifier.fillMaxWidth().height(48.dp)) {
            Text("我了解，并且已满 18 岁")
        }
        Spacer(Modifier.height(12.dp))
        OutlinedButton(
            onClick = { showUnderage = true },
            modifier = Modifier.fillMaxWidth().height(48.dp),
        ) {
            Text("我不满 18 岁")
        }
        Spacer(Modifier.height(24.dp))
        // 协议版本摘要（v0.5.2 E）：
        // 升级与全新安装都显示，不加逻辑分支；**直接引用唯一常量**，不得在这里复制第二份字面。
        // 用户点进协议前就能看到「这一版改了什么」，不用先过门槛再回头翻。
        Text(
            text = LegalCopy.AGREEMENT_DIGEST,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(4.dp))
        TextButton(onClick = onOpenAgreement) {
            Text(
                text = "服务协议 · 隐私说明",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun UnderageNotice(onBack: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp, vertical = 32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.Start,
    ) {
        Text(
            text = "这里不适合你",
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Spacer(Modifier.height(12.dp))
        Text(
            text = "这里提供的是陪伴型对话，仅面向 18 岁以上。\n未满 18 岁暂时不能使用。",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(28.dp))
        OutlinedButton(onClick = onBack, modifier = Modifier.height(48.dp)) {
            Text("返回")
        }
    }
}
