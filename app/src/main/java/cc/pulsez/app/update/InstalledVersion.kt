package cc.pulsez.app.update

import android.content.Context

/**
 * 当前安装版本的 `versionCode`（用于和清单比较，唯一比较依据）。
 *
 * 为什么读 PackageManager 而不是 `BuildConfig.VERSION_CODE`：`BuildConfig.VERSION_CODE` 是**编译时**
 * 常量，和用户手机上实际装的 APK 在正常升级路径下一致；但读系统里真实的包信息更接近
 * 「现在装的是哪一版」的事实，也和 的 `PackageInfo.longVersionCode`（API 28+，
 * `Long`）口径一致。取不到（极端情况）返回 0，等价于「任何正版本都比它新」——保守，不会卡死更新。
 *
 * 依据：https://developer.android.com/reference/android/content/pm/PackageInfo#getLongVersionCode()
 */
object InstalledVersion {

    /**
     * 读本 App 的 `longVersionCode`；失败返回 0。
     *
     * `getPackageInfo(String, Int)` 在 API 33 起标记 deprecated（新签名 `PackageInfoFlags` 是
     * API 33+），minSdk 31 必须用旧重载，故显式 suppress；行为在 API 31–36 一致。
     */
    @Suppress("DEPRECATION")
    fun versionCode(context: Context): Long = runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0).longVersionCode
    }.getOrDefault(0L)
}
