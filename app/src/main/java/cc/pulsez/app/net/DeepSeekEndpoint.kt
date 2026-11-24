package cc.pulsez.app.net

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * DeepSeek 官方端点的统一判定。
 *
 * ## 为什么不能再用裸子串
 * 旧实现是 `baseUrl.contains("deepseek")`，三类地址会被误判：
 * - 镜像/代理：`https://deepseek-proxy.example.com/v1`（其实是第三方，不认 DeepSeek 私有字段）；
 * - 钓鱼/伪装域名：`https://api.deepseek.com.evil.com/v1`；
 * - 任何路径/查询里恰好带 `deepseek` 的地址。
 * 误判的代价是：把 `thinking` / `tools:[{"type":"deepseek_web_search"}]` 发给不认识它们的服务商
 * （可能 400），以及 UI 上给出"能思考 / 能联网"的假开关。
 *
 * ## 判定规则（按域名边界，不按子串）
 * - `host == api.deepseek.com`，或
 * - `host` 以 `.deepseek.com` 结尾（如 `xxx.deepseek.com`）。
 *
 * 允许大小写、端口、路径，也允许用户只填主机名（`api.deepseek.com/v1`，省略协议）。
 * 依据 （字段归属）、§3.5（`deepseek_web_search` 是私有工具类型）。
 */
internal object DeepSeekEndpoint {

    /** DeepSeek 官方 API 主机名。 */
    private const val OFFICIAL_HOST = "api.deepseek.com"

    /** DeepSeek 域名后缀（**含前导点**，保证按域名标签边界匹配，`api.deepseek.com.evil.com` 不命中）。 */
    private const val DOMAIN_SUFFIX = ".deepseek.com"

    fun matches(baseUrl: String): Boolean {
        val raw = baseUrl.trim()
        if (raw.isEmpty()) return false
        // 用户常直接粘贴 `api.deepseek.com/v1`（没有协议）：补一个再交给 OkHttp 解析，
        // 比手写 split/正则更可靠（主机名大小写、端口、userinfo、IPv6 都由 HttpUrl 处理）。
        val candidate = if (raw.contains("://")) raw else "https://$raw"
        val host = candidate.toHttpUrlOrNull()?.host?.lowercase() ?: return false
        return host == OFFICIAL_HOST || host.endsWith(DOMAIN_SUFFIX)
    }
}

/**
 * 全工程**唯一**的 DeepSeek 端点判定入口。
 *
 * `Settings.isDeepSeekProvider()`、聊天请求、群聊回复、记忆抽取、能力探测都走这里，
 * 不允许再出现 `contains("deepseek")` 这类裸子串判定。
 */
internal fun isDeepSeekEndpoint(baseUrl: String): Boolean = DeepSeekEndpoint.matches(baseUrl)

/**
 * 内部请求在 DeepSeek 端点下是否显式关思考：
 * - `false` = 发 `thinking:{"type":"disabled"}`（DeepSeek 官方）；
 * - `null` = 不传字段（其它服务商保持原行为，绝不发它们不认识的字段）。
 *
 * 依据（服务端默认思考开、effort 默认 high）、§2.1（不传 ≠ 关）。
 */
internal fun internalThinkingFor(
    baseUrl: String,
    deepSeekCheck: (String) -> Boolean = ::isDeepSeekEndpoint,
): Boolean? = if (deepSeekCheck(baseUrl)) false else null

/**
 * 把「用户看不见的后台请求」改写成 DeepSeek 端点下的安全形态：**显式关思考**。
 *
 * ## 适用的四类请求
 * 1. 群聊回复（`data/group/GroupPortsImpl.kt`，主回 256 / 短反应 48 token）；
 * 2. 群聊记忆抽取（`core/GroupTurnMemory.kt`，2000 token）；
 * 3. 1:1 增量抽取 / 交接摘要（`core/ChatEngine.kt`，2000 token）；
 * 4. 能力探测（`net/ModelProbe.kt`，`max_tokens=1`）。
 *
 * 这些请求都不给用户看，服务端"思考默认开、effort 默认 high"只会：
 * - 把很小的 `max_tokens` 全花在思考上 → **正文为空**：群聊 256 会让整轮沉默、探测 1 必然判失败；
 * - 多花钱、多等时间（思考内容按输出计费）。
 * 依据（关思考时不发 `reasoning_effort`，这里由 Payloads 保证）。
 *
 * > 1:1 用户可见回复**不**走这里：它按用户的「深度思考」开关决定发不发 thinking（末条）。
 */
internal fun ChatRequest.asInternalRequest(baseUrl: String): ChatRequest =
    copy(thinking = internalThinkingFor(baseUrl))
