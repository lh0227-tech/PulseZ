package cc.pulsez.app.ui.config

import cc.pulsez.app.net.BaseUrl
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * 密钥与「服务商」绑定的纯规则（v0.5.2，`）。
 *
 * 背景（第三方）：v0.5.1 的密钥槽是全局单值（`chat_api_key` / `embed_api_key`），
 * 保存时「地址无条件写、密钥非空才写」→ 用户换服务商、密钥框留空保存，旧服务商的密钥会被
 * 拼到新地址上发出（`net/JsonHttp.kt` 的 `Authorization: Bearer`）。修法的核心假设是
 * **App 无法证明旧密钥属于谁**（「禁止猜」）：
 * - 保存时把「密钥 → 服务商 origin」的绑定显式写下来（`keyHost`）；
 * - 读取时只有 `keyHost == 当前地址 origin` 才允许使用密钥；
 * - 换服务商（origin 变化）而密钥留空 → 拒绝保存、不写任何字段、要求重填这一家的密钥；
 * - 升级迁移把「有密钥但 keyHost 为空」的旧密钥删掉（无法证明归属，不能自动回填）。
 *
 * 本文件只有纯函数：不碰 DataStore / Keystore / Compose，可直接 JVM 单测
 * （`CredentialRulesTest`）。origin **必须**复用 `net/BaseUrl.origin()`（已有 `BaseUrlOriginTest`
 * 锁行为），不得另造解析，否则「同 origin 换 path」「无协议地址」两处会和网络层不一致。
 */

/**
 * 保存时的判定（` A–§3.3.2）。
 *
 * 只有 [SaveNewKey] 与 [NoKeyYet]/[KeepOldKey] 会写设置；[BadOrigin]/[RequireNewKey]
 * **一个字段都不写**（B/G/M：不写、不假成功）。
 */
internal enum class KeySaveDecision {
    /** 地址解析不出 origin（空 / 非法）：拒绝保存并提示填完整地址（`BadOrigin`）。 */
    BadOrigin,

    /** 还没有密钥、密钥框也空：只写地址 + 模型（D）。 */
    NoKeyYet,

    /** 同 origin、已有密钥、密钥框空：保留旧密钥与旧 host，只更新地址/模型（A/K）。 */
    KeepOldKey,

    /** 已有密钥、但 origin 变了或旧 host 为空：密钥留空保存 → 拒绝，要求重填（B）。 */
    RequireNewKey,

    /** 密钥框填了新密钥：走三步「失效优先」协议（C）。 */
    SaveNewKey,
}

/**
 * 地址 → origin（统一入口，避免判定与展示各写一份解析）。
 *
 * origin **必须**复用 [BaseUrl.origin]；这里只额外挡一种输入：**只有协议头**（`https://`、
 * `http://`、`https:`）。`BaseUrl.normalize` 会把它们削成 `https:`，而 `origin()` 对
 * 「不含 `://` 的裸字符串」补 https 前缀后，OkHttp 会把 `https` 当成主机名、空端口当默认端口，
 * 于是得到看似合法的 `https://https`。这不是真实服务商地址，保存它只会让用户之后必然连不上，
 * 所以按坏地址处理（M/J：提示「地址要填完整」）。
 */
private fun originOf(raw: String): String {
    val normalized = BaseUrl.normalize(raw)
    if (normalized.isEmpty() || normalized.endsWith(":")) return ""
    return BaseUrl.origin(normalized)
}

/**
 * 保存判定（纯函数）。
 *
 * @param hasKey 持久化的 `hasChatKey` / `hasEmbedKey`（**只读设置流的值**，不用 UI state 猜）
 * @param keyHost 持久化的 `chatKeyHost` / `embedKeyHost`（origin；空串 = 归属未知）
 * @param baseUrl 用户这次填的地址（未 trim 也行，origin 解析前会 trim）
 * @param keyInputBlank 密钥框是否为空（`trim()` 后）
 */
internal fun saveDecision(
    hasKey: Boolean,
    keyHost: String,
    baseUrl: String,
    keyInputBlank: Boolean,
): KeySaveDecision {
    // origin 复用生产解析：用户可能填 `api.deepseek.com/v1`、带/不带 `/v1`、甚至整条
    // `.../v1/chat/completions`（BaseUrl 会剥掉端点后缀）。解析失败 = 地址不可用。
    val origin = originOf(baseUrl)
    if (origin.isEmpty()) return KeySaveDecision.BadOrigin
    // 用户这次填了密钥 = 明确表示「换成这一家的密钥」，无论 origin 是否变化都按新密钥绑定
    // （C；同 origin 重填也走 SaveNewKey，保证 host 与密钥一起更新，不依赖旧 host 是否可信）。
    if (!keyInputBlank) return KeySaveDecision.SaveNewKey
    if (!hasKey) return KeySaveDecision.NoKeyYet
    // 旧密钥能继续用，必须同时满足「有归属 host」且「归属就是当前服务商」。
    // 空 host 不能回填当前地址（′ / 禁止项 1）：那等于猜旧密钥属于新服务商。
    return if (originOf(keyHost) == origin) {
        KeySaveDecision.KeepOldKey
    } else {
        KeySaveDecision.RequireNewKey
    }
}

/**
 * 绑定判定的第一段（只判关系，**不读密钥**；`）。
 *
 * 拆两段的理由：读密钥要走 AndroidKeyStore（慢、可能失败），没有绑定关系时根本不该碰；
 * 同时把「先判绑定、再读密钥」落成可断言的函数边界（§3.8 点）。
 */
internal sealed interface KeyBindingState {
    /** 没有可证明的绑定：没密钥 / host 空 / host 与当前地址不符 / 当前地址解析不出 origin。 */
    data object NotBound : KeyBindingState

    /** 绑定成立：当前地址的 origin 就是密钥的 host。可以继续读密钥。 */
    data class Expected(val origin: String) : KeyBindingState
}

/** 第一段：`hasKey && keyHost == BaseUrl.origin(baseUrl)` 才算 [KeyBindingState.Expected]。 */
internal fun bindingState(hasKey: Boolean, keyHost: String, baseUrl: String): KeyBindingState {
    if (!hasKey) return KeyBindingState.NotBound
    val origin = originOf(baseUrl)
    if (origin.isEmpty()) return KeyBindingState.NotBound
    if (keyHost.isBlank()) return KeyBindingState.NotBound
    return if (originOf(keyHost) == origin) {
        KeyBindingState.Expected(origin)
    } else {
        KeyBindingState.NotBound
    }
}

/**
 * 绑定判定的第二段结果。
 *
 * 读路径（`AppContainer.llmClient()` / `asrClient()` / `embeddingConfigured()` /
 * `importEmbedPort`）只认 [Usable]；[NotBound] 与 [NoKey] 一律返回 null（不新增任何网络请求）。
 */
internal sealed interface KeyBinding {
    /** 绑定成立且密文确实存在、非空：可以使用，[origin] 为该服务商 origin。 */
    data class Usable(val origin: String) : KeyBinding

    /** 绑定关系不成立（没有 / host 空 / host 不符 / 地址无效）。 */
    data object NotBound : KeyBinding

    /** 绑定关系成立但读不到密文（被清空、解密失败、还没写完）。 */
    data object NoKey : KeyBinding
}

/**
 * 第二段：**先调 [bindingState]，只有 [KeyBindingState.Expected] 时才调用 [readSecret]**。
 *
 * 传 lambda 而不是布尔值，是为了让「先判绑定、再读密钥」的次序在类型上就能被保证
 * （调用方没法在判断前把密钥读出来）；生产实现传 `{ secrets.get(name) }`。
 */
internal fun resolveBinding(
    hasKey: Boolean,
    keyHost: String,
    baseUrl: String,
    readSecret: () -> String?,
): KeyBinding = when (val state = bindingState(hasKey, keyHost, baseUrl)) {
    KeyBindingState.NotBound -> KeyBinding.NotBound
    is KeyBindingState.Expected -> {
        val secret = readSecret()
        if (secret.isNullOrBlank()) KeyBinding.NoKey else KeyBinding.Usable(state.origin)
    }
}

/** 纯 JVM 测试用重载：`hasSecret` 代表「密文存在且非空」。 */
internal fun resolveBinding(
    hasKey: Boolean,
    keyHost: String,
    baseUrl: String,
    hasSecret: Boolean,
): KeyBinding = resolveBinding(hasKey, keyHost, baseUrl) {
    if (hasSecret) HAS_SECRET_PLACEHOLDER else null
}

/**
 * 升级迁移是否需要清掉这个旧密钥（` I / §3.3.2）。
 *
 * 判据故意只有「有密钥 + 归属 host 为空」：v0.5.1 及更早没有 `keyHost` 字段，升级后必然是
 * 这个形状。不能把「有密钥 + host 恰好等于当前地址」也清掉——那不是升级留下的形状，
 * 且 v0.5.2 保存成功后的正常状态也不长这样。
 */
internal fun needsLegacyKeyReset(hasKey: Boolean, keyHost: String): Boolean =
    hasKey && keyHost.isBlank()

/**
 * 给用户看的「哪一家」（` F）。
 *
 * 只取主机名；**非默认端口带 `:port`**；userinfo（`user:pass@`）由 OkHttp 的
 * `HttpUrl.host` 天然剥掉，完整 URL / 路径 / 查询都不会出现在界面上。取不到时返回
 * 「这个地址」，调用方不要把原始字符串塞进 UI。
 */
internal fun hostForDisplay(origin: String): String {
    val raw = origin.trim()
    if (raw.isEmpty()) return HOST_FALLBACK
    // 与 BaseUrl.origin 同一宽容度：允许用户只填主机名（补 https 再解析）。
    val candidate = if (raw.contains("://")) raw else "https://$raw"
    val url = candidate.toHttpUrlOrNull() ?: return HOST_FALLBACK
    val host = url.host
    if (host.isBlank()) return HOST_FALLBACK
    val defaultPort = HttpUrl.defaultPort(url.scheme)
    return if (url.port == defaultPort) host else "$host:${url.port}"
}

/** 配置页密钥状态行（` F）：由绑定状态派生，不用布尔凑。*/
sealed interface KeyStatus {
    /** 密钥可用，且只发给 [origin] 这个服务商。 */
    data class Usable(val origin: String) : KeyStatus

    /** 有密钥标记但绑定不成立（host 空 / host 不符 / 密文读不到）：要重填一次。 */
    data object NeedsReentry : KeyStatus

    /** 还没有密钥。 */
    data object None : KeyStatus
}

/**
 * 密钥状态行的三档派生（` F / §3.9.5）。
 *
 * 注意 `hasKey=true + host 有值 + baseUrl 空` 归入 [KeyStatus.NeedsReentry]（不是「还没有密钥」）：
 * 用户填的地址被清空时，旧密钥仍然存在、只是配不上当前地址，说「还没有密钥」不真实。
 */
internal fun keyStatus(
    hasKey: Boolean,
    keyHost: String,
    baseUrl: String,
    hasSecret: Boolean,
): KeyStatus = when (val binding = resolveBinding(hasKey, keyHost, baseUrl, hasSecret)) {
    is KeyBinding.Usable -> KeyStatus.Usable(binding.origin)
    KeyBinding.NotBound, KeyBinding.NoKey ->
        if (hasKey) KeyStatus.NeedsReentry else KeyStatus.None
}

/** `resolveBinding(hasSecret=)` 用的占位：真实密钥不会出现在纯规则层。 */
private const val HAS_SECRET_PLACEHOLDER = "has-secret"

/** `hostForDisplay` 取不到主机名时的兜底文案（不得泄露原始 URL/userinfo）。 */
internal const val HOST_FALLBACK = "这个地址"
