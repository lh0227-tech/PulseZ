package cc.pulsez.app.ui.config

import cc.pulsez.app.data.settings.Settings
import cc.pulsez.app.data.settings.SettingsStore
import cc.pulsez.app.net.BaseUrl

/**
 * 一步设置/密钥写入的抽象（v0.5.2，`）。
 *
 * 把「往 DataStore 写哪几个字段」「删哪个密文」包成一个 slot，[CredentialSaveWorkflow]
 * 只管三步协议的顺序与失败回滚；测试可以注入会失败的 slot（§3.6.5「注入失败点」），
 * 不必为了造一次写失败去改生产存储。
 */
internal data class CredentialSlot(
    /** `SecretStore` 里的密文名（`chat_api_key` / `embed_api_key`）。 */
    val secretName: String,
    /** 从设置快照读 `hasKey`（chat/embed 各一个字段）。 */
    val keyPresent: (Settings) -> Boolean,
    /** 从设置快照读 `keyHost`（chat/embed 各一个字段）。 */
    val keyHost: (Settings) -> String,
    /** 从设置快照读地址（用于 D/A 分支写后回读校验）。 */
    val endpointBase: (Settings) -> String,
    /** 从设置快照读模型名（用于 D/A 分支写后回读校验）。 */
    val endpointModel: (Settings) -> String,
    /** 保存协议第 ① 步：一次 edit 置 `hasKey=false` + `keyHost=""`。 */
    val invalidateBinding: suspend () -> Unit,
    /** `NoKeyYet` / `KeepOldKey`：只写地址 + 模型，**不动** `hasKey` / `keyHost` / 密文。 */
    val writeEndpoint: suspend (baseUrl: String, model: String) -> Unit,
    /** 保存协议第 ③ 步：一次 edit 写地址 + 模型 + `keyHost` + `hasKey=true`。 */
    val writeBoundEndpoint: suspend (baseUrl: String, model: String, keyHost: String) -> Unit,
)

/** 聊天三格对应的 slot。 */
internal fun chatCredentialSlot(settings: SettingsStore): CredentialSlot = CredentialSlot(
    secretName = ConfigViewModel.SECRET_CHAT_KEY,
    keyPresent = { it.hasChatKey },
    keyHost = { it.chatKeyHost },
    endpointBase = { it.chatBaseUrl },
    endpointModel = { it.chatModel },
    invalidateBinding = { settings.invalidateChatKeyBinding() },
    writeEndpoint = { baseUrl, model -> settings.setChatEndpoint(baseUrl, model) },
    writeBoundEndpoint = { baseUrl, model, origin ->
        settings.setChatBoundEndpoint(baseUrl, model, origin)
    },
)

/** 找旧事三格对应的 slot。 */
internal fun embedCredentialSlot(settings: SettingsStore): CredentialSlot = CredentialSlot(
    secretName = ConfigViewModel.SECRET_EMBED_KEY,
    keyPresent = { it.hasEmbedKey },
    keyHost = { it.embedKeyHost },
    endpointBase = { it.embedBaseUrl },
    endpointModel = { it.embedModel },
    invalidateBinding = { settings.invalidateEmbedKeyBinding() },
    writeEndpoint = { baseUrl, model -> settings.setEmbedEndpoint(baseUrl, model) },
    writeBoundEndpoint = { baseUrl, model, origin ->
        settings.setEmbedBoundEndpoint(baseUrl, model, origin)
    },
)

/** 保存结果（` A–）。UI 只把 [SAVED] 当成功。*/
internal enum class CredentialSaveResult {
    /** 回读校验通过，`saved=true` 只在这里发生（§3.8 可断言点）。 */
    SAVED,

    /** 地址解析不出 origin：一个字段都不写（J/M）。 */
    BAD_ORIGIN,

    /** 换服务商但密钥留空：一个字段都不写，要求重填（B）。 */
    REQUIRE_NEW_KEY,

    /** 任一步写失败 / 回读校验不一致：按协议回滚到「不可用」态（L）。 */
    FAILED,
}

/**
 * 密钥保存的三步「失效优先」协议（v0.5.2，`）。
 *
 * 为什么先失效：旧实现是「先写地址、再写密钥」，中途进程死掉 / 写失败就可能留下
 * 「新地址 + 旧密钥」的 状态。本协议把 ① 置为不可用放在最前，② 写密文，③ 才把
 * 地址/模型/host/hasKey 一起写并重新启用。任何一步失败都落在「绑定不可用」，最坏情况
 * 是用户重填一次（可接受），而不是把 A 家的密钥发给 B 家（不可接受）。
 *
 * 并发：本类**不加锁**；生产由 `AppContainer` 用同一把 `Mutex` 串行化保存 / 清除 /
 * 迁移和孤儿密文对账也使用同一把锁，避免与保存或清除交错执行。
 */
internal class CredentialSaveWorkflow(
    private val readSettings: suspend () -> Settings,
    private val writeSecret: (String, String) -> Unit,
    private val removeSecret: (String) -> Unit,
) {

    /**
     * 保存一次。
     *
     * @param keyInput 密钥框原文（空 = 用户没填；非空 = 要换成这一家的密钥）
     * @return [CredentialSaveResult.SAVED] 只在第 ④ 步回读校验通过后返回
     */
    suspend fun save(
        slot: CredentialSlot,
        baseUrl: String,
        model: String,
        keyInput: String,
    ): CredentialSaveResult {
        val trimmedBase = baseUrl.trim()
        val trimmedModel = model.trim()
        val trimmedKey = keyInput.trim()
        // （` 用户口径 / §2）：用户可省略协议（`api.deepseek.com/v1`）。
        // 判定仍按原规则接受这种输入（场景 J 不变，`saveDecision` 内部靠 `BaseUrl.origin` 补 https
        // 识别 origin），但**落盘值统一补 `https://`**；`http://` 原样保留、不强制升级。
        // 为什么要落盘就补：请求层 `BaseUrl.endpoint` 直接拼路径交给 OkHttp，无 scheme 会抛
        // `IllegalArgumentException`（v0.5.3 的 保存显示成功、请求全失败）。请求侧另有
        // 防御性兜底，只用于修复前已存下的历史无协议值，不能替代这里的新值归一化。
        val persistedBase = BaseUrl.withScheme(trimmedBase)
        val current = runCatching { readSettings() }.getOrElse { return CredentialSaveResult.FAILED }
        val decision = saveDecision(
            hasKey = slot.keyPresent(current),
            keyHost = slot.keyHost(current),
            baseUrl = trimmedBase,
            keyInputBlank = trimmedKey.isBlank(),
        )
        return when (decision) {
            KeySaveDecision.BadOrigin -> CredentialSaveResult.BAD_ORIGIN
            KeySaveDecision.RequireNewKey -> CredentialSaveResult.REQUIRE_NEW_KEY
            KeySaveDecision.NoKeyYet,
            KeySaveDecision.KeepOldKey,
            -> writeEndpointOnly(slot, persistedBase, trimmedModel)
            KeySaveDecision.SaveNewKey -> saveNewKey(slot, persistedBase, trimmedModel, trimmedKey)
        }
    }

    /**
     * `NoKeyYet` / `KeepOldKey`：只写地址 + 模型；写完**回读校验**，不一致同样算失败
     * （`saved=true` 不允许出现在「写成功但持久值不对」的情况；不会变成半配置）。
     *
     * @param baseUrl 已经过 归一化、**即将落盘**的地址；回读校验拿它比对，
     *   所以「用户输入无协议、落盘补了 https」不会被误判成写失败。
     */
    private suspend fun writeEndpointOnly(
        slot: CredentialSlot,
        baseUrl: String,
        model: String,
    ): CredentialSaveResult {
        val written = runCatching { slot.writeEndpoint(baseUrl, model) }.isSuccess
        if (!written) return CredentialSaveResult.FAILED
        val persisted = runCatching { readSettings() }.getOrNull()
            ?: return CredentialSaveResult.FAILED
        return if (slot.endpointBase(persisted) == baseUrl && slot.endpointModel(persisted) == model) {
            CredentialSaveResult.SAVED
        } else {
            CredentialSaveResult.FAILED
        }
    }

    /**
     * `SaveNewKey`：① 失效 → ② 写密文 → ③ 写地址/模型/host/hasKey → ④ 回读校验。
     *
     * ① 失败：DataStore 立不了「不可用」标记，改为**删掉旧密文**（能删就删），保证读路径
     * 拿不到旧密钥——宁可让用户重填，也不让可能配错的密钥出网。
     * ② 失败：绑定已在 ① 失效，直接返回失败。
     * ③ 失败：删密文 + 再次失效（`hasKey=false` + host 空），返回失败。
     * ④ 不一致：删密文 + 再次失效，返回失败。
     */
    private suspend fun saveNewKey(
        slot: CredentialSlot,
        baseUrl: String,
        model: String,
        newKey: String,
    ): CredentialSaveResult {
        val origin = BaseUrl.origin(baseUrl)
        // saveDecision 已经保证 origin 非空；这里防御性兜底（不写任何字段）。
        if (origin.isEmpty()) return CredentialSaveResult.BAD_ORIGIN

        // ① 失效优先：一次 edit 让旧绑定不可用。
        val invalidated = runCatching { slot.invalidateBinding() }.isSuccess
        if (!invalidated) {
            // 无法在设置里立标记时，删掉旧密文是唯一能保证「读路径不可用」的动作。
            runCatching { removeSecret(slot.secretName) }
            return CredentialSaveResult.FAILED
        }

        // ② 写新密文。
        if (runCatching { writeSecret(slot.secretName, newKey) }.isFailure) {
            return CredentialSaveResult.FAILED
        }

        // ③ 一次性写地址 + 模型 + host + hasKey=true。
        if (runCatching { slot.writeBoundEndpoint(baseUrl, model, origin) }.isFailure) {
            rollback(slot)
            return CredentialSaveResult.FAILED
        }

        // ④ 回读校验：`saved=true` 只在这一步通过后才可能出现。
        val persisted = runCatching { readSettings() }.getOrNull()
        val consistent = persisted != null &&
            slot.keyPresent(persisted) &&
            slot.keyHost(persisted) == origin
        if (!consistent) {
            rollback(slot)
            return CredentialSaveResult.FAILED
        }
        return CredentialSaveResult.SAVED
    }

    /**
     * 失败回滚：删密文 + 让绑定失效。
     *
     * 顺序上先删密文再失效；两者都是 best-effort（任一失败都会在下一次启动迁移的
     * 孤儿密文对账里重试，见 `AppContainer.migrateLegacyCredentialBinding`）。
     */
    private suspend fun rollback(slot: CredentialSlot) {
        runCatching { removeSecret(slot.secretName) }
        runCatching { slot.invalidateBinding() }
    }
}
