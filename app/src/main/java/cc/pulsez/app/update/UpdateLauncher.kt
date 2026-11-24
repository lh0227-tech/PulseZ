package cc.pulsez.app.update

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * 「去下载」的出口：**只**用系统浏览器打开 https 下载地址（决策，零新权限）。
 *
 * 依据：
 * - Phase 1：普通 https 链接不需要任何权限，安装动作由浏览器
 *   （它已持有安装权限）完成；App 不声明 `REQUEST_INSTALL_PACKAGES`、不做后台下载/静默安装；
 * - Android 官方 `Intent#ACTION_VIEW`：https 链接交给浏览器
 *   https://developer.android.com/reference/android/content/Intent#ACTION_VIEW
 *
 * 为什么**不先** `resolveActivity` 再决定显不显示入口：targetSdk 30+ 的软件包可见性
 * （package visibility）会让「没有 `<queries>` 声明」的 App 把系统的 `resolveActivity`
 * 查成 null，从而误判「没有浏览器」、把真入口藏掉。这里直接尝试 `startActivity`，
 * 只按官方异常 `ActivityNotFoundException` 给人话出路（不造假入口，也不误杀真浏览器）。
 */
object UpdateLauncher {

    /**
     * 构造「打开下载页」的 Intent；url 不是合法 https 地址时返回 null。
     *
     * 只接受 https：清单里的 `apkUrl` 已由白名单校验过，这里是第二道防线，避免把 http/其它
     * scheme 交给外部 App（exported intent 的通用安全习惯）。
     */
    fun downloadIntent(url: String): Intent? {
        val parsed = url.toHttpUrlOrNull() ?: return null
        if (parsed.scheme != "https") return null
        return Intent(Intent.ACTION_VIEW, Uri.parse(parsed.toString()))
            // 调用方可能来自 Application/Compose 根组合之外：显式 NEW_TASK，
            // 避免 "Calling startActivity() from outside of an Activity context requires
            // the FLAG_ACTIVITY_NEW_TASK flag" 的直接崩。
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    /**
     * 用浏览器打开下载页；成功返回 true。
     *
     * 手机没有能处理 https 的 App（极少见）→ false，调用方给一句人话
     * （`UpdateCopy.BROWSER_UNAVAILABLE`），不静默、不崩。
     */
    fun openDownloadPage(context: Context, url: String): Boolean {
        val intent = downloadIntent(url) ?: return false
        return try {
            context.startActivity(intent)
            true
        } catch (notFound: ActivityNotFoundException) {
            false
        } catch (denied: SecurityException) {
            // 个别定制系统会在这里抛安全异常（例如企业策略禁用浏览器）；按「打不开」处理。
            false
        }
    }
}
