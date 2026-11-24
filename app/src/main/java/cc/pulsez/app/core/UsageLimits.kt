package cc.pulsez.app.core

import cc.pulsez.app.data.repo.UsageSnapshot

/**
 * 日/月花费上限的判定与提示（缺陷 「到 100% 停止发起新请求」）。
 *
 * 语义（与设置页口径一致）：
 * - 上限单位是「分」（`dailyLimitYuanX100` / `monthlyLimitYuanX100`），**0 = 不限制**；
 * - 已花 `>=` 上限即视为到顶：这一条请求不再发（历史仍可看）；
 * - 上限改完立即生效：调用方每次发送前都重新 `flow.first()` 读当前值，本对象不缓存任何东西。
 *
 * 纯函数、无 Android 依赖，可直接单测。
 */
object UsageLimits {

    /**
     * 日上限到顶的提示（可操作：告诉用户怎么恢复）。
     *
     * v0.4 统一文案（附录 A 4.9）：
     * 事实 + 一个动作，不留"明天接着聊"的软挽留。
     */
    const val DAILY_LIMIT_MESSAGE: String = "今天到上限了。想接着聊，就把上限调高。"

    /** 月上限到顶的提示（结构与日上限一致；事实换成"这个月"，否则会误报"今天到上限"）。 */
    const val MONTHLY_LIMIT_MESSAGE: String = "这个月到上限了。想接着聊，就把上限调高。"

    /**
     * 是否已经到/超过上限。
     *
     * @param snapshot 当前记账快照（`UsageStore.flow` 的值，跨天/跨月会自动归零）
     * @param dailyLimitYuanX100 日上限（分）；0 = 不限制
     * @param monthlyLimitYuanX100 月上限（分）；0 = 不限制
     */
    fun isOverLimit(
        snapshot: UsageSnapshot,
        dailyLimitYuanX100: Int,
        monthlyLimitYuanX100: Int,
    ): Boolean {
        if (dailyLimitYuanX100 > 0 && snapshot.todayYuan >= dailyLimitYuanX100 / 100.0) return true
        if (monthlyLimitYuanX100 > 0 && snapshot.monthYuan >= monthlyLimitYuanX100 / 100.0) return true
        return false
    }

    /**
     * 超限时给 UI 的提示文案；没超限返回 null。
     *
     * 日上限优先于月上限：用户更关心"今天还能不能聊"，且日上限通常更早触发。
     */
    fun message(
        snapshot: UsageSnapshot,
        dailyLimitYuanX100: Int,
        monthlyLimitYuanX100: Int,
    ): String? {
        if (dailyLimitYuanX100 > 0 && snapshot.todayYuan >= dailyLimitYuanX100 / 100.0) {
            return DAILY_LIMIT_MESSAGE
        }
        if (monthlyLimitYuanX100 > 0 && snapshot.monthYuan >= monthlyLimitYuanX100 / 100.0) {
            return MONTHLY_LIMIT_MESSAGE
        }
        return null
    }
}
