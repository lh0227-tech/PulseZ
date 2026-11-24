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
import androidx.compose.material3.AssistChip
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
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import cc.pulsez.app.ui.components.PulsezPrimaryButton

/** 聊天示例：只填聊天地址 + 聊天模型（不碰找旧事三格）。`internal` 供纯 JVM 测试逐字断言。 */
internal data class Example(val name: String, val baseUrl: String, val model: String)

/**
 * 聊天示例表（v0.5.1 送检前只留 4 个：DeepSeek / 硅基流动 / 阿里百炼 / 智谱）。
 *
 * 不再内置「本地 Ollama」示例：手机连电脑上的 Ollama 只能填明文 http 局域网地址，而系统与
 * `res/xml/network_security_config.xml` 都不放行，点了「测一下」必然失败（原因与措辞见
 * [OllamaCopy.NOT_BUILT_IN]）。自建 https 反向代理的用户可以手动填地址。
 */
internal val chatExamples = listOf(
    Example("DeepSeek", "https://api.deepseek.com", "deepseek-flash"),
    Example("硅基流动", "https://api.siliconflow.cn/v1", "deepseek-ai/DeepSeek-V3"),
    Example("阿里百炼", "https://dashscope.aliyuncs.com/compatible-mode/v1", "qwen-plus"),
    Example("智谱", "https://open.bigmodel.cn/api/paas/v4", "glm-4-plus"),
)

/**
 * 「聊天服务商」设置页的用户可见文案（用户指令 2026-11-21：和找旧事设置彻底分成两页）。
 *
 * 三问：这是什么、要不要用、不用会怎样。
 * 本页只解释聊天服务商：不出现任何「找旧事」字段 / 示例 / 按钮 / 说明。
 * 禁用词：不出现 token / 上下文 / 窗口 / 向量 / embedding / 记忆检索 /
 * 关键词；"模型"按 v0.5.0 补充只允许出现在配置页（这里就是配置页）。
 *
 * 收口成常量的原因同 [EmbedConfigCopy]：纯 JVM 测试能逐字断言 + 扫禁用词，并能断言
 * 「聊天页文案里没有找旧事」这一分离约束（Compose 字面量没法反射收集）。
 */
internal object ChatConfigCopy {
    /** 页面标题（沿用 定稿，和设置行「聊天」对得上）。*/
    const val TITLE = "连接你的 AI"

    /** 第一段：说清三格要自己填、不预设服务商。 */
    const val INTRO = "这三格要你自己填。这个应用不预设服务商，也不绑定模型。"

    /** 密钥下方：聊天内容只会发给用户自己填的这一家。 */
    const val KEY_NOTE =
        "只存在你的手机里。除了你自己填的这个服务商，不会发给任何人。换了服务商要重填这一家的密钥。"

    /** 保存成功（v0.5.2：只有三步协议 + 回读校验通过才可能看到）。*/
    const val SAVE_SUCCESS = "存好了。"

    /** 示例区说明：只是例子，不是推荐。 */
    const val EXAMPLES_NOTE = "几个例子，点一下就填进去。只是例子，不是推荐。"

    /** 聊天测试按钮。 */
    const val TEST = "测一下"

    /** 聊天测试进行中的按钮文案（省略号用 `……`）。*/
    const val TESTING = "正在测……"

    /**
     * 聊天测试成功文案（定稿）。
     *
     * 由本页常量统一给出，不透传探测层的原始结论：探测层的成功附注（"这家找旧事没那么准"）
     * 属于另一个页面（用户指令 2026-11-21 彻底分开），不能出现在聊天页；聊天测试现在也
     * 不再顺带探测找旧事端点。
     */
    const val TEST_SUCCESS = "连上了。可以开始了。"

    /** 保存按钮：只保存聊天三格（`ConfigViewModel.saveChat`）。 */
    const val SAVE = "保存"

    /**
     * 「深度思考 / 智能搜索」只对 DeepSeek 官方地址有效。
     *
     * 非 DeepSeek 端点时在本页说一句原因，避免用户以为胶囊丢了；
     * 原来这句放在设置「连接」组，拆分后跟着聊天设置走，不再贴在找旧事行下面。
     */
    const val DEEPSEEK_ONLY_HINT = "聊天框里的「深度思考」「智能搜索」只对 DeepSeek 的地址有效。"

    /** 全部本页文案，供纯 JVM 测试批量扫禁用词与分离约束。 */
    val ALL: List<String> = listOf(
        TITLE,
        INTRO,
        KEY_NOTE,
        EXAMPLES_NOTE,
        TEST,
        TESTING,
        TEST_SUCCESS,
        SAVE,
        SAVE_SUCCESS,
        DEEPSEEK_ONLY_HINT,
        // 本地 Ollama 为什么不给示例（v0.5.1 送检前修正；不再写 OLLAMA_HOST 那套走不通的说明）。
        OllamaCopy.NOT_BUILT_IN,
    )
}

/**
 * 聊天服务商设置页（用户指令 2026-11-21：从原「连接页」拆出，只保留聊天相关）。
 *
 * 铁律：不预设任何服务商、任何模型。示例点一下只是填进输入框，不是推荐。
 *
 * 拆分说明：
 * - 原来这里还有一块「找旧事（另配一个服务）」折叠区，用户反馈和聊天设置混在一起很容易填错；
 * - 现在找旧事有独立页面 [FindOldThingsConfigScreen] 和独立路由（`Route.FindOldThings`）；
 * - 本页**不得出现**任何找旧事字段 / 示例 / 按钮 / 说明（`ConfigEmbeddingTest` 在常量层回归）。
 */
@Composable
fun ConfigScreen(
    state: ConfigUiState,
    /** 当前保存的聊天地址是不是 DeepSeek 官方端点：决定是否显示「只对 DeepSeek 有效」说明。 */
    deepSeekProvider: Boolean,
    onBack: () -> Unit,
    onBaseUrlChange: (String) -> Unit,
    onApiKeyChange: (String) -> Unit,
    onModelChange: (String) -> Unit,
    /** 聊天示例 chip：只填聊天地址 + 聊天模型，不碰找旧事三格。 */
    onApplyExample: (String, String) -> Unit,
    onTest: () -> Unit,
    /** 保存聊天三格（`ConfigViewModel.saveChat`），不碰找旧事设置。 */
    onSave: () -> Unit,
    /** 「清除密钥」（E）：清聊天密钥 + 绑定 + 旧探测结论；只有 `hasChatKey` 时渲染。 */
    onClearChatKey: () -> Unit,
    /** 升级一次性提示「知道了」（I）：只清提示标记，不改配置。 */
    onAckKeyReset: () -> Unit,
) {
    var showKey by remember { mutableStateOf(false) }

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
                text = ChatConfigCopy.TITLE,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onBackground,
            )
        }

        Column(modifier = Modifier.padding(horizontal = 20.dp)) {
            Spacer(Modifier.height(8.dp))
            Text(
                text = ChatConfigCopy.INTRO,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(20.dp))

            // 升级一次性提示（I）：v0.5.0/v0.5.1 的旧密钥没有归属，被迁移删掉了；
            // 只在真的发生过迁移时出现，「知道了」清标记。放在最前，用户填密钥前就看到。
            if (state.keyResetNotice) {
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
                        onClick = onAckKeyReset,
                        modifier = Modifier.heightIn(min = 48.dp),
                    ) {
                        Text(CredentialCopy.UPGRADE_ACK)
                    }
                }
                Spacer(Modifier.height(8.dp))
            }

            OutlinedTextField(
                value = state.baseUrl,
                onValueChange = onBaseUrlChange,
                label = { Text("地址") },
                placeholder = { Text("https://……") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(12.dp))

            OutlinedTextField(
                value = state.apiKey,
                onValueChange = onApiKeyChange,
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
                text = ChatConfigCopy.KEY_NOTE,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            // 密钥状态行（F，v0.5.2）：由绑定状态派生（可用 / 要重填一次 / 还没有），
            // 不是布尔凑的；可用时只显示主机名，不显示完整地址/路径/userinfo。
            // 「清除密钥」（E）只在真的存过密钥时出现——没有密钥还显示「清除」是空动作。
            Spacer(Modifier.height(4.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = CredentialCopy.keyStatusText(state.chatKeyStatus),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                if (state.hasChatKey) {
                    TextButton(
                        onClick = onClearChatKey,
                        modifier = Modifier.heightIn(min = 48.dp),
                    ) {
                        Text(CredentialCopy.CLEAR_KEY_BUTTON)
                    }
                }
            }
            Spacer(Modifier.height(12.dp))

            OutlinedTextField(
                value = state.model,
                onValueChange = onModelChange,
                label = { Text("模型") },
                placeholder = { Text("填这个服务商给的模型名") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(Modifier.height(16.dp))
            Text(
                text = ChatConfigCopy.EXAMPLES_NOTE,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                chatExamples.take(3).forEach { example ->
                    AssistChip(
                        onClick = { onApplyExample(example.baseUrl, example.model) },
                        label = { Text(example.name, style = MaterialTheme.typography.bodySmall) },
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                chatExamples.drop(3).forEach { example ->
                    AssistChip(
                        onClick = { onApplyExample(example.baseUrl, example.model) },
                        label = { Text(example.name, style = MaterialTheme.typography.bodySmall) },
                    )
                }
            }

            // 本地 Ollama 为什么不在示例里（v0.5.1 送检前修正）：旧说明教用户把电脑放开监听、
            // 手机填局域网 IP，但明文 http 被系统与 network_security_config 拦下，做不通；
            // 改成一句如实说明 + https 反向代理的出路。
            Spacer(Modifier.height(8.dp))
            Text(
                text = OllamaCopy.NOT_BUILT_IN,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(24.dp))
            PulsezPrimaryButton(
                onClick = onTest,
                enabled = state.canTest,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp),
            ) {
                Text(if (state.testing) ChatConfigCopy.TESTING else ChatConfigCopy.TEST)
            }

            state.result?.let { result ->
                Spacer(Modifier.height(12.dp))
                Text(
                    text = result,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (state.resultOk) {
                        MaterialTheme.colorScheme.onBackground
                    } else {
                        MaterialTheme.colorScheme.error
                    },
                )
            }

            // 思考 / 搜索是 DeepSeek 私有能力：其它服务商不显示胶囊，这里说明一句原因，
            // 避免用户以为开关丢了。依据 把它归到聊天设置页。
            if (!deepSeekProvider) {
                Spacer(Modifier.height(16.dp))
                Text(
                    text = ChatConfigCopy.DEEPSEEK_ONLY_HINT,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            // 保存失败/被拒的人话（B/G/L/M）：留在本页、说清原因与出路，不静默返回。
            state.saveError?.let { error ->
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
                Text(ChatConfigCopy.SAVE)
            }
            Spacer(Modifier.height(40.dp))
        }
    }
}
