package cc.pulsez.app.data.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 协议版本与门槛判据。
 *
 * 纯 JVM：`SettingsStore.needsAgreement` 是纯函数，不碰 DataStore/Android。
 * 判据口径（ E/F）：
 * - 0（没过门槛）/ 1（ 及更早）→ 要重新同意；
 * - 2（当前版本）→ 已同意；
 * - 3（降级安装：数据里的同意版本比当前新）→ 也要重新同意（用 `!=` 不是 `<`）。
 *
 * 门槛页与 `update/UpdatePrefs.agreed` 必须共用这一个函数（不允许两套判据）。
 */
class AgreementVersionTest {

    @Test
    fun agreementVersionIsTwo() {
        assertEquals("协议版本必须是 2", 2, SettingsStore.AGREEMENT_VERSION)
    }

    @Test
    fun neverAgreedNeedsAgreement() {
        assertTrue("没同意过（0）要过门槛", SettingsStore.needsAgreement(0))
    }

    @Test
    fun legacyVersionOneNeedsAgreement() {
        assertTrue("同意过 v1 的升级用户要重看一次", SettingsStore.needsAgreement(1))
    }

    @Test
    fun currentVersionDoesNotNeedAgreement() {
        assertFalse(
            "已同意当前版本（2）不再过门槛",
            SettingsStore.needsAgreement(SettingsStore.AGREEMENT_VERSION),
        )
    }

    @Test
    fun downgradeInstallNeedsAgreement() {
        assertTrue(
            "降级安装（同意版本 3 > 当前 2）同样要重新同意",
            SettingsStore.needsAgreement(SettingsStore.AGREEMENT_VERSION + 1),
        )
    }
}
