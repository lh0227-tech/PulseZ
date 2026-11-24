package cc.pulsez.app.data.repo

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.LocalDate
import java.time.YearMonth

private val Context.usageStore by preferencesDataStore(name = "pulsez_usage")

/**
 * 花费记账。
 *
 * 只记"微元"（1 元 = 1_000_000 微元），避免浮点累积误差。
 * 按天/月滚动；跨天时旧值清零。上限由用户自己设，这里只做记账。
 *
 * v0.5.0 分类记账：
 * - `todayMessages/monthMessages` 只由**聊天**记账累加（[UsageCategory.CHAT]），
 *   图片/朗读等其它分类不得污染消息条数；
 * - 单列「图片」花费（[todayImageYuan]/[monthImageYuan]）供用量页小项展示，
 *   数值是**预估**（官方按输入 token 计，图片最多 1024 token/张，见 `Pricing`）。
 *
 * v0.5.1 追加语音（` 的 MVP 拆分：语音单列一版）：
 * - [UsageCategory.VOICE] 与图片同构：只加钱、单列「语音」小项，**不加消息条数**；
 * - 金额按官方 ASR list 价预估（`Pricing.asrEstimateYuan`），界面标「预估，以服务商账单为准」。
 */
data class UsageSnapshot(
    val todayYuan: Double,
    val monthYuan: Double,
    val todayMessages: Int,
    val monthMessages: Int,
    val savedRatio: Double,
    /** 今天花在图上的钱（预估，v0.5.0）。含在 [todayYuan] 里，不重复相加。*/
    val todayImageYuan: Double = 0.0,
    /** 这个月花在图上的钱（预估，v0.5.0）。含在 [monthYuan] 里，不重复相加。*/
    val monthImageYuan: Double = 0.0,
    /** 今天花在语音转文字上的钱（预估，v0.5.1）。含在 [todayYuan] 里，不重复相加。*/
    val todayVoiceYuan: Double = 0.0,
    /** 这个月花在语音转文字上的钱（预估，v0.5.1）。含在 [monthYuan] 里，不重复相加。*/
    val monthVoiceYuan: Double = 0.0,
)

/**
 * 记账分类（v0.5.0；` 的 chat/vision/voice/file/tts 口径）。
 *
 * 已落：聊天（v0.5.0）、图片（v0.5.0）、语音（v0.5.1）。文件/朗读各自的分类在对应版本补
 * （` 的 MVP 拆分），不提前造不存在的分类。
 */
enum class UsageCategory {
    /** 聊天请求：**唯一**会累加消息条数的分类。 */
    CHAT,

    /** 图片（识图）：不计消息条数，单列图片花费。 */
    VISION,

    /** 语音转文字（ASR）：不计消息条数，单列语音花费（v0.5.1）。*/
    VOICE,
}

class UsageStore(private val context: Context) {

    val flow: Flow<UsageSnapshot> = context.usageStore.data.map { prefs ->
        val today = LocalDate.now().toString()
        val month = YearMonth.now().toString()
        val sameDay = prefs[Keys.DAY] == today
        val sameMonth = prefs[Keys.MONTH] == month
        UsageSnapshot(
            todayYuan = if (sameDay) (prefs[Keys.DAY_MICROS] ?: 0L) / 1_000_000.0 else 0.0,
            monthYuan = if (sameMonth) (prefs[Keys.MONTH_MICROS] ?: 0L) / 1_000_000.0 else 0.0,
            todayMessages = if (sameDay) (prefs[Keys.DAY_MESSAGES] ?: 0L).toInt() else 0,
            monthMessages = if (sameMonth) (prefs[Keys.MONTH_MESSAGES] ?: 0L).toInt() else 0,
            savedRatio = (prefs[Keys.SAVED_RATIO] ?: 0L) / 1000.0,
            todayImageYuan = if (sameDay) (prefs[Keys.DAY_IMAGE_MICROS] ?: 0L) / 1_000_000.0 else 0.0,
            monthImageYuan = if (sameMonth) (prefs[Keys.MONTH_IMAGE_MICROS] ?: 0L) / 1_000_000.0 else 0.0,
            todayVoiceYuan = if (sameDay) (prefs[Keys.DAY_VOICE_MICROS] ?: 0L) / 1_000_000.0 else 0.0,
            monthVoiceYuan = if (sameMonth) (prefs[Keys.MONTH_VOICE_MICROS] ?: 0L) / 1_000_000.0 else 0.0,
        )
    }

    /**
     * 记一笔花费（默认聊天分类，保持旧调用点行为）。
     *
     * @param savedRatio 缓存省下的比例（0–1）；只有聊天的真实 usage 能算出来
     */
    suspend fun record(costYuan: Double, savedRatio: Double) {
        record(costYuan, savedRatio, UsageCategory.CHAT)
    }

    /**
     * 记一笔分类花费。
     *
     * 约定：
     * - 金额总是累加到日/月总额（不论分类）；
     * - **只有 [UsageCategory.CHAT] 累加消息条数**——图片/语音/其它分类不许污染"今天 N 条消息"；
     * - [UsageCategory.VISION] 额外累加「图片」小项；
     * - [UsageCategory.VOICE] 额外累加「语音」小项（v0.5.1，只加钱、不加消息数）；
     * - `savedRatio` 只由聊天写（图片/语音没有缓存命中概念，不覆盖已有的省下比例）。
     */
    suspend fun record(
        costYuan: Double,
        savedRatio: Double,
        category: UsageCategory,
    ) {
        val today = LocalDate.now().toString()
        val month = YearMonth.now().toString()
        context.usageStore.edit { prefs ->
            val sameDay = prefs[Keys.DAY] == today
            val sameMonth = prefs[Keys.MONTH] == month
            val micros = (costYuan * 1_000_000).toLong().coerceAtLeast(0)
            prefs[Keys.DAY] = today
            prefs[Keys.MONTH] = month
            prefs[Keys.DAY_MICROS] = (if (sameDay) prefs[Keys.DAY_MICROS] ?: 0L else 0L) + micros
            prefs[Keys.MONTH_MICROS] = (if (sameMonth) prefs[Keys.MONTH_MICROS] ?: 0L else 0L) + micros
            if (category == UsageCategory.CHAT) {
                prefs[Keys.DAY_MESSAGES] = (if (sameDay) prefs[Keys.DAY_MESSAGES] ?: 0L else 0L) + 1
                prefs[Keys.MONTH_MESSAGES] = (if (sameMonth) prefs[Keys.MONTH_MESSAGES] ?: 0L else 0L) + 1
                prefs[Keys.SAVED_RATIO] = (savedRatio.coerceIn(0.0, 1.0) * 1000).toLong()
            }
            if (category == UsageCategory.VISION) {
                prefs[Keys.DAY_IMAGE_MICROS] =
                    (if (sameDay) prefs[Keys.DAY_IMAGE_MICROS] ?: 0L else 0L) + micros
                prefs[Keys.MONTH_IMAGE_MICROS] =
                    (if (sameMonth) prefs[Keys.MONTH_IMAGE_MICROS] ?: 0L else 0L) + micros
            }
            if (category == UsageCategory.VOICE) {
                prefs[Keys.DAY_VOICE_MICROS] =
                    (if (sameDay) prefs[Keys.DAY_VOICE_MICROS] ?: 0L else 0L) + micros
                prefs[Keys.MONTH_VOICE_MICROS] =
                    (if (sameMonth) prefs[Keys.MONTH_VOICE_MICROS] ?: 0L else 0L) + micros
            }
        }
    }

    suspend fun clear() {
        context.usageStore.edit { it.clear() }
    }

    private object Keys {
        val DAY = stringPreferencesKey("day")
        val MONTH = stringPreferencesKey("month")
        val DAY_MICROS = longPreferencesKey("day_micros")
        val MONTH_MICROS = longPreferencesKey("month_micros")
        val DAY_MESSAGES = longPreferencesKey("day_messages")
        val MONTH_MESSAGES = longPreferencesKey("month_messages")
        val SAVED_RATIO = longPreferencesKey("saved_ratio_milli")

        // v0.5 识图：图片花费小项（预估）。Key 名一旦发布就不要再改。
        val DAY_IMAGE_MICROS = longPreferencesKey("day_image_micros")
        val MONTH_IMAGE_MICROS = longPreferencesKey("month_image_micros")

        // v0.5.1 语音：语音花费小项（预估）。同样：Key 名一旦发布就不要再改。
        val DAY_VOICE_MICROS = longPreferencesKey("day_voice_micros")
        val MONTH_VOICE_MICROS = longPreferencesKey("month_voice_micros")
    }
}
