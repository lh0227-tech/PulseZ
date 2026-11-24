package cc.pulsez.app.update

/**
 * 一个版本清单来源（按顺序降级尝试，任意一个成功即用）。
 *
 * 只放公开静态文件的 URL，不带 query、不带 token、不带设备标识（隐私基线）。
 *
 * @property id 人类可读的来源标识，只用于代码/测试可读性，**不打印、不上报**。
 * @property url 完整的 version.json 地址（主源/备源都指向同一份清单）。
 */
data class UpdateSource(
    val id: String,
    val url: String,
)

/**
 * 更新源清单（用户 2026-11-21 已定 旧稿的 `dl.pulsez.cc` 不再使用）。
 *
 * - 主源：`releases.pulsez.cc`（将来解析到 桶的纯静态文件；`pulsez.cc` 是首页，不放清单）；
 * - 备源：GitHub Release 资产直链 `releases/latest/download/version.json`（不调 REST API，
 *   避开未认证 60 次/小时/IP 限流）。
 *
 * **未核实（#13–14）**：域名解析/证书 绑定、GitHub 仓库是否公开且已有 Release，
 * 本机无法确认；两个源当前都可能不可用——App 侧的行为是**静默失败**（不打扰用户），
 * 手动检查给一句「没连上」。真实链路端到端验证留给 QA/发布节点。
 */
object UpdateSources {

    /** 主源 id（代码可读性用）。 */
    const val PRIMARY_ID: String = "releases"

    /** 主源清单地址（用户定的主源）。 */
    const val PRIMARY_URL: String = "https://releases.pulsez.cc/stable/version.json"

    /** 备源 id。 */
    const val FALLBACK_ID: String = "github"

    /**
     * 备源清单地址：GitHub Release 里的 version.json 资产。
     *
     * 依据——`/releases/latest/download/<asset>` 是官方推荐的「永远指向最新版」
     * 直链，不需要 API、不吃 60/h 限流。仓库名按用户 2026-11-21 给的最新口径（`lh0227-tech/PulseZ`）。
     */
    const val FALLBACK_URL: String =
        "https://github.com/lh0227-tech/PulseZ/releases/latest/download/version.json"

    /**
     * 默认降级链：主源 → 备源。
     *
     * 测试把 base URL 换成本机 MockWebServer；生产永远用上面两个常量。
     */
    fun defaults(): List<UpdateSource> = listOf(
        UpdateSource(id = PRIMARY_ID, url = PRIMARY_URL),
        UpdateSource(id = FALLBACK_ID, url = FALLBACK_URL),
    )
}
