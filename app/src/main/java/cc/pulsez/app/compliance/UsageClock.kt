package cc.pulsez.app.compliance

/**
 * 连续使用时长统计。
 *
 * 只累加"应用在前台"的时间；锁屏/切后台不计。纯逻辑，便于单测。
 */
class UsageClock(
    private val thresholdMillis: Long = DEFAULT_THRESHOLD_MS,
) {

    private var accumulated = 0L
    private var foregroundSince: Long? = null
    private var lastReminderAt = 0L

    fun onForeground(now: Long) {
        if (foregroundSince == null) foregroundSince = now
    }

    fun onBackground(now: Long) {
        foregroundSince?.let {
            accumulated += (now - it).coerceAtLeast(0)
            foregroundSince = null
        }
    }

    /** 当前累计前台时长。 */
    fun total(now: Long): Long =
        accumulated + (foregroundSince?.let { (now - it).coerceAtLeast(0) } ?: 0)

    /**
     * 是否该提醒。每跨过一个阈值提醒一次，提醒后需要再过一个阈值才会再次提醒。
     */
    fun shouldRemind(now: Long): Boolean {
        val totalNow = total(now)
        if (totalNow < thresholdMillis) return false
        if (totalNow - lastReminderAt < thresholdMillis) return false
        lastReminderAt = totalNow
        return true
    }

    fun reset() {
        accumulated = 0
        foregroundSince = null
        lastReminderAt = 0
    }

    companion object {
        /** 2 小时 */
        const val DEFAULT_THRESHOLD_MS = 2 * 60 * 60 * 1000L
    }
}
