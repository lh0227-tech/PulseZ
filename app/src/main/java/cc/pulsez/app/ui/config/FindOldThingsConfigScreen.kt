package cc.pulsez.app.ui.config

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import cc.pulsez.app.ui.components.PulsezPrimaryButton

/**
 * 一个「找旧事」示例：只填找旧事地址 + 找旧事模型，和聊天示例是两套
 * 。
 */
internal data class EmbedExample(val name: String, val baseUrl: String, val model: String)

/**
 * 「找旧事」示例表（v0.5.1 送检前只留 4 个：智谱 / 硅基流动 / 阿里百炼 / OpenAI）。
 *
 * 智谱 `embedding-3` 排第一（用户指令 2026-11-21）。
 *
 * 地址与模型名逐字来自各家官方文档（2026-11-21 复核）：
 * - 智谱：官方接口 `POST https://open.bigmodel.cn/api/paas/v4/embeddings`，model 枚举
 *   `embedding-3`；[模型页](https://docs.bigmodel.cn/cn/guide/models/embedding/embedding-3)、
 *   [接口页](https://docs.bigmodel.cn/api-reference/%%%%%9E%8B-api/%%96%87%%9C%AC%%%8C%%85%)
 *   （单请求最多 64 条，覆盖 App 的 16 条/批）。这里填到 `/paas/v4` 这一层，
 *   App 会在后面拼 `/embeddings`（`net/BaseUrl.kt`）。
 * - 硅基流动：官方接口 `POST https://api.siliconflow.cn/v1/embeddings`，
 *   [API 手册](https://api-docs.siliconflow.cn/docs/api/embeddings-post)；模型名 `BAAI/bge-m3`
 *   逐字取自官方定价页（大小写/斜杠不能改）。
 * - 阿里百炼：官方 OpenAI 兼容接口
 *   [中文文档](https://help.aliyun.com/zh/model-studio/embedding-interfaces-compatible-with-openai)，
 *   `POST /compatible-mode/v1/embeddings`；模型 `qwen3.7-text-embedding` 单请求最多 20 行
 *   （≥ App 的 16 条/批）。故意不放 `text-embedding-v4`/`v3`：官方单请求上限 10 行 < 16。
 * - OpenAI：官方指南
 *   [Embeddings](https://developers.openai.com/api/docs/guides/embeddings) 的 `text-embedding-3-small`
 *   与 `POST https://api.openai.com/v1/embeddings`；国内直连可用性未实测，
 *   所以 chip 上不写连通性结论。
 *
 * 不再内置「本地 Ollama」示例（v0.5.1 送检前修正）：手机连电脑上的 Ollama 只能填明文 http
 * 局域网地址，系统与 `res/xml/network_security_config.xml` 都不放行，做不通；原因与用户可见措辞
 * 见 [OllamaCopy.NOT_BUILT_IN]（说明放在聊天页，这一页不再保留 Ollama 相关文案）。
 *
 * 硬约束（`ConfigEmbeddingTest` 逐字锁定）：
 * - 来源是 `internal`，让纯 JVM 测试能逐字断言（Compose 字面量没法反射收集）；
 * - 不含任何聊天模型（`deepseek-flash` / `qwen-plus` / `glm-4-plus` …）；
 * - 不含 DeepSeek / Kimi：官方文档没有 embeddings 端点；
 * - chip 名只写服务商名，不写"免费/推荐/可能连不上"这类未实测或会变的结论
 *   。
 */
internal object EmbedExamples {
    val ALL: List<EmbedExample> = listOf(
        // 用户指令 2026-11-21：内置示例第一项必须是智谱 embedding-3。
        EmbedExample(
            name = "智谱",
            baseUrl = "https://open.bigmodel.cn/api/paas/v4",
            model = "embedding-3",
        ),
        EmbedExample(
            name = "硅基流动",
            baseUrl = "https://api.siliconflow.cn/v1",
            model = "BAAI/bge-m3",
        ),
        EmbedExample(
            name = "阿里百炼",
            baseUrl = "https://dashscope.aliyuncs.com/compatible-mode/v1",
            model = "qwen3.7-text-embedding",
        ),
        EmbedExample(
            name = "OpenAI",
            baseUrl = "https://api.openai.com/v1",
            model = "text-embedding-3-small",
        ),
    )
}

/**
 * 「找旧事」独立设置页的用户可见文案（用户指令 2026-11-21：和聊天设置彻底分成两页）。
 *
 * 三问：这是什么、要不要用、不用会怎样，都在
 * [DESCRIPTION] + [OPTIONAL_NOTE] 里答清楚。
 *
 * 禁用词：不出现 token / 上下文 / 窗口 / 向量 /
 * embedding / 记忆检索 / 关键词 / 缓存；角色代词只用 TA。
 * "模型"允许出现在配置页（用户要填模型名的地方 v0.5.0 补充）。
 *
 * 收口成常量的原因同 [EmbedExamples]：纯 JVM 测试逐字断言 + 禁用词检查。
 * 本页不再出现任何 Ollama 文案（v0.5.1 送检前修正：本地 Ollama 示例与说明见聊天页
 * [OllamaCopy.NOT_BUILT_IN]，这一页只保留 4 个可用示例）。
 */
internal object EmbedConfigCopy {
    /** 页面标题：说人话的名字，和设置行「让 TA 更会找旧事」对得上。 */
    const val TITLE = "找旧事"

    /** 第一段：这是什么 / 要不要用 / 可以和聊天同一家（三问的前两问）。 */
    const val DESCRIPTION =
        "这是另配的一个服务：让 TA 按意思找到以前聊过的相关片段，" +
            "不用把全部旧聊天都带上。可选，可以和聊天是同一家，也可以不是。"

    /** 第二段：不用会怎样 + 费用归属，避免用户以为不配就不能用。 */
    const val OPTIONAL_NOTE =
        "不配也能用：只会按原话去找，找得没那么准。配了以后，费用由你填的这家服务收取。"

    /** 示例区标题：说清与聊天模型不是一套（用户反馈的混淆点）。 */
    const val EXAMPLES_TITLE = "找旧事的模型（和聊天用的不是一套，别混填）"

    /** 示例区脚注：只是例子、不是推荐。*/
    const val EXAMPLES_NOTE = "只是快捷填入，不是推荐，可以自己改。"

    /** 明确点出哪两家没有这个能力，免得用户白试聊天家的地址。*/
    const val NO_CAPABILITY_HINT = "DeepSeek、Kimi 没有这个能力，要另配一家。"

    /** 密钥说明：与聊天页同口径（密钥存储；review v0.5.2 补「换服务商要重填」）。*/
    const val KEY_NOTE =
        "只存在你的手机里。除了你自己填的这家服务，不会发给任何人。换了服务商要重填这一家的密钥。"

    /** 「测一下找旧事」按钮（有独立可点条件，见 `ConfigUiState.canTestEmbed`）。 */
    const val TEST_BUTTON = "测一下找旧事"

    /** 测试进行中的按钮文案（省略号用 `……`）。*/
    const val TESTING = "正在测……"

    /** 保存按钮：只保存这一页的三格，不碰聊天设置。 */
    const val SAVE = "保存"

    /**
     * 隐私提醒（聊天内容会发给这个服务商）。
     *
     * 只把"要比较的聊天片段"发给用户自己填的这家服务，不是把全部聊天都发出去。
     */
    const val PRIVACY_REMINDER = "提醒：用了这一项，要比较的聊天片段会发给你填的这家服务。"

    /** 全部本页文案，供纯 JVM 测试批量扫禁用词、两页分离约束与「不含 Ollama 文案」断言。 */
    val ALL: List<String> = listOf(
        TITLE,
        DESCRIPTION,
        OPTIONAL_NOTE,
        EXAMPLES_TITLE,
        EXAMPLES_NOTE,
        NO_CAPABILITY_HINT,
        KEY_NOTE,
        TEST_BUTTON,
        TESTING,
        SAVE,
        PRIVACY_REMINDER,
    )
}

/**
 * 「找旧事」独立设置页（用户指令 2026-11-21）。
 *
 * 与 [ConfigScreen]（聊天服务商）彻底分开：
 * - 这里只有找旧事地址 / 密钥 / 模型、找旧事示例、「测一下找旧事」和这一页的隐私提醒；
 * - 不出现任何聊天字段 / 聊天示例 / 聊天测试按钮（`ConfigEmbeddingTest` 在常量层回归）；
 * - 保存只写找旧事的三格（`ConfigViewModel.saveEmbed`），不碰聊天设置。
 *
 * 交互沿用既有页面约定：返回箭头 48dp + `AutoMirrored` 图标、`imePadding`、
 * 整页可滚动、按钮高 48dp；返回手势由 `AppRoot` 的预测性返回统一处理。
 */
@Composable
fun FindOldThingsConfigScreen(
    state: ConfigUiState,
    onBack: () -> Unit,
    onEmbedBaseUrlChange: (String) -> Unit,
    onEmbedApiKeyChange: (String) -> Unit,
    onEmbedModelChange: (String) -> Unit,
    /** 示例 chip：只填找旧事地址 + 找旧事模型，不碰聊天三格，也不碰密钥。 */
    onApplyEmbedExample: (String, String) -> Unit,
    /** 「测一下找旧事」（`ConfigViewModel.testEmbed`，与聊天测试互不影响）。 */
    onTestEmbed: () -> Unit,
    /** 保存找旧事三格（`ConfigViewModel.saveEmbed`）。 */
    onSave: () -> Unit,
    /** 「停止使用找旧事」（H）：二次确认后清地址/模型/密钥/绑定；配置存在时才渲染。 */
    onDisconnectEmbed: () -> Unit,
    /** 升级一次性提示「知道了」（I）：只清提示标记，不改配置。 */
    onAckKeyResetNotice: () -> Unit,
) {
    /** 密钥是否明文显示：与聊天页同款「显示/隐藏」（review 两页同构）。*/
    var showKey by remember { mutableStateOf(false) }
    /** 「停止使用找旧事」的二次确认框（H：不能一键就把可选服务断掉）。 */
    var showDisconnectConfirm by remember { mutableStateOf(false) }

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
                text = EmbedConfigCopy.TITLE,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onBackground,
            )
        }

        Column(modifier = Modifier.padding(horizontal = 20.dp)) {
            Spacer(Modifier.height(8.dp))
            Text(
                text = EmbedConfigCopy.DESCRIPTION,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = EmbedConfigCopy.OPTIONAL_NOTE,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = EmbedConfigCopy.NO_CAPABILITY_HINT,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            // 升级一次性提示（I）：旧密钥没有归属，被迁移删掉了；只在真的发生过迁移时出现，
            // 「知道了」清标记（与聊天页各自的标记分开，不串页）。
            if (state.embedKeyResetNotice) {
                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = CredentialCopy.UPGRADE_NOTICE,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(
                        onClick = onAckKeyResetNotice,
                        modifier = Modifier.heightIn(min = 48.dp),
                    ) {
                        Text(CredentialCopy.UPGRADE_ACK)
                    }
                }
            }
            Spacer(Modifier.height(20.dp))

            OutlinedTextField(
                value = state.embedBaseUrl,
                onValueChange = onEmbedBaseUrlChange,
                label = { Text("地址") },
                placeholder = { Text("https://……") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(12.dp))

            OutlinedTextField(
                value = state.embedApiKey,
                onValueChange = onEmbedApiKeyChange,
                label = { Text("密钥") },
                singleLine = true,
                visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
                trailingIcon = {
                    TextButton(onClick = { showKey = !showKey }) {
                        Text(if (showKey) "隐藏" else "显示")
                    }
                },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Next),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = EmbedConfigCopy.KEY_NOTE,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            // 密钥状态行（F，v0.5.2）：两页都显示，三档派生；这一页没有「清除密钥」按钮，
            // 断开的出路是页底的「停止使用找旧事」（H），所以这里只显示状态。
            Spacer(Modifier.height(4.dp))
            Text(
                text = CredentialCopy.keyStatusText(state.embedKeyStatus),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))

            OutlinedTextField(
                value = state.embedModel,
                onValueChange = onEmbedModelChange,
                label = { Text("模型") },
                placeholder = { Text("填这家服务给的模型名") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(Modifier.height(16.dp))
            Text(
                text = EmbedConfigCopy.EXAMPLES_TITLE,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                EmbedExamples.ALL.take(3).forEach { example ->
                    AssistChip(
                        onClick = { onApplyEmbedExample(example.baseUrl, example.model) },
                        label = { Text(example.name, style = MaterialTheme.typography.bodySmall) },
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                EmbedExamples.ALL.drop(3).forEach { example ->
                    AssistChip(
                        onClick = { onApplyEmbedExample(example.baseUrl, example.model) },
                        label = { Text(example.name, style = MaterialTheme.typography.bodySmall) },
                    )
                }
            }
            Spacer(Modifier.height(4.dp))
            Text(
                text = EmbedConfigCopy.EXAMPLES_NOTE,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            // 「测一下找旧事」：独立可点条件（state.canTestEmbed），只测这一套，不影响聊天。
            Spacer(Modifier.height(16.dp))
            PulsezPrimaryButton(
                onClick = onTestEmbed,
                enabled = state.canTestEmbed,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp),
            ) {
                Text(if (state.embedTesting) EmbedConfigCopy.TESTING else EmbedConfigCopy.TEST_BUTTON)
            }
            state.embedResult?.let { result ->
                Spacer(Modifier.height(12.dp))
                Text(
                    text = result,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (state.embedResultOk) {
                        MaterialTheme.colorScheme.onBackground
                    } else {
                        MaterialTheme.colorScheme.error
                    },
                )
            }

            Spacer(Modifier.height(12.dp))
            Text(
                text = EmbedConfigCopy.PRIVACY_REMINDER,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            // 保存失败/被拒的人话（B/G/L）：留在本页、说清原因与出路，不静默返回。
            state.embedSaveError?.let { error ->
                Spacer(Modifier.height(16.dp))
                Text(
                    text = error,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            Spacer(Modifier.height(28.dp))
            PulsezPrimaryButton(
                onClick = onSave,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp),
            ) {
                Text(EmbedConfigCopy.SAVE)
            }

            // 「停止使用找旧事」（H）：可选服务要能单独撤回。次要样式 + 二次确认，
            // 不把它做成和「保存」同等重的主动作。配置为空时不显示（没有可断的）。
            if (state.hasEmbedKey || state.embedBaseUrl.isNotBlank()) {
                Spacer(Modifier.height(12.dp))
                OutlinedButton(
                    onClick = { showDisconnectConfirm = true },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp),
                ) {
                    Text(CredentialCopy.DISCONNECT_BUTTON)
                }
            }
            Spacer(Modifier.height(40.dp))
        }
    }

    if (showDisconnectConfirm) {
        AlertDialog(
            onDismissRequest = { showDisconnectConfirm = false },
            title = { Text(CredentialCopy.DISCONNECT_DIALOG_TITLE) },
            text = { Text(CredentialCopy.DISCONNECT_DIALOG_TEXT) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDisconnectConfirm = false
                        onDisconnectEmbed()
                    },
                    modifier = Modifier.heightIn(min = 48.dp),
                ) {
                    Text(CredentialCopy.DISCONNECT_CONFIRM)
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showDisconnectConfirm = false },
                    modifier = Modifier.heightIn(min = 48.dp),
                ) {
                    Text(CredentialCopy.DISCONNECT_CANCEL)
                }
            },
        )
    }
}
