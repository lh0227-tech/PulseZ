package cc.pulsez.app.data.settings

import android.content.Context
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import cc.pulsez.app.net.isDeepSeekEndpoint
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "pulsez_settings")

/**
 * 设置项。密钥不在这里明文保存（走 Keystore 加密后再落），
 * 这里先放非敏感项与"密钥是否已配置"的标记。
 */
data class Settings(
    val chatBaseUrl: String = "",
    val chatModel: String = "",
    val hasChatKey: Boolean = false,
    /**
     * 聊天密钥**属于哪个服务商**（`net/BaseUrl.origin()` 归一化后的 `scheme://host[:port]`）。
     *
     * 空串 = 这个密钥没有可证明的归属：v0.5.1 及更早没有这个字段（升级前留下的密钥通不过迁移），
     * 或者刚走过保存协议第 ① 步（先把绑定置为不可用）。读取端只认「`keyHost` 与当前地址
     * origin 相同」的密钥（`ui/config/CredentialRules.kt` 的 `bindingState`），**绝不把空 host
     * 的旧密钥自动绑定到当前地址**（无法证明归属就不猜）。
     */
    val chatKeyHost: String = "",
    val embedBaseUrl: String = "",
    val embedModel: String = "",
    val hasEmbedKey: Boolean = false,
    /** 「找旧事」密钥的归属服务商 origin；语义同 [chatKeyHost]。 */
    val embedKeyHost: String = "",
    /**
     * 升级迁移是否清掉过聊天旧密钥（一次性提示标记）。
     *
     * 迁移把「有密钥但没有归属 host」的旧密钥删掉并置 true；配置页据此显示一次
     * 「这一版把密钥改成跟着服务商走，要重填一次。」，用户点「知道了」/重填保存/清除后清掉。
     */
    val chatKeyResetAfterUpgrade: Boolean = false,
    /** 「找旧事」旧密钥迁移一次性提示标记；语义同 [chatKeyResetAfterUpgrade]。 */
    val embedKeyResetAfterUpgrade: Boolean = false,
    val windowTier: Int = TIER_300K,
    val dailyLimitYuanX100: Int = 0,   // 0 = 不限制
    val monthlyLimitYuanX100: Int = 0, // 0 = 不限制
    val agreedVersion: Int = 0,
    val agreedAt: Long = 0L,
    /** 输入框「深度思考」胶囊；默认关，由用户按需手动开启。关时必须显式发 disabled。 */
    val thinkingEnabled: Boolean = false,
    /** 输入框「智能搜索」胶囊；默认关。仅 DeepSeek 端点可用。*/
    val webSearchEnabled: Boolean = false,
    /** 思考强度 `low` / `high` / `max`，默认 `low`（只在 DeepSeek + 思考开时生效）。 */
    val reasoningEffort: String = EFFORT_LOW,
    /**
     * 用户是否**在设置页改过**思考强度。
     *
     * 用途只有一处：改过之后就不再给「可以在设置里调思考强度」的一次性引导
     * （条件 4「用户没在设置里改过思考强度」、§7 验收第 5 条）。
     * 与 [reasoningEffort] 分开存：用户把值改回「低」也算改过（显式操作过就不该再教）。
     */
    val reasoningEffortTouched: Boolean = false,
    /**
     * 「创作模式」开关：默认关，由用户主动开启（v0.4）。
     *
     * 开启后 [cc.pulsez.app.core.ChatEngine] 在 ① 段人设层**末尾**追加
     * [cc.pulsez.app.core.CreativeMode.layer]；危机兜底换创作版文案。
     * 只改"回复可以更长、更入戏"，**不改变任何安全边界**，也不做任何自动检测。
     */
    val creativeMode: Boolean = false,
    /**
     * 「创作模式」首次开启说明是否已经看过（一次性说明，非阻断，可随时关）。
     *
     * 与 [creativeMode] 分开存：用户关掉模式后再开，不该重复弹说明；说明按钮是
     * 「开启 / 先不用」，没有"不勾选不能继续"。
     */
    val creativeModeIntroSeen: Boolean = false,
    /**
     * 自动朗读（系统 TTS）开关：默认关（v0.5）。
     *
     * 默认关有两个原因：① 公共场合突然出声不合适；② 音色/能否离线取决于手机，
     * 不该替用户默认打开一个"不确定听起来怎样"的功能。
     */
    val autoRead: Boolean = false,
    /** 朗读语速 ×100（0.8–1.5 倍，默认 1.0）。依据 「语速（0.8–1.5）」。*/
    val readRateX100: Int = READ_RATE_DEFAULT_X100,
    /** 朗读音色 id（`Voice.getName()`）；空串 = 系统默认/第一个可用中文音色。*/
    val readVoiceId: String = "",
    /**
     * 「自动朗读」首次说明是否已经看过（一次性「首次开启自动朗读时」）。
     *
     * 与 [autoRead] 分开存：看过说明后关掉再开不该重复弹；「先不用」也算看过。
     */
    val readIntroSeen: Boolean = false,
    /**
     * 「发图片」首次说明是否已经看过（v0.5 「识图」）。
     *
     * 触发时机：**首次带图发送前**弹一次（不是选图时），两个按钮（知道了 / 以后再说）都不阻断。
     * 与图片能力无关：用户可以换回不支持看图的模型，已看过标记保持不变（不会再弹）。
     */
    val imageIntroSeen: Boolean = false,
    /**
     * 「语音输入」首次说明是否已经看过（v0.5.1，`「语音输入首次」）。
     *
     * 触发时机：**第一次按麦克风**时先用 App 内一句话说明（这是什么 / 音频去哪 / 不用会怎样），
     * 再弹系统麦克风权限；「先不用」也算看过，之后不再重复弹。与权限是否授予无关：
     * 用户拒过权限后再点麦克风，仍应能再次看到用途说明（本条只记「已解释过」）。
     *
     * 与「自动朗读」「识图」的首用标记分开存：三者互不影响。
     */
    val voiceIntroSeen: Boolean = false,
    /**
     * 识图能力探测缓存：`/models` 探测时用的模型 id（v0.5）。
     *
     * 为什么缓存模型名：能力结论只对"探测时那个模型"有效；用户改模型后必须回退模型 id 规则，
     * 不能拿旧模型的结论冒充新模型。
     */
    val visionProbeModel: String = "",
    /**
     * 识图能力探测结论：`""` = 没探过/探测失败；`"true"` = 能看图；`"false"` = 不能。
     *
     * 用字符串而不是可空布尔：DataStore Preferences 没有 null 值类型。读取统一走
     * [visionProbeResult]。
     */
    val visionProbeSupported: String = "",
    /** 识图能力探测时间戳（毫秒）；0 = 没探过。只做溯源展示用，不做过期策略（v0.5）。*/
    val visionProbeAt: Long = 0L,
    /**
     * 最近一次「检查更新」的时间戳（epoch millis，0 = 没查过）。
     *
     * **成功/失败都写**：更新源一直不通时，如果不记录失败，每次冷启动都会再打一次（要求）。
     * 自动检查的 24h 节流只读这一个值（「每天最多一次」）。
     *
     * 与 旧稿的差异：旧稿分「成功 last_check_at」与「尝试 last_attempt_at」两个键，
     * 本版按合成为一个键（失败也写），语义 = 最近一次检查尝试时间。
     */
    val updateLastCheckAt: Long = 0L,
    /**
     * 用户点「稍后」跳过的版本号（0 = 没跳过）。
     *
     * 同一版本不再**自动**提示；用户手动点「检查更新」仍会看到新版本（忽略只压制自动打扰）。
     * 依据「跳过这个版本」。
     */
    val updateIgnoredVersionCode: Long = 0L,
    /**
     * 已经弹过一次「有新版本」启动提示的版本号（0 = 没弹过）。
     *
     * 用途只有一个：同一版本**只打扰一次**，不要每次冷启动都弹对话框
     * （`update_pending_version_code`「已提示但未处理」；业务口径见）。
     */
    val updatePendingVersionCode: Long = 0L,
) {
    companion object {
        const val TIER_32K = 32_000
        const val TIER_64K = 64_000
        const val TIER_300K = 300_000
        const val TIER_CUSTOM = -1

        /** 思考强度取值（DeepSeek `reasoning_effort`）。*/
        const val EFFORT_LOW = "low"
        const val EFFORT_HIGH = "high"
        const val EFFORT_MAX = "max"

        val REASONING_EFFORTS = listOf(EFFORT_LOW, EFFORT_HIGH, EFFORT_MAX)

        /** 朗读语速范围 ×100：0.8–1.5 倍。*/
        const val READ_RATE_MIN_X100 = 80
        const val READ_RATE_MAX_X100 = 150

        /** 默认朗读语速 1.0 倍。 */
        const val READ_RATE_DEFAULT_X100 = 100
    }
}

/**
 * 把任意存储的语速值收敛到 0.8–1.5（×100）。
 *
 * 与 [normalizeReasoningEffort] 同一思路：脏数据/手改值不能把 `TextToSpeech.setSpeechRate`
 * 推到不可听的范围；范围外一律夹到边界，不是丢弃成默认（用户选过「更快」不该被重置成 1.0）。
 */
fun normalizeReadRate(rateX100: Int?): Int =
    (rateX100 ?: Settings.READ_RATE_DEFAULT_X100)
        .coerceIn(Settings.READ_RATE_MIN_X100, Settings.READ_RATE_MAX_X100)

/** 把任意输入归一化成合法档位：不认识的一律按低（更便宜、更符合聊天场景）。 */
fun normalizeReasoningEffort(effort: String?): String =
    if (effort != null && effort in Settings.REASONING_EFFORTS) effort else Settings.EFFORT_LOW

/**
 * 是否是 DeepSeek 官方端点（全工程统一的 host 规则，见 `net/DeepSeekEndpoint.kt`）。
 *
 * 思考 / 联网搜索的字段是 DeepSeek 私有能力（`deepseek_web_search` 是私有工具类型，
 * ）；对其它服务商发这些字段可能 400 或静默忽略，所以 UI 上直接不显示胶囊。
 *
 * **不要**再退回 `chatBaseUrl.contains("deepseek")`：镜像/代理域名（`deepseek-proxy.example.com`）
 * 与伪装域名（`api.deepseek.com.evil.com`）都会被裸子串误判成官方端点。

 */
fun Settings.isDeepSeekProvider(): Boolean = isDeepSeekEndpoint(chatBaseUrl)

/**
 * 图片入口的三态（v0.5 ` §3.3）。
 *
 * 为什么是三态而不是布尔：产品要求不同原因给不同表现
 * ——
 * - 第三方端点：**入口隐藏**，并在设置→连接里说明"发图片只对 DeepSeek 的地址有效"；
 * - DeepSeek 但模型不支持：**入口置灰/禁用**，说明"这个模型看不了图"；
 * - 能用：正常显示。
 */
enum class ImageEntryState {
    /** 非 DeepSeek 端点：不显示入口（第三方对 image_url 行为未核实，不发）。 */
    HIDDEN_OTHER_PROVIDER,

    /** DeepSeek 端点但当前模型不支持看图：入口禁用并说明。 */
    DISABLED_MODEL,

    /** 能用。 */
    ENABLED,
}

/** 读缓存的能力结论；``""``（没探过）与其它脏值都回 null。 */
fun Settings.visionProbeResult(): Boolean? = when (visionProbeSupported.trim().lowercase()) {
    "true" -> true
    "false" -> false
    else -> null
}

/**
 * 当前配置能不能发图片（纯函数；门禁判定见 `net/VisionCapability`）。
 *
 * 依据：` 「门禁 = 模型 id + input_modalities，host 只作端点判断」；
 * ` 「绝不回退 host」。
 */
fun Settings.visionAvailable(): Boolean = cc.pulsez.app.net.VisionCapability.isAvailable(
    deepSeekProvider = isDeepSeekProvider(),
    model = chatModel,
    probedModel = visionProbeModel,
    probedSupported = visionProbeResult(),
)

/** 图片入口该显示成什么样（见 [ImageEntryState]）。 */
fun Settings.imageEntryState(): ImageEntryState = when {
    !isDeepSeekProvider() -> ImageEntryState.HIDDEN_OTHER_PROVIDER
    visionAvailable() -> ImageEntryState.ENABLED
    else -> ImageEntryState.DISABLED_MODEL
}

class SettingsStore(context: Context) {

    /**
     * 协议版本与门槛判据（v0.5.2，`）。
     *
     * **注意**：这个 companion 属于 `class SettingsStore`；上面 `data class Settings`（`:158-179`）
     * 也有一个 companion（思考档位/语速常量），两者不是同一个。协议版本必须放在**这个类**里，
     * 门槛页（`ui/AppRoot.kt`）与更新检查（`update/UpdatePrefs.kt`）才能统一引用
     * `SettingsStore.AGREEMENT_VERSION` / `SettingsStore.needsAgreement(...)`（全工程只留一处）。
     */
    companion object {

        /**
         * 当前服务协议版本（v0.5.2 从 1 升到 2）。
         *
         * 为什么升版本：v0.5.2 的协议新增了「密钥跟着服务商走」「清空会删什么/留什么」
         * 「没有新增权限」三件事（`res/raw/agreement.html`）。只判 `== 0` 会让 v0.5.0/v0.5.1
         * 的升级用户看不到新增的出网/清空说明，却直接放行新的后台检查。
         */
        const val AGREEMENT_VERSION: Int = 2

        /**
         * 门槛判据：**已同意版本必须恰好等于当前版本**。
         *
         * 用 `!=` 而不是 `<`：降级安装（旧版数据里 agreedVersion 更大）同样要求重新同意——
         * 用户看到的是旧版协议，不能拿「同意过更新的协议」放行（` E）。
         * 门槛页与 `update/UpdatePrefs` 必须用这**同一个**判据（§4.2 F），不得写成两套。
         */
        fun needsAgreement(agreedVersion: Int): Boolean = agreedVersion != AGREEMENT_VERSION
    }

    private val store = context.dataStore

    val flow: Flow<Settings> = store.data.map { p -> p.toSettings() }

    /**
     * 批量修改设置（原子）。
     *
     * 参数是 [MutablePreferences]：测试可以在 `@Before` 里 `update { it.clear() }` 把设置
     * 清回默认——Robolectric 同一进程内 DataStore 是单例，用例之间会共享同一个文件。
     */
    suspend fun update(block: (MutablePreferences) -> Unit) {
        store.edit(block)
    }

    suspend fun setChatEndpoint(baseUrl: String, model: String) {
        store.edit {
            it[Keys.CHAT_BASE_URL] = baseUrl
            it[Keys.CHAT_MODEL] = model
        }
    }

    suspend fun setEmbedEndpoint(baseUrl: String, model: String) {
        store.edit {
            it[Keys.EMBED_BASE_URL] = baseUrl
            it[Keys.EMBED_MODEL] = model
        }
    }

    suspend fun setWindowTier(tier: Int) {
        store.edit { it[Keys.WINDOW_TIER] = tier }
    }

    suspend fun setDailyLimit(yuanX100: Int) {
        store.edit { it[Keys.DAILY_LIMIT] = yuanX100.coerceAtLeast(0) }
    }

    suspend fun setMonthlyLimit(yuanX100: Int) {
        store.edit { it[Keys.MONTHLY_LIMIT] = yuanX100.coerceAtLeast(0) }
    }

    /** 记录协议同意（版本 + 时间戳），用于本地的可核验留痕。 */
    suspend fun agree(version: Int, at: Long = System.currentTimeMillis()) {
        store.edit {
            it[Keys.AGREED_VERSION] = version
            it[Keys.AGREED_AT] = at
        }
    }

    /**
     * 「清空所有数据」里的设置清空（v0.5.2，` B ③ / §4.3.2）。
     *
     * 语义：**一次 `edit`** 先读出协议同意记录，`clear()` 清掉全部键，再把协议同意两项写回。
     * 清完 `Settings` 必须等于 `Settings(agreedVersion = 原值, agreedAt = 原值)`（其余键全默认）
     * ——`clear()` 清全部键，所以 新增的 `chatKeyHost/embedKeyHost`、
     * `chatKeyResetAfterUpgrade/embedKeyResetAfterUpgrade`、识图探测缓存、更新检查记录、
     * 语音/朗读/图片首用标记…全都会被清掉；**不得**只逐项清
     * 「会删」清单里的键（漏键正是 的来源）。
     *
     * 为什么保留协议同意记录：清空数据不该让用户重新过一次 18 岁门槛（伤害体验），
     * 协议原文 §4 已写明「协议同意记录会保留，避免重过门槛」。
     *
     * 为什么必须单次 edit：拆多次写会让「部分清空」在进程被杀时留下半清状态（ 的
     * 失败不可见问题）；单次 edit 的 DataStore 写入是原子的。
     */
    suspend fun wipeUserData() {
        store.edit { prefs ->
            val agreedVersion = prefs[Keys.AGREED_VERSION] ?: 0
            val agreedAt = prefs[Keys.AGREED_AT] ?: 0L
            prefs.clear()
            prefs[Keys.AGREED_VERSION] = agreedVersion
            prefs[Keys.AGREED_AT] = agreedAt
        }
    }

    suspend fun setChatKeyPresent(present: Boolean) {
        store.edit { it[Keys.HAS_CHAT_KEY] = present }
    }

    suspend fun setEmbedKeyPresent(present: Boolean) {
        store.edit { it[Keys.HAS_EMBED_KEY] = present }
    }

    /**
     * 保存协议 **第 ① 步**（v0.5.2，`）：一次 edit 把聊天密钥绑定
     * 置为不可用（`hasKey=false` + host 清空）。
     *
     * 必须先立这步再写新密文/新地址：任何中断都落在「不可用」态，不会出现「新密钥配旧 host」
     * 或「旧密钥配新 host」（′ 的根因）。**不动** endpoint/model 与升级提示标记。
     */
    suspend fun invalidateChatKeyBinding() {
        store.edit {
            it[Keys.HAS_CHAT_KEY] = false
            it[Keys.CHAT_KEY_HOST] = ""
        }
    }

    /** 保存协议第 ① 步（找旧事）；语义同 [invalidateChatKeyBinding]。 */
    suspend fun invalidateEmbedKeyBinding() {
        store.edit {
            it[Keys.HAS_EMBED_KEY] = false
            it[Keys.EMBED_KEY_HOST] = ""
        }
    }

    /**
     * 保存协议 **第 ③ 步**（v0.5.2）：**一次 edit** 写聊天地址 / 模型 / 绑定 host，并置
     * `hasKey=true`。调用前必须已按序完成 ① 失效 ② 密文写入（`CredentialSaveWorkflow`）。
     */
    suspend fun setChatBoundEndpoint(baseUrl: String, model: String, keyHost: String) {
        store.edit {
            it[Keys.CHAT_BASE_URL] = baseUrl
            it[Keys.CHAT_MODEL] = model
            it[Keys.CHAT_KEY_HOST] = keyHost
            it[Keys.HAS_CHAT_KEY] = true
        }
    }

    /** 保存协议第 ③ 步（找旧事）；语义同 [setChatBoundEndpoint]。 */
    suspend fun setEmbedBoundEndpoint(baseUrl: String, model: String, keyHost: String) {
        store.edit {
            it[Keys.EMBED_BASE_URL] = baseUrl
            it[Keys.EMBED_MODEL] = model
            it[Keys.EMBED_KEY_HOST] = keyHost
            it[Keys.HAS_EMBED_KEY] = true
        }
    }

    /**
     * 升级迁移（v0.5.2，` I）：清聊天旧密钥的绑定 + `hasKey=false`
     * + 置一次性提示标记，**一次 edit** 完成。host 不回填（无法证明旧密钥属于谁）。
     *
     * baseUrl/model 保留：那是用户填的配置，删掉会连带毁掉「服务商地址」的事实；
     * 只是密钥不再可用（读路径 `bindingState` 独立守卫）。
     */
    suspend fun resetLegacyChatKeyBinding() {
        store.edit {
            it[Keys.HAS_CHAT_KEY] = false
            it[Keys.CHAT_KEY_HOST] = ""
            it[Keys.CHAT_KEY_RESET_AFTER_UPGRADE] = true
        }
    }

    /** 升级迁移（找旧事）；语义同 [resetLegacyChatKeyBinding]。 */
    suspend fun resetLegacyEmbedKeyBinding() {
        store.edit {
            it[Keys.HAS_EMBED_KEY] = false
            it[Keys.EMBED_KEY_HOST] = ""
            it[Keys.EMBED_KEY_RESET_AFTER_UPGRADE] = true
        }
    }

    /**
     * 「清除密钥」（v0.5.2，` E）：一次 edit 让聊天密钥绑定失效，
     * 并清掉升级一次性提示（用户已经用行动处理过，提示不再需要）。
     *
     * 密文由调用方删（`SecretStore` 不在 DataStore 里，`CredentialSaveWorkflow`/AppContainer 串行）；
     * 本方法只负责设置里的「不可用 + 不再提示」。
     */
    suspend fun clearChatKeyBinding() {
        store.edit {
            it[Keys.HAS_CHAT_KEY] = false
            it[Keys.CHAT_KEY_HOST] = ""
            it[Keys.CHAT_KEY_RESET_AFTER_UPGRADE] = false
        }
    }

    /**
     * 「停止使用找旧事」（v0.5.2，` H）：一次 edit 清掉找旧事的
     * 地址 / 模型 / 绑定 / `hasKey` / 升级提示。之后 `ChatEngine` 读到的 `embedBaseUrl` 为空，
     * 检索/索引与「找旧事」服务零出网；关键词检索不受影响。
     */
    suspend fun clearEmbedServiceConfig() {
        store.edit {
            it.remove(Keys.EMBED_BASE_URL)
            it.remove(Keys.EMBED_MODEL)
            it[Keys.HAS_EMBED_KEY] = false
            it[Keys.EMBED_KEY_HOST] = ""
            it[Keys.EMBED_KEY_RESET_AFTER_UPGRADE] = false
        }
    }

    /** 清 / 置「升级后要重填一次」的聊天一次性提示（「知道了」/ 重填成功后清）。 */
    suspend fun setChatKeyResetAfterUpgrade(reset: Boolean) {
        store.edit { it[Keys.CHAT_KEY_RESET_AFTER_UPGRADE] = reset }
    }

    /** 清 / 置「升级后要重填一次」的找旧事一次性提示。 */
    suspend fun setEmbedKeyResetAfterUpgrade(reset: Boolean) {
        store.edit { it[Keys.EMBED_KEY_RESET_AFTER_UPGRADE] = reset }
    }

    /** 输入框「深度思考」开关（跨会话记住上次选择）。*/
    suspend fun setThinkingEnabled(enabled: Boolean) {
        store.edit { it[Keys.THINKING_ENABLED] = enabled }
    }

    /** 输入框「智能搜索」开关（跨会话记住上次选择）。*/
    suspend fun setWebSearchEnabled(enabled: Boolean) {
        store.edit { it[Keys.WEB_SEARCH_ENABLED] = enabled }
    }

    /**
     * 思考强度。无效值按低处理，避免把脏数据发给服务端。
     * 同时记下「用户改过强度」——改过之后不再出现一次性引导（条件 4）。
     */
    suspend fun setReasoningEffort(effort: String) {
        store.edit {
            it[Keys.REASONING_EFFORT] = normalizeReasoningEffort(effort)
            it[Keys.REASONING_EFFORT_TOUCHED] = true
        }
    }

    /**
     * 「创作模式」开关（默认关；用户主动开启）。
     * 关掉只影响之后的新一轮，不改写已落库的消息、不销毁任何数据。
     */
    suspend fun setCreativeMode(enabled: Boolean) {
        store.edit { it[Keys.CREATIVE_MODE] = enabled }
    }

    /**
     * 记录「创作模式」一次性说明已展示。调用方在说明弹出时写 true；
     * 只有用户**主动开启**时才需要展示，关掉模式不会重置它。
     */
    suspend fun setCreativeModeIntroSeen(seen: Boolean) {
        store.edit { it[Keys.CREATIVE_MODE_INTRO_SEEN] = seen }
    }

    /**
     * 自动朗读开关（v0.5）。
     *
     * 只改"下一段新回复读不读"；正在读的那条由调用方同时 `Reader.stop()`（见 AppRoot/ChatViewModel）。
     */
    suspend fun setAutoRead(enabled: Boolean) {
        store.edit { it[Keys.AUTO_READ] = enabled }
    }

    /** 朗读语速 ×100；越界值按 0.8–1.5 收敛，避免脏数据把语速推到不可听。 */
    suspend fun setReadRateX100(rateX100: Int) {
        store.edit { it[Keys.READ_RATE_X100] = normalizeReadRate(rateX100) }
    }

    /** 朗读音色 id；空串 = 系统默认/第一个可用中文音色（`Voice.getName()` 原样存）。 */
    suspend fun setReadVoiceId(voiceId: String) {
        store.edit { it[Keys.READ_VOICE_ID] = voiceId }
    }

    /** 记录「自动朗读」一次性说明已展示（首用 / 先不用 / 点外面关掉都算）。 */
    suspend fun setReadIntroSeen(seen: Boolean) {
        store.edit { it[Keys.READ_INTRO_SEEN] = seen }
    }

    /** 记录「发图片」一次性说明已展示（知道了 / 以后再说 / 点外面关掉都算）。 */
    suspend fun setImageIntroSeen(seen: Boolean) {
        store.edit { it[Keys.IMAGE_INTRO_SEEN] = seen }
    }

    /**
     * 记录「语音输入」一次性说明已展示（v0.5.1；「先不用」/点外面关掉都算）。
     *
     * 只影响「第一次按麦克风前的说明」，不参与权限判定、不影响录音是否可用。
     */
    suspend fun setVoiceIntroSeen(seen: Boolean) {
        store.edit { it[Keys.VOICE_INTRO_SEEN] = seen }
    }

    /**
     * 记录一次「检查更新」的尝试时间（v0.5.1）。
     *
     * 成功/失败都调用（[cc.pulsez.app.update.UpdateRepository] 保证）：失败也写，才不会每次
     * 冷启动都再联网一次。只写这一个键，不动其它设置。
     */
    suspend fun setUpdateCheckResult(at: Long) {
        store.edit { it[Keys.UPDATE_LAST_CHECK_AT] = at }
    }

    /**
     * 记录「已经弹过一次新版本提示」的版本号（同版本不再重复打扰）。
     *
     * 0 = 清掉标记（留给将来「清空数据/重置」用）；负数按 0 处理，避免脏数据。
     */
    suspend fun setUpdatePendingVersionCode(versionCode: Long) {
        store.edit { it[Keys.UPDATE_PENDING_VERSION_CODE] = versionCode.coerceAtLeast(0L) }
    }

    /**
     * 记录用户点「稍后」跳过的版本号（0 = 清掉跳过标记）。
     *
     * 只影响**自动**提示；用户手动检查仍会如实显示新版本。
     */
    suspend fun setUpdateIgnoredVersionCode(versionCode: Long) {
        store.edit { it[Keys.UPDATE_IGNORED_VERSION_CODE] = versionCode.coerceAtLeast(0L) }
    }

    /**
     * 缓存识图能力探测结论（v0.5 `ConnectionTester`/`ProbeTester` 在"测一下"成功后写入）。
     *
     * 只缓存结论与模型：**不缓存 base url**——门禁的端点判断永远实时走
     * `Settings.isDeepSeekProvider()`（绝不回退 host/不拿旧结论冒充新端点）。
     */
    suspend fun setVisionProbe(
        model: String,
        supported: Boolean,
        at: Long = System.currentTimeMillis(),
    ) {
        val safeModel = model.trim()
        if (safeModel.isEmpty()) return
        store.edit {
            it[Keys.VISION_PROBE_MODEL] = safeModel
            it[Keys.VISION_PROBE_SUPPORTED] = if (supported) "true" else "false"
            it[Keys.VISION_PROBE_AT] = at
        }
    }

    /**
     * 清掉识图能力缓存（换端点/清空配置时用；之后按模型 id 规则回退）。
     *
     * **预留（v0.5.1+，`）**：目前生产代码无调用——`VisionCapability`
     * 已按「缓存里的模型 id == 当前模型、且端点仍是 DeepSeek」逐次判定，换模型/换端点不会
     * 冒用旧结论，所以不调用也不会给出错误门禁。保留给「换服务商时彻底清缓存」的维护动作，
     * 别当死代码删掉。
     */
    suspend fun clearVisionProbe() {
        store.edit {
            it.remove(Keys.VISION_PROBE_MODEL)
            it.remove(Keys.VISION_PROBE_SUPPORTED)
            it.remove(Keys.VISION_PROBE_AT)
        }
    }

    /**
     * 「深度思考可以调力度」行内提示是否该出现（产品决定 2026-11-20：默认已开，
     * 所以触发条件是「用户**第一次操作**深度思考开关」——无论开还是关，不再是「关切→开」）。
     *
     * 同时满足才返回 true，并**立刻**写「已展示」标记（不等用户关掉）：
     * 1. 这是用户第一次拨动「深度思考」开关（调用方在每次切换后调用一次，不传方向）；
     * 2. 安装后还没展示过；
     * 3. 用户没在设置里改过思考强度（改过就说明已经知道这个设置）。
     *
     * 展示时机/时长/无障碍由 `ui/chat/ChatScreen.kt` 负责（300ms 延迟、4s/系统建议时长）。
     */
    suspend fun consumeThinkingHint(): Boolean {
        val prefs = store.data.first()
        if (prefs[Keys.THINKING_HINT_SHOWN] == true) return false
        if (prefs[Keys.REASONING_EFFORT_TOUCHED] == true) return false
        store.edit { it[Keys.THINKING_HINT_SHOWN] = true }
        return true
    }

    private fun Preferences.toSettings(): Settings = Settings(
        chatBaseUrl = this[Keys.CHAT_BASE_URL].orEmpty(),
        chatModel = this[Keys.CHAT_MODEL].orEmpty(),
        hasChatKey = this[Keys.HAS_CHAT_KEY] ?: false,
        chatKeyHost = this[Keys.CHAT_KEY_HOST].orEmpty(),
        embedBaseUrl = this[Keys.EMBED_BASE_URL].orEmpty(),
        embedModel = this[Keys.EMBED_MODEL].orEmpty(),
        hasEmbedKey = this[Keys.HAS_EMBED_KEY] ?: false,
        embedKeyHost = this[Keys.EMBED_KEY_HOST].orEmpty(),
        chatKeyResetAfterUpgrade = this[Keys.CHAT_KEY_RESET_AFTER_UPGRADE] ?: false,
        embedKeyResetAfterUpgrade = this[Keys.EMBED_KEY_RESET_AFTER_UPGRADE] ?: false,
        windowTier = this[Keys.WINDOW_TIER] ?: Settings.TIER_300K,
        dailyLimitYuanX100 = this[Keys.DAILY_LIMIT] ?: 0,
        monthlyLimitYuanX100 = this[Keys.MONTHLY_LIMIT] ?: 0,
        agreedVersion = this[Keys.AGREED_VERSION] ?: 0,
        agreedAt = this[Keys.AGREED_AT] ?: 0L,
        thinkingEnabled = this[Keys.THINKING_ENABLED] ?: false,
        webSearchEnabled = this[Keys.WEB_SEARCH_ENABLED] ?: false,
        reasoningEffort = normalizeReasoningEffort(this[Keys.REASONING_EFFORT]),
        reasoningEffortTouched = this[Keys.REASONING_EFFORT_TOUCHED] ?: false,
        creativeMode = this[Keys.CREATIVE_MODE] ?: false,
        creativeModeIntroSeen = this[Keys.CREATIVE_MODE_INTRO_SEEN] ?: false,
        autoRead = this[Keys.AUTO_READ] ?: false,
        readRateX100 = normalizeReadRate(this[Keys.READ_RATE_X100]),
        readVoiceId = this[Keys.READ_VOICE_ID].orEmpty(),
        readIntroSeen = this[Keys.READ_INTRO_SEEN] ?: false,
        imageIntroSeen = this[Keys.IMAGE_INTRO_SEEN] ?: false,
        voiceIntroSeen = this[Keys.VOICE_INTRO_SEEN] ?: false,
        visionProbeModel = this[Keys.VISION_PROBE_MODEL].orEmpty(),
        visionProbeSupported = this[Keys.VISION_PROBE_SUPPORTED].orEmpty(),
        visionProbeAt = this[Keys.VISION_PROBE_AT] ?: 0L,
        updateLastCheckAt = this[Keys.UPDATE_LAST_CHECK_AT] ?: 0L,
        updateIgnoredVersionCode = this[Keys.UPDATE_IGNORED_VERSION_CODE] ?: 0L,
        updatePendingVersionCode = this[Keys.UPDATE_PENDING_VERSION_CODE] ?: 0L,
    )

    private object Keys {
        val CHAT_BASE_URL = stringPreferencesKey("chat_base_url")
        val CHAT_MODEL = stringPreferencesKey("chat_model")
        val HAS_CHAT_KEY = booleanPreferencesKey("has_chat_key")
        // v0.5.2 密钥按服务商绑定。Key 名发布后不再改。
        val CHAT_KEY_HOST = stringPreferencesKey("chat_key_host")
        val CHAT_KEY_RESET_AFTER_UPGRADE = booleanPreferencesKey("chat_key_reset_after_upgrade")
        val EMBED_BASE_URL = stringPreferencesKey("embed_base_url")
        val EMBED_MODEL = stringPreferencesKey("embed_model")
        val HAS_EMBED_KEY = booleanPreferencesKey("has_embed_key")
        val EMBED_KEY_HOST = stringPreferencesKey("embed_key_host")
        val EMBED_KEY_RESET_AFTER_UPGRADE = booleanPreferencesKey("embed_key_reset_after_upgrade")
        val WINDOW_TIER = intPreferencesKey("window_tier")
        val DAILY_LIMIT = intPreferencesKey("daily_limit_x100")
        val MONTHLY_LIMIT = intPreferencesKey("monthly_limit_x100")
        val AGREED_VERSION = intPreferencesKey("agreed_version")
        val AGREED_AT = longPreferencesKey("agreed_at")
        val THINKING_ENABLED = booleanPreferencesKey("thinking_enabled")
        val WEB_SEARCH_ENABLED = booleanPreferencesKey("web_search_enabled")
        val REASONING_EFFORT = stringPreferencesKey("reasoning_effort")
        val REASONING_EFFORT_TOUCHED = booleanPreferencesKey("reasoning_effort_touched")
        val THINKING_HINT_SHOWN = booleanPreferencesKey("thinking_hint_shown")

        // v0.4 创作模式。Key 名一旦发布就不要再改，否则老用户的开关会被重置。
        val CREATIVE_MODE = booleanPreferencesKey("creative_mode")
        val CREATIVE_MODE_INTRO_SEEN = booleanPreferencesKey("creative_mode_intro_seen")

        // v0.5 自动朗读。同样：Key 名一旦发布就不要再改。
        val AUTO_READ = booleanPreferencesKey("auto_read")
        val READ_RATE_X100 = intPreferencesKey("read_rate_x100")
        val READ_VOICE_ID = stringPreferencesKey("read_voice_id")
        val READ_INTRO_SEEN = booleanPreferencesKey("read_intro_seen")

        // v0.5.1 语音输入。Key 名一旦发布就不要再改。
        val VOICE_INTRO_SEEN = booleanPreferencesKey("voice_intro_seen")

        // v0.5 识图。Key 名一旦发布就不要再改。
        val IMAGE_INTRO_SEEN = booleanPreferencesKey("image_intro_seen")
        val VISION_PROBE_MODEL = stringPreferencesKey("vision_probe_model")
        val VISION_PROBE_SUPPORTED = stringPreferencesKey("vision_probe_supported")
        val VISION_PROBE_AT = longPreferencesKey("vision_probe_at")

        // v0.5.1 检查更新。Key 名一旦发布就不要再改。
        val UPDATE_LAST_CHECK_AT = longPreferencesKey("update_last_check_at")
        val UPDATE_IGNORED_VERSION_CODE = longPreferencesKey("update_ignored_version_code")
        val UPDATE_PENDING_VERSION_CODE = longPreferencesKey("update_pending_version_code")
    }
}
