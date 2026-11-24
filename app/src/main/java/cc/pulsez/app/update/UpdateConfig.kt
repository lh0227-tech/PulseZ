package cc.pulsez.app.update

import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * 「检查更新」的固定参数（v0.5.1 决策：只检查 + 非阻断提示 + 跳浏览器下载）。
 *
 * 依据：
 * - = A（零新权限，不加 `REQUEST_INSTALL_PACKAGES`）；
 *   §4.1 触发与节流（24h、失败静默、debug 不自动查、不判断网络类型）；
 *   §5.4 feature flag 常量；
 * - （失败文案说事实 + 给出路）；
 * - （不制造不存在功能：本轮不做 App 内下载/安装/通知）。
 *
 * 这里**没有**任何密钥、token、设备标识：只放公开的静态参数。
 */
internal object UpdateConfig {

    /** 清单协议版本：只认 1；大于或小于 1 都拒绝（「必须 ≤ 客户端认识的版本」）。*/
    const val MANIFEST_SCHEMA: Int = 1

    /** 客户端只接受 stable 渠道（beta 只给内部测试）。*/
    const val CHANNEL_STABLE: String = "stable"

    /** 本项目 minSdk（与 app/build.gradle.kts 的 minSdk 31 对齐）；清单 minSdk 大于它就装不上。 */
    const val PROJECT_MIN_SDK: Int = 31

    /** 自动检查最短间隔：24 小时（「每天最多一次」）。*/
    const val CHECK_INTERVAL_MS: Long = 24L * 60L * 60L * 1000L

    /**
     * 清单响应体上限。
     *
     * 正常 version.json 约 1 KB；这里限 64 KiB，避免被投毒/异常源灌一个超大响应进解析器。
     * 依据（清单是约 1 KB 的静态文件）、§6.4（解析失败按「这一源失败」处理）。
     */
    const val MAX_MANIFEST_BYTES: Long = 64L * 1024L

    /**
     * APK 大小上限（清单预检，**本轮不下载**）。
     *
     * `sizeBytes` 必须 > 0 且 < 20 MB。这里只用来拒绝明显不可能/被投毒的清单；
     * 不下载 APK，所以不做逐字节比对。
     */
    const val MAX_APK_BYTES: Long = 20L * 1024L * 1024L

    /** `apkMirrors` 最多 4 个。*/
    const val MAX_APK_MIRRORS: Int = 4

    /**
     * `apkUrl`/`apkMirrors` 的域名白名单（只校验**清单里声明的 URL**）。
     *
     * 依据 白名单口径 + §3.3「GitHub 资产 302 到 release-assets 的最终 URL
     * 不参与白名单」。用户 2026-11-21 定的更新源：
     * - 主源 `releases.pulsez.cc`（将来解析到 桶；`dl.pulsez.cc` 不启用）；
     * - 备源 GitHub Releases（`github.com` 直链，不调 REST API）。
     * 域名/仓库当前可能未就绪（#13–14），但 App 侧只写常量，不做可达性假设。
     */
    val ALLOWED_DOWNLOAD_HOSTS: Set<String> = setOf("releases.pulsez.cc", "github.com")

    /** 检查请求超时：独立轻量客户端，短超时，不拖冷启动（建议 connect 3s / read 5s / 整次 ≤ 8s）。*/
    const val CONNECT_TIMEOUT_MS: Long = 3_000L
    const val READ_TIMEOUT_MS: Long = 5_000L
    const val CALL_TIMEOUT_MS: Long = 8_000L

    /**
     * 自动检查总开关。
     *
     * 决定首版**硬编码 true**（不做远程 kill switch：出问题把 `stable/version.json`
     * 的 versionCode 指回当前版本即可降级）。debug 构建的关闭在 [AUTO_CHECK_ON_DEBUG]，
     * 由 [cc.pulsez.app.AppContainer] 接线时合并，**不写进这个常量**——否则单测/注入方默认值
     * 也会跟着构建类型漂移。
     */
    const val AUTO_CHECK_ON_LAUNCH: Boolean = true

    /**
     * debug 构建是否也自动检查：**否**。
     *
     * 依据——debug 包 `applicationIdSuffix=".debug"` 且签名不同，自动查只会
     * 查到 release 的新版，没有意义、还多一次联网；**手动检查在 debug 下仍可用**。
     */
    val AUTO_CHECK_ON_DEBUG: Boolean = false

    /**
     * 手动检查失败的一句人话（「这次没连上发布页，过一会儿再试。」）。
     *
     * 放在这里是因为 [UpdateUiState.ManualFailed] 需要把消息带进状态；`UpdateCopy.MANUAL_FAILED`
     * 引用同一个常量，保证 UI 与状态只有一份字面量（改文案只改一处）。
     */
    const val MANUAL_FAILED_MESSAGE: String = "这次没连上发布页，过一会儿再试。"

    /**
     * 检查专用的 OkHttp 客户端。
     *
     * 刻意**不复用** `net/HttpFactory` 的全局客户端：那个客户端为流式长回答把
     * `readTimeout/callTimeout` 置 0，检查一个 1 KB 静态文件不能跟着无限等。
     * 也刻意不装任何拦截器：不打印 URL 内容/响应正文/设备信息（隐私基线）。
     *
     * 依据（超时与「不重试」）（日志不打印正文与密钥）。
     */
    fun newClient(): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .readTimeout(READ_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .callTimeout(CALL_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        // 不自动重连/重试：两个源各最多 1 次（「最多 2 个源各 1 次，不重试」）。
        .retryOnConnectionFailure(false)
        .build()

    /**
     * 自动检查是否到期。
     *
     * - 从没查过（0）→ 到期；
     * - 距上次 ≥ 24h → 到期（「> 24h 才请求」口径「≥24h 才请求」取边界含）；
     * - 时钟回拨（上次时间 > 现在）→ 视为到期并重写时间戳，不因改时间卡死。
     */
    fun isAutoCheckDue(now: Long, lastCheckAt: Long): Boolean {
        if (lastCheckAt <= 0L) return true
        if (lastCheckAt > now) return true
        return now - lastCheckAt >= CHECK_INTERVAL_MS
    }
}
