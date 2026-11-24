package cc.pulsez.app.update

import cc.pulsez.app.data.settings.SettingsStore
import kotlinx.coroutines.flow.first

/**
 * 「检查更新」需要落盘的少量本地状态（DataStore 键见 `SettingsStore`）。
 *
 * 抽成接口只为了**可离线单测**节流/忽略逻辑：生产实现 [SettingsUpdatePrefs] 走 DataStore，
 * 测试用内存 fake（不依赖 Robolectric/真机）。不是「未来扩展」的抽象。
 *
 * @property lastCheckAt 最近一次检查**尝试**的时间（epoch millis；成功/失败都写，0 = 没查过）。
 * @property ignoredVersionCode 用户点过「稍后」的版本号（0 = 没跳过）。同版本不再自动提示。
 * @property pendingVersionCode 已经弹过一次「有新版本」提示的版本号（0 = 没弹过）。同版本不重复打扰。
 * @property agreed 用户是否已通过首启门槛页（未通过前**不联网**）。
 */
data class UpdatePrefsSnapshot(
    val lastCheckAt: Long = 0L,
    val ignoredVersionCode: Long = 0L,
    val pendingVersionCode: Long = 0L,
    val agreed: Boolean = false,
)

/** 更新状态读写口（生产 = DataStore；测试 = 内存）。 */
interface UpdatePrefs {

    suspend fun snapshot(): UpdatePrefsSnapshot

    /** 记录一次检查尝试的时间（成功/失败都写：失败也写，避免每次冷启动都联网）。 */
    suspend fun recordCheck(at: Long)

    /** 记录「已经弹过一次提示」的版本号。 */
    suspend fun recordPendingVersion(versionCode: Long)

    /** 记录「稍后」跳过的版本号。 */
    suspend fun setIgnoredVersion(versionCode: Long)
}

/**
 * 生产实现：把更新状态读写落到现有 `SettingsStore`（DataStore 键 snake_case，发布后不改名）。
 *
 * 依据 键表（`update_last_check_at` / `update_ignored_version_code` /
 * `update_pending_version_code`）写作用域只允许改 `SettingsStore` 的这一组新键。
 */
internal class SettingsUpdatePrefs(
    private val settings: SettingsStore,
) : UpdatePrefs {

    override suspend fun snapshot(): UpdatePrefsSnapshot {
        val current = settings.flow.first()
        return UpdatePrefsSnapshot(
            lastCheckAt = current.updateLastCheckAt,
            ignoredVersionCode = current.updateIgnoredVersionCode,
            pendingVersionCode = current.updatePendingVersionCode,
            // v0.5.2（F）：与首启门槛**同一个判据**。
            // 旧口径 `agreedVersion > 0` 会把「同意过 v1 协议」的升级用户当成已同意，
            // 于是既看不到新增的出网/清空说明，又直接放行新的后台检查；降级安装（agreedVersion
            // 比当前版本大）同样要重新同意。不得写成 `>= 2` 与 `!= 2` 两套判据。
            agreed = !SettingsStore.needsAgreement(current.agreedVersion),
        )
    }

    override suspend fun recordCheck(at: Long) {
        settings.setUpdateCheckResult(at)
    }

    override suspend fun recordPendingVersion(versionCode: Long) {
        settings.setUpdatePendingVersionCode(versionCode)
    }

    override suspend fun setIgnoredVersion(versionCode: Long) {
        settings.setUpdateIgnoredVersionCode(versionCode)
    }
}
