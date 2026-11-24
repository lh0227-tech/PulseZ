package cc.pulsez.app.ui.config

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import cc.pulsez.app.AppContainer
import cc.pulsez.app.data.settings.Settings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * 连接配置（v0.5.0 后拆成两页：聊天 [ConfigScreen] / 找旧事 [FindOldThingsConfigScreen]）。
 *
 * 不预设任何服务商与模型：每个框全部由用户填，示例只是"点一下填进去"的快捷方式。
 *
 * 拆分后的取舍（用户指令 2026-11-21）：仍然**一个 ViewModel** 管两个 section，
 * 因为设置存储本来就分 chat/embed 两组、字段互不共享；拆两个 ViewModel 会让
 * 共享的测试状态与工厂接线翻倍，收益只是文件更小。UI 与路由是两个页面，
 * 保存也分成 [ConfigViewModel.saveChat] / [ConfigViewModel.saveEmbed] 两条路径。
 *
 * v0.5.2：保存变成 **suspend + Boolean**，只有三步协议
 * 回读校验通过才返回 true（UI 据此决定 `nav.back()`）；判定一律用 `settings.flow` 的
 * **持久值**（[current]），不用本页 UI state 当依据。
 */
data class ConfigUiState(
    val baseUrl: String = "",
    val apiKey: String = "",
    val model: String = "",
    val embedBaseUrl: String = "",
    val embedApiKey: String = "",
    val embedModel: String = "",
    val testing: Boolean = false,
    val result: String? = null,
    val resultOk: Boolean = false,
    val saved: Boolean = false,
    /** 「测一下找旧事」进行中（与聊天的 [testing] 分开，互不影响）。 */
    val embedTesting: Boolean = false,
    /** 「测一下找旧事」的结果文案；null = 还没测过。 */
    val embedResult: String? = null,
    /** 「测一下找旧事」是否连上了（决定 [embedResult] 用正常色还是错误色）。 */
    val embedResultOk: Boolean = false,
    /** 聊天页保存失败/被拒的人话（B/G/L/M）；null = 没有错误。 */
    val saveError: String? = null,
    /** 找旧事页保存/断开失败的人话；null = 没有错误。 */
    val embedSaveError: String? = null,
    /**
     * 持久化的「聊天密钥是否已配置」镜像（**只由 settings.flow 回填**，不是本页输入状态）。
     *
     * v0.5.2 起密钥按服务商绑定（[chatKeyHost]），是否真的能用由 [chatKeyStatus] 派生。
     */
    val hasChatKey: Boolean = false,
    /** 持久化的聊天密钥归属服务商 origin（空 = 归属未知）。 */
    val chatKeyHost: String = "",
    /** 持久化的「找旧事密钥是否已配置」镜像。 */
    val hasEmbedKey: Boolean = false,
    /** 持久化的找旧事密钥归属服务商 origin（空 = 归属未知）。 */
    val embedKeyHost: String = "",
    /** 聊天页密钥状态行（F：可用 / 要重填一次 / 还没有）。 */
    val chatKeyStatus: KeyStatus = KeyStatus.None,
    /** 找旧事页密钥状态行（F）。 */
    val embedKeyStatus: KeyStatus = KeyStatus.None,
    /** 升级迁移的一次性提示：「这一版把密钥改成跟着服务商走，要重填一次。」（I） */
    val keyResetNotice: Boolean = false,
    /** 找旧事页的升级一次性提示（与聊天分开，避免一页的提示串到另一页）。 */
    val embedKeyResetNotice: Boolean = false,
) {
    val canTest: Boolean
        get() = baseUrl.isNotBlank() && apiKey.isNotBlank() && model.isNotBlank() && !testing

    /**
     * 「测一下找旧事」可点条件：找旧事地址、密钥、模型都非空。
     *
     * 注意与 [saveEmbed] 的差异：保存允许密钥留空（沿用**同一服务商**的旧密钥），但
     * **测试必须当场有密钥**（服务端要鉴权，没密钥必然失败，让按钮可点只会浪费用户一次点击）。
     */
    val canTestEmbed: Boolean
        get() = embedBaseUrl.isNotBlank() &&
            embedApiKey.isNotBlank() &&
            embedModel.isNotBlank() &&
            !embedTesting
}

class ConfigViewModel(
    private val container: AppContainer,
    private val tester: ConnectionTester,
) : ViewModel() {

    private val _state = MutableStateFlow(ConfigUiState())
    val state: StateFlow<ConfigUiState> = _state.asStateFlow()

    /**
     * 最近一次从 `settings.flow` 读到的**持久值**。
     *
     * v0.5.2 的判定（是否已有密钥、旧密钥属于哪家、要不要给「清除」出路）只读它，
     * **不用 UI state 猜**（`禁止项「禁止用 UI state 当判定依据」）。
     */
    private var current: Settings = Settings()

    init {
        viewModelScope.launch {
            container.settings.flow.collect { settings ->
                current = settings
                applyPersisted(settings)
            }
        }
    }

    fun onBaseUrlChange(value: String) = update { copy(baseUrl = value, saved = false) }
    fun onApiKeyChange(value: String) = update { copy(apiKey = value, saved = false) }
    fun onModelChange(value: String) = update { copy(model = value, saved = false) }
    fun onEmbedBaseUrlChange(value: String) = update { copy(embedBaseUrl = value, embedSaveError = null) }
    fun onEmbedApiKeyChange(value: String) = update { copy(embedApiKey = value, embedSaveError = null) }
    fun onEmbedModelChange(value: String) = update { copy(embedModel = value, embedSaveError = null) }

    /**
     * 聊天示例 chip：只填聊天地址 + 聊天模型，**不碰找旧事三格**。
     *
     * 拆分后聊天与找旧事是两个页面（用户指令 2026-11-21）；纯映射抽成 [applyChatExampleTo]，
     * 便于纯 JVM 测试断言"两页字段不串"。
     */
    fun applyExample(baseUrl: String, model: String) {
        update { applyChatExampleTo(this, baseUrl, model) }
    }

    /**
     * 「找旧事」示例 chip：只填找旧事地址 + 找旧事模型，**不碰聊天三格**。
     *
     * 依据「点一下只填'地址 + 模型'」；`（找旧事是独立服务）。
     * 纯映射逻辑抽成 [applyEmbedExampleTo]，便于纯 JVM 测试（本 ViewModel 依赖 Android 容器）。
     */
    fun applyEmbedExample(baseUrl: String, model: String) {
        update { applyEmbedExampleTo(this, baseUrl, model) }
    }

    /**
     * 「测一下找旧事」：复用 `ConnectionTester.testEmbeddings`（`ProbeTester` 已实现），
     * 不新增网络依赖、不改聊天测试路径。
     */
    fun testEmbed() {
        val s = _state.value
        if (!s.canTestEmbed) return
        viewModelScope.launch {
            _state.value = _state.value.copy(embedTesting = true, embedResult = null)
            val ok = runCatching {
                tester.testEmbeddings(s.embedBaseUrl.trim(), s.embedApiKey.trim(), s.embedModel.trim())
            }.getOrElse {
                // 没测成（网络/解析等异常）也走同一句"没连上"，不让用户对着英文异常发愣。
                ConnectionTestResult(chatOk = false, embeddingsOk = false, message = "")
            }.embeddingsOk
            _state.value = _state.value.copy(
                embedTesting = false,
                embedResultOk = ok,
                embedResult = if (ok) EmbedTestCopy.SUCCESS else EmbedTestCopy.FAILURE,
            )
        }
    }

    fun test() {
        val s = _state.value
        if (!s.canTest) return
        viewModelScope.launch {
            _state.value = _state.value.copy(testing = true, result = null)
            val result = runCatching {
                tester.testChat(s.baseUrl.trim(), s.apiKey.trim(), s.model.trim())
            }.getOrElse {
                ConnectionTestResult(false, false, "没测成功。检查一下网络，或者换一个地址试试。")
            }
            _state.value = _state.value.copy(
                testing = false,
                // 成功时用本页常量，不透传探测层的原始文案：探测层的成功附注属于找旧事页
                // （用户指令 2026-11-21）；聊天测试也不再顺带探找旧事端点
                // ，聊天页不能出现那边的结论。
                result = if (result.chatOk) ChatConfigCopy.TEST_SUCCESS else result.message,
                resultOk = result.chatOk,
            )
        }
    }

    /**
     * 保存**聊天**三格（和找旧事彻底分成两页；v0.5.2 三步协议）。
     *
     * 只写聊天地址 / 模型 / 密钥；**不碰**找旧事的三格与密钥（找旧事用 [saveEmbed]）。
     *
     * @return true 只在「三步协议 + 回读校验」都通过后返回；UI 据此才 `nav.back()`。
     *         失败/被拒一律留在本页显示人话（B/G/L/M），**不写任何字段**或按协议回滚为不可用。
     */
    suspend fun saveChat(): Boolean {
        val s = _state.value
        val baseUrl = s.baseUrl.trim()
        val model = s.model.trim()
        val keyInput = s.apiKey.trim()
        // 判定只用持久值：采集器可能还没收到第一帧，这里先同步读一次（同时刷新 [current]）。
        syncCurrent()
        // 聊天页地址/模型都要有：模型空时不写、不假成功（与找旧事页 G 同一口径；
        // 地址空交给协议判 BadOrigin（M），先给「地址要填完整」而不是「都没填」）。
        if (baseUrl.isNotBlank() && model.isBlank()) {
            _state.value = _state.value.copy(saved = false, saveError = CredentialCopy.INCOMPLETE)
            return false
        }
        val result = runCatching { container.saveChatCredential(baseUrl, model, keyInput) }
            .getOrElse { CredentialSaveResult.FAILED }
        return when (result) {
            CredentialSaveResult.SAVED -> {
                // v0.5.2：换域/存新密钥后旧探测结论失效（不新增按 origin 缓存机制）。
                runCatching { container.settings.clearVisionProbe() }
                // 用户已经重填过了：升级一次性提示不再需要。
                runCatching { container.settings.setChatKeyResetAfterUpgrade(false) }
                _state.value = _state.value.copy(
                    saved = true,
                    apiKey = "",
                    saveError = null,
                    result = ChatConfigCopy.SAVE_SUCCESS,
                    resultOk = true,
                )
                refreshPersisted()
                true
            }
            CredentialSaveResult.BAD_ORIGIN -> {
                _state.value = _state.value.copy(
                    saved = false,
                    saveError = CredentialCopy.badOrigin(forEmbed = false, hasKey = current.hasChatKey),
                    result = null,
                    resultOk = false,
                )
                false
            }
            CredentialSaveResult.REQUIRE_NEW_KEY -> {
                _state.value = _state.value.copy(
                    saved = false,
                    saveError = CredentialCopy.REQUIRE_NEW_KEY_CHAT,
                    result = null,
                    resultOk = false,
                )
                false
            }
            CredentialSaveResult.FAILED -> {
                _state.value = _state.value.copy(
                    saved = false,
                    saveError = CredentialCopy.SAVE_FAILED,
                    result = null,
                    resultOk = false,
                )
                false
            }
        }
    }

    /**
     * 保存**找旧事**三格（v0.5.2 三步协议；v0.5.3 方案丙）。
     *
     * 只写找旧事地址 / 模型 / 密钥；**不碰**聊天三格与聊天密钥（聊天用 [saveChat]）。
     * 地址或模型留空 → 报「要填全」、**不写、不假成功**。**只缺密钥不再一律拒绝**：
     * 从未配置过密钥时按场景 D 允许先写地址 + 模型（[KeySaveDecision.NoKeyYet]），
     * 设置状态行随后如实显示「地址已存，还没填密钥」（v0.5.3 方案丙，`）；
     * 换了服务商再留空密钥仍按协议要求重填、不写（[KeySaveDecision.RequireNewKey]）。
     */
    suspend fun saveEmbed(): Boolean {
        val s = _state.value
        val baseUrl = s.embedBaseUrl.trim()
        val model = s.embedModel.trim()
        val keyInput = s.embedApiKey.trim()
        syncCurrent()
        if (baseUrl.isBlank() || model.isBlank()) {
            _state.value = _state.value.copy(
                saved = false,
                embedSaveError = CredentialCopy.INCOMPLETE,
                embedResult = null,
                embedResultOk = false,
            )
            return false
        }
        val result = runCatching { container.saveEmbedCredential(baseUrl, model, keyInput) }
            .getOrElse { CredentialSaveResult.FAILED }
        return when (result) {
            CredentialSaveResult.SAVED -> {
                runCatching { container.settings.setEmbedKeyResetAfterUpgrade(false) }
                _state.value = _state.value.copy(
                    saved = true,
                    embedApiKey = "",
                    embedSaveError = null,
                    embedResult = null,
                    embedResultOk = false,
                )
                refreshPersisted()
                true
            }
            CredentialSaveResult.BAD_ORIGIN -> {
                _state.value = _state.value.copy(
                    saved = false,
                    embedSaveError = CredentialCopy.badOrigin(forEmbed = true, hasKey = current.hasEmbedKey),
                    embedResult = null,
                    embedResultOk = false,
                )
                false
            }
            CredentialSaveResult.REQUIRE_NEW_KEY -> {
                _state.value = _state.value.copy(
                    saved = false,
                    embedSaveError = CredentialCopy.REQUIRE_NEW_KEY_EMBED,
                    embedResult = null,
                    embedResultOk = false,
                )
                false
            }
            CredentialSaveResult.FAILED -> {
                _state.value = _state.value.copy(
                    saved = false,
                    embedSaveError = CredentialCopy.SAVE_FAILED,
                    embedResult = null,
                    embedResultOk = false,
                )
                false
            }
        }
    }

    /**
     * 「清除密钥」（E）：清聊天密钥 + 绑定 + 升级提示；清探测结论与本页旧测试结果。
     *
     * 聊天/语音门禁随即关闭（读路径只认 `bindingState` + 密文）。返回值决定是否给成功轻提示；
     * 失败不静默（本页显示「没清掉，再试一次。」）。
     */
    suspend fun clearChatKey(): Boolean {
        val ok = runCatching { container.clearChatCredential() }.getOrDefault(false)
        if (ok) {
            runCatching { container.settings.clearVisionProbe() }
            _state.value = _state.value.copy(
                apiKey = "",
                saved = false,
                saveError = null,
                result = null,
                resultOk = false,
            )
        } else {
            _state.value = _state.value.copy(saved = false, saveError = CredentialCopy.CLEAR_FAILED)
        }
        refreshPersisted()
        return ok
    }

    /**
     * 「停止使用找旧事」（H）：清地址/模型/密钥/绑定 + 升级提示；清本页旧测试结果。
     *
     * 断开后 `embedBaseUrl` 为空，`ChatEngine` 的检索/索引零出网；关键词检索不受影响。
     */
    suspend fun disconnectEmbed(): Boolean {
        val ok = runCatching { container.disconnectEmbedCredential() }.getOrDefault(false)
        if (ok) {
            _state.value = _state.value.copy(
                embedBaseUrl = "",
                embedModel = "",
                embedApiKey = "",
                saved = false,
                embedSaveError = null,
                embedResult = null,
                embedResultOk = false,
            )
        } else {
            _state.value = _state.value.copy(
                saved = false,
                embedSaveError = CredentialCopy.DISCONNECT_FAILED,
            )
        }
        refreshPersisted()
        return ok
    }

    /** 升级一次性提示「知道了」：只清提示标记，不改任何配置（I）。 */
    suspend fun ackKeyResetNotice() {
        runCatching { container.settings.setChatKeyResetAfterUpgrade(false) }
        _state.value = _state.value.copy(keyResetNotice = false)
    }

    /** 找旧事页升级一次性提示「知道了」（与聊天提示分开清）。 */
    suspend fun ackEmbedKeyResetNotice() {
        runCatching { container.settings.setEmbedKeyResetAfterUpgrade(false) }
        _state.value = _state.value.copy(embedKeyResetNotice = false)
    }

    /**
     * 用 [settings] 的持久值回填本页：
     * - 用户输入框只在「还空着」时回填（不覆盖正在编辑的内容，沿用 v0.5.0 行为）；
     * - 密钥镜像 / 状态行 / 一次性提示**无条件取持久值**（它们不是输入 state）。
     */
    private suspend fun applyPersisted(settings: Settings) {
        val chatStatus = runCatching { container.chatKeyStatus(settings) }.getOrDefault(KeyStatus.None)
        val embedStatus = runCatching { container.embedKeyStatus(settings) }.getOrDefault(KeyStatus.None)
        _state.value = _state.value.copy(
            baseUrl = _state.value.baseUrl.ifBlank { settings.chatBaseUrl },
            model = _state.value.model.ifBlank { settings.chatModel },
            embedBaseUrl = _state.value.embedBaseUrl.ifBlank { settings.embedBaseUrl },
            embedModel = _state.value.embedModel.ifBlank { settings.embedModel },
            hasChatKey = settings.hasChatKey,
            chatKeyHost = settings.chatKeyHost,
            hasEmbedKey = settings.hasEmbedKey,
            embedKeyHost = settings.embedKeyHost,
            chatKeyStatus = chatStatus,
            embedKeyStatus = embedStatus,
            keyResetNotice = settings.chatKeyResetAfterUpgrade,
            embedKeyResetNotice = settings.embedKeyResetAfterUpgrade,
        )
    }

    /** 保存/清除后立刻重读持久值（不等采集器下一次发射），保证状态行不滞后。 */
    private suspend fun refreshPersisted() {
        val persisted = runCatching { container.settings.flow.first() }.getOrNull() ?: return
        current = persisted
        applyPersisted(persisted)
    }

    /** 只刷新 [current]（判定用的持久快照），不动 UI 回填。 */
    private suspend fun syncCurrent() {
        val persisted = runCatching { container.settings.flow.first() }.getOrNull() ?: return
        current = persisted
    }

    private fun update(block: ConfigUiState.() -> ConfigUiState) {
        _state.value = _state.value.block()
    }

    companion object {
        const val SECRET_CHAT_KEY = "chat_api_key"
        const val SECRET_EMBED_KEY = "embed_api_key"

        fun factory(container: AppContainer, tester: ConnectionTester): ViewModelProvider.Factory =
            viewModelFactory {
                initializer { ConfigViewModel(container, tester) }
            }
    }
}

/**
 * 聊天示例 chip 的纯映射（只改聊天地址 / 聊天模型两格）。
 *
 * 与 [applyEmbedExampleTo] 成对存在，便于纯 JVM 测试断言两页字段不串
 * （拆分依据：用户指令 2026-11-21）。
 *
 * 边界：找旧事三格（[ConfigUiState.embedBaseUrl] / [ConfigUiState.embedModel]）、
 * 密钥、测试状态一律不动——聊天和找旧事是两套服务。
 */
internal fun applyChatExampleTo(state: ConfigUiState, baseUrl: String, model: String): ConfigUiState =
    state.copy(baseUrl = baseUrl, model = model)

/**
 * 「找旧事」示例 chip 的纯映射（只改找旧事地址/模型两格）。
 *
 * 抽成纯函数的原因：`ConfigViewModel` 构造要 `AppContainer` + `viewModelScope`，
 * 纯 JVM 单测跑不了；映射本身没有副作用，单独测更直接（`ConfigEmbeddingTest`）。
 *
 * 边界：聊天三格（[ConfigUiState.baseUrl] / [ConfigUiState.model]）、密钥、测试状态
 * 一律不动——找旧事和聊天是两套服务。
 */
internal fun applyEmbedExampleTo(state: ConfigUiState, baseUrl: String, model: String): ConfigUiState =
    state.copy(embedBaseUrl = baseUrl, embedModel = model)

/**
 * 「测一下找旧事」的结果文案（风格：先说事实，再给出路）。
 *
 * 收口成常量是为了纯 JVM 逐字断言，防止后续手滑改成机制词。
 */
internal object EmbedTestCopy {
    /** 成功：只说事实，不夸张。 */
    const val SUCCESS = "找旧事连上了。"

    /** 失败：说清"没连上"并给出三个可检查的点（用户自己填的就是这三样）。 */
    const val FAILURE = "没连上。检查一下地址、密钥和模型名。"
}
