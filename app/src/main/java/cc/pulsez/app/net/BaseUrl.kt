package cc.pulsez.app.net

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.util.concurrent.ConcurrentHashMap

/**
 * Base URL 归一化。
 *
 * 用户可能填 `https://api.deepseek.com`、`.../v1`、`.../v1/`，甚至直接粘贴
 * 服务商文档里整个 `.../v1/chat/completions`。这里统一成不带尾部 `/` 的 base，
 * `/v1` 的自动补全由调用方在 404 时重试一次并**记住**（见 [initial] / [remember]）。
 *
 * 协议（scheme）口径见 [withScheme]：用户可省略协议（`api.deepseek.com/v1`），保存时补
 * `https://`，`http://` 原样保留。
 */
internal object BaseUrl {

    /**
     * 进程内记住"某个归一化 base 实际可用的完整 base"。
     *
     * 产品里 AppContainer 可能每次发消息都新建 LlmClient（见 AppContainer.llmClient()），
     * 只把结果记在 client 实例上会导致每条消息都先吃一次 404；所以记在进程级。
     * 键是用户填的归一化 base，值是探测成功后真正可用的 base（例如补过 /v1 的）。
     */
    private val resolvedBases = ConcurrentHashMap<String, String>()

    /** 构造 client 时的初始 base：命中过缓存就直接用可用的那个。 */
    fun initial(raw: String): String {
        val normalized = normalize(raw)
        return resolvedBases[normalized] ?: normalized
    }

    /** 404 后补 /v1 成功时调用；只记成功结果，失败不改缓存。*/
    fun remember(configuredNormalized: String, working: String) {
        if (configuredNormalized.isEmpty() || working.isEmpty() || working == configuredNormalized) return
        resolvedBases[configuredNormalized] = working
    }

    /**
     * 归一化用户填的 base 地址。
     *
     * 用户可能填 `https://api.deepseek.com`、`.../v1`、`.../v1/`，甚至直接粘贴
     * 服务商文档里的整个 `.../v1/chat/completions`。这里统一成不带尾部 `/` 的 base，
     * `/v1` 的自动补全由调用方在 404 时重试一次并**记住**（见 [initial] / [remember]）。
     *
     * v0.5.6 旧实现先对**整个字符串** `trimEnd('/')`、再对**整个字符串**做端点后缀
     * `endsWith` 判断，于是 query / fragment 会被当成路径的一部分处理：
     * - `https://x/v1?route=foo/` 会丢掉 query 值末尾的斜线；
     * - `https://x/v1?route=/models` 会变成 `?route=`；
     * - 粘贴 `https://x/v1/chat/completions?api-version=1` 时端点后缀被 query 挡住剥不掉，
     *   下游 [endpoint] 会再追加一次接口后缀。
     *
     * 修法：先按 URL 结构解析（含 `://` 直接解析；无协议时补 `https://` 再解析；Host 由
     * OkHttp 校验）。解析成功**只操作 path**——去尾部 `/`、若 path 以 [ENDPOINT_SUFFIXES]
     * 之一结尾则剥掉该段并再去尾部 `/`——**保留 query**（Azure 式 `?api-version=` 必须原样
     * 带着），丢弃 fragment（fragment 本来就不会随请求发送）。
     *
     * 重组时用原串的 `scheme://authority` 前缀 + 新 path + 原 query，因此：
     * - 无协议输入仍返回**无协议**字符串（[initial] 的缓存键语义不变，[endpoint] 再补 scheme）；
     * - host 大小写、显式默认端口、百分号编码等字符**原样保留**（不像 `HttpUrl.toString()`
     *   那样做小写 host / 去默认端口的规范化）；
     * - 无 query / fragment 的标准地址输出与 v0.5.5 逐字符一致（`BaseUrlNormalizeTest` 锁定）。
     *
     * 解析失败（空地址 / 只有协议头 `https://` / `ws://` 等非 http(s) scheme / 非法 Host）回退
     * 旧字符串行为 [normalizeByString]，保持历史输出不变。
     *
     * 有意行为修复（旧实现整串处理导致的错误）：`https://models` 这类「host 恰好以
     * `/models` 结尾、没有 path」的地址，旧实现会剥成 `https:`；现在 host 不参与后缀判断。
     *
     * 依据：`
     * `
     * `。
     */
    fun normalize(raw: String): String {
        val value = raw.trim()
        if (value.isEmpty()) return ""
        val address = parseAddress(value) ?: return normalizeByString(value)
        return address.rebuildPath(normalizePath(address.path))
    }

    /**
     * base 的 **path** 是否已以 `/v1` 结尾（v0.5.6 旧实现是整串字符串尾判）。
     *
     * 旧实现 `base.endsWith("/v1")` 对带 query / fragment 的地址必然判错：
     * `https://x/v1?api-version=1` 末尾是 query，被判 false → 404 重试链里 [withV1] 会把
     * `/v1` 再拼一次（字符串回退时甚至拼进 query 值）；无协议历史值同样失效。
     * 现在按 URL 结构解析后只看 path（有无 query / fragment 都成立）；解析失败回退字符串尾判。
     */
    fun isV1(base: String): Boolean {
        val value = base.trim()
        if (value.isEmpty()) return false
        val address = parseAddress(value) ?: return value.endsWith("/v1")
        return address.pathEndsWithV1()
    }

    /**
     * 404 时补 `/v1` 重试用；已经以 `/v1` 结尾就原样返回。
     *
     * v0.5.5 base 可能带 query（如 `https://x/v1?api-version=1`）。字符串尾判会把
     * `/v1` 追加进 query 值（`...?api-version=1/v1`）。改成 URL 感知：可解析时按 **path**
     * 判断是否已以 `/v1` 结尾，未结尾就把 `v1` 作为路径段追加（在 query 之前）、保留 query、
     * 丢弃 fragment（fragment 本来就不会发送）；解析失败（空地址 / 非 http(s) scheme 等）
     * 回退原来的字符串拼接，保持历史行为不变。
     *
     * v0.5.6 追加分支改为与 [normalize] 共用 [parseAddress] 的位置切片，无协议历史值
     * 也能 URL 感知——`api.x.com?api-version=1` 由旧回退拼成 `api.x.com?api-version=1/v1`
     * （把 `/v1` 拼进 query 值），现在得到 `api.x.com/v1?api-version=1`（仍无协议，缓存键
     * 语义不变）；标准地址输出与 v0.5.5 逐字符一致（`BaseUrlNormalizeTest` 锁定）。
     *
     * 依据：` OkHttp 4.12.0
     * `HttpUrl.Builder.addPathSegments` 的「路径段追加在 query 之前」语义（本机实测）。
     */
    fun withV1(base: String): String {
        val value = base.trim()
        if (value.isEmpty()) return ""
        val address = parseAddress(value) ?: return if (isV1(value)) value else "$value/v1"
        if (address.pathEndsWithV1()) return value
        return address.rebuildPath(address.path.trimEnd('/') + "/v1")
    }

    /**
     * 给用户填的地址补协议（` 用户口径、§2 修复映射）。
     *
     * 口径：
     * - 已含 `://`（`https://` / `http://` / 自定义 scheme）→ **原样保留**，不静默改写用户输入；
     *   `http://` 不升级成 https 是为了忠实保存用户填的地址（`http://127.0.0.1:8080` 是
     *   `BaseUrlOriginTest.保留非默认端口` 已锁定的合法输入），**不代表**应用放开了明文：
     *   `app/src/main/res/xml/network_security_config.xml:7-12` 的明文例外只列
     *   `localhost` / `127.0.0.1` / `10.0.2.2`，手机连局域网 http（如 `http://192.168.x.x:11434`）
     *   会被系统明文策略拦下。能否连通由系统的网络明文策略决定，与这里的保存口径无关。
     * - 不含 `://` → 补 `https://`（服务商文档常把地址写成 `api.deepseek.com/v1`）。
     *   依据：`（根因已核实）——`net/JsonHttp.kt:46` 把拼好的串
     *   直接交给 `Request.Builder().url()`，而 OkHttp 要求绝对 URL（含 scheme），否则抛
     *   `IllegalArgumentException`，被 `LlmClient` / `EmbeddingClient` 映射成
     *   `BadResponse("invalid base url")`。
     *
     * 无状态纯函数：保存前归一化（`ui/config/CredentialSaveWorkflow.kt`）与历史遗留值的
     * 请求侧防御（[endpoint]）共用同一份判断，避免两处口径漂移。
     *
     * @return trim 后的地址；空串返回空串（空地址由调用方按「地址无效」处理）
     */
    fun withScheme(raw: String): String {
        val value = raw.trim()
        if (value.isEmpty()) return ""
        return if (value.contains("://")) value else "https://$value"
    }

    /**
     * 拼接接口地址，例如 endpoint("https://api.x.com/v1", "models")。
     *
     * 防御历史遗留值：v0.5.3 及更早的保存流程只 `trim` 后原样落盘，无协议地址
     * （如 `api.deepseek.com/v1`）会留在设置里；如果直接拼给 OkHttp 会抛
     * `IllegalArgumentException`（被映射成 `invalid base url`，聊天/找旧事全部请求失败，
     * 但保存时却显示成功）。这里统一先补 scheme 再拼路径，保证这类存量值能正常出网；
     * [normalize] / [initial] 的语义保持不变（缓存键仍是用户填的归一化值，不引入二次迁移）。
     *
     * v0.5.5 base 可能带 query / fragment（保存流程接受这种地址，如
     * `https://x/v1?api-version=1`）。旧实现是**字符串直接追加**，会把接口后缀拼进 query 值
     * （`.../v1?api-version=1/chat/completions`，实际请求 path 仍是 `/v1`）。改成 URL 感知：
     * - 只剥掉与本次要拼接的 [path] 完全匹配的端点后缀，避免 [initial] 已归一化后再次把
     *   租户路径里的 `/models` 或 `/embeddings` 当成当前接口后缀；
     * - 可解析：用 `HttpUrl.newBuilder()` 把 [path] 作为路径段追加到 path 末尾（在 query 之前），
     *   保留 query，丢弃 fragment（fragment 本来就不会发送）；
     * - 解析失败（空地址 / 自定义 scheme 等）→ 回退旧的字符串拼接，保持历史行为。
     *
     * 依据：` OkHttp 4.12.0
     * `HttpUrl.Builder.addPathSegments`/`fragment(null)` 语义（本机实测：path 在 query 之前、
     * query 保留、fragment 丢弃）；二次归一化边界依据：[initial] 与 [normalizePath]。
     */
    fun endpoint(base: String, path: String): String {
        val suffix = path.trimStart('/')
        val normalizedBase = withScheme(normalizeForEndpoint(base, suffix))
        val url = normalizedBase.toHttpUrlOrNull()
        if (url != null && suffix.isNotEmpty()) {
            return url.newBuilder()
                .addPathSegments(suffix)
                .fragment(null)
                .build()
                .toString()
        }
        return normalizedBase + "/" + suffix
    }

    /**
     * 请求侧只剥本次要追加的同一接口后缀。[initial] / [normalize] 已经负责保存值归一化，
     * 这里再次做通用后缀剥离会误删租户路径末尾的 `/models` 或 `/embeddings`。
     * 解析失败时沿用 [normalize] 的原字符串回退，之后仍由 [endpoint] 的旧拼接分支收尾。
     *
     * 依据：本文件 [initial] 28-30 行与 [normalizePath] 归一化规则；影响仅限 [endpoint] 的 base。
     */
    private fun normalizeForEndpoint(raw: String, endpointSuffix: String): String {
        val value = raw.trim()
        if (value.isEmpty()) return ""
        val address = parseAddress(value)
            ?: return normalizeByString(value)
        return address.rebuildPath(normalizePath(address.path, endpointSuffix))
    }

    /**
     * 取 origin（`scheme://host[:port]`，不带路径/查询/片段）。
     *
     * 用途：DeepSeek 的 `/v1/tools/asr`、`/v1/tools/tts` 是**根路径下带 `/v1`** 的私有端点，
     * 而聊天 base 可能是 `https://api.deepseek.com`、`.../v1`、`.../v1/`，甚至用户粘进来的
     * 整个 `.../v1/chat/completions`。ASR 路径以官方 `/v1/tools/asr` 为准，所以先归一化到
     * origin 再拼，避免出现 `.../v1/v1/tools/asr` 或 `.../chat/completions/v1/tools/asr`。
     *
     * 依据（`POST {origin}/v1/tools/asr`）、
     * §3.5.1（`/v1/tools/...` 带 /v1，与根路径的 chat/files 不同）。
     *
     * @return 解析失败/空串返回空串（调用方按「地址无效」处理）
     */
    fun origin(raw: String): String {
        val normalized = normalize(raw)
        if (normalized.isEmpty()) return ""
        // 用户常只填主机名（`api.deepseek.com/v1`，省略协议）：补 https 再交给 OkHttp 解析，
        // 主机大小写、端口、IPv6、userinfo 都由 HttpUrl 处理（与 DeepSeekEndpoint 同一做法）。
        val candidate = if (normalized.contains("://")) normalized else "https://$normalized"
        val url = candidate.toHttpUrlOrNull() ?: return ""
        return url.newBuilder()
            .encodedPath("/")
            .query(null)
            .fragment(null)
            .build()
            .toString()
            .trimEnd('/')
    }

    /**
     * 把 base 地址拆成「前缀 = 原样的 `scheme://authority`（无协议时就是 authority）+
     * 原始 path + 原始 query」的位置信息（v0.5.6）。fragment 不在保留范围内。
     *
     * 只用 `HttpUrl` 做**可解析性 + Host 合法性**校验，真正的字符重组仍基于原串位置切片：
     * `HttpUrl.toString()` 会做 host 小写、去显式默认端口等规范化，直接拿它当输出会让
     * 标准地址不能逐字符保持不变（用户填什么就存什么，[initial] 的缓存键也要稳定）。
     *
     * 已知边界：是否「带协议」沿用 [withScheme] 的 `contains("://")` 口径。若 `://` 只出现在
     * query / path 里而地址本身没协议（如 `api.x.com/v1?next=https://x`），会解析失败回退
     * 旧字符串行为——与 [withScheme] 一致，属本次未覆盖的尽力而为边界。
     *
     * @return null = 不可解析（空串 / 只有协议头 / 非 http(s) scheme / 非法 Host），
     *   调用方回退旧的字符串行为。
     */
    private fun parseAddress(raw: String): ParsedAddress? {
        val value = raw.trim()
        if (value.isEmpty()) return null
        val hadScheme = value.contains("://")
        val candidate = if (hadScheme) value else "https://$value"
        // Host 合法性交给 OkHttp（它也会拒绝无 host、坏端口、带空格的输入）。
        candidate.toHttpUrlOrNull() ?: return null

        // authority 的结束位置 = path / query / fragment 的起点。
        val authorityStart = if (hadScheme) value.indexOf("://") + 3 else 0
        var authorityEnd = value.length
        for (index in authorityStart until value.length) {
            val char = value[index]
            if (char == '/' || char == '?' || char == '#') {
                authorityEnd = index
                break
            }
        }
        val hashIndex = value.indexOf('#', authorityEnd)
        val queryIndex = value.indexOf('?', authorityEnd)
        // `?` 出现在 `#` 之后属于 fragment 内容，不算 query。
        val hasQuery = queryIndex >= 0 && (hashIndex < 0 || queryIndex < hashIndex)
        val pathEnd = when {
            hasQuery -> queryIndex
            hashIndex >= 0 -> hashIndex
            else -> value.length
        }
        val queryEnd = when {
            !hasQuery -> -1
            hashIndex >= 0 && hashIndex > queryIndex -> hashIndex
            else -> value.length
        }
        return ParsedAddress(
            source = value,
            authorityEnd = authorityEnd,
            pathEnd = pathEnd,
            queryStart = if (hasQuery) queryIndex else -1,
            queryEnd = queryEnd,
        )
    }

    /**
     * [parseAddress] 的切片结果，所有边界都指向 trim 后的原串 [source]：
     * - `[0, authorityEnd)`：原样的 `scheme://authority`（无协议输入时就是 authority）；
     * - `[authorityEnd, pathEnd)`：原始 path（可能为空、可能带尾部 `/`）；
     * - `[queryStart, queryEnd)`：原始 query（含 `?`；`queryStart == -1` 表示没有 query）；
     * - fragment 不保留（请求本来就不会发送）。
     */
    private class ParsedAddress(
        private val source: String,
        private val authorityEnd: Int,
        private val pathEnd: Int,
        private val queryStart: Int,
        private val queryEnd: Int,
    ) {
        val path: String get() = source.substring(authorityEnd, pathEnd)

        /** 只看 path 的 `/v1` 尾判（有无 query / fragment 都成立）。*/
        fun pathEndsWithV1(): Boolean = path.trimEnd('/').endsWith("/v1")

        /** 保留原前缀与 query，只替换 path。 */
        fun rebuildPath(newPath: String): String {
            val query = if (queryStart >= 0) source.substring(queryStart, queryEnd) else ""
            return source.substring(0, authorityEnd) + newPath + query
        }
    }

    /** 只对 path 做归一化：去尾 `/` → 剥端点后缀 → 再去尾 `/`（v0.5.6）。*/
    private fun normalizePath(path: String, endpointSuffix: String? = null): String {
        var value = path.trimEnd('/')
        val matchingSuffix = endpointSuffix?.trim('/')?.takeIf { it.isNotEmpty() }
        if (matchingSuffix != null) {
            val suffix = "/$matchingSuffix"
            return if (value.endsWith(suffix)) value.removeSuffix(suffix).trimEnd('/') else value
        }
        for (suffix in ENDPOINT_SUFFIXES) {
            if (value.endsWith(suffix)) {
                value = value.removeSuffix(suffix).trimEnd('/')
                break
            }
        }
        return value
    }

    /** 解析失败时的旧行为（v0.5.5 及更早）：整串去尾 `/` + 整串剥端点后缀。*/
    private fun normalizeByString(value: String): String {
        var result = value.trimEnd('/')
        for (suffix in ENDPOINT_SUFFIXES) {
            if (result.endsWith(suffix)) {
                result = result.removeSuffix(suffix).trimEnd('/')
                break
            }
        }
        return result
    }

    private val ENDPOINT_SUFFIXES = listOf("/chat/completions", "/embeddings", "/models")
}
