package cc.pulsez.app.ui.copy

import cc.pulsez.app.audio.VoiceInputError
import cc.pulsez.app.audio.VoiceQuotaReason
import cc.pulsez.app.core.UsageLimits
import cc.pulsez.app.net.ApiError
import cc.pulsez.app.net.ApiException
import cc.pulsez.app.net.AsrRejectReason
import cc.pulsez.app.net.AsrRejectedException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 语音输入文案回归（纯 JVM，不碰 Android / Compose / Room）。
 *
 *
 * -（官方语音文案：切换至语音 / 按住说话 / 松手 / 上滑取消）、
 *（失败三句话内给「再试一次」）、（首次提示结构：是什么 / 音频去哪 / 不用会怎样）、
 *（文案禁忌）；
 * -（三问）、（禁用词）、（金额「预估，以服务商账单为准」）、
 * /（错误与上限的既定说法）；
 * -（音频出网 + 不承诺服务商不保存）；
 * - ：转写进输入框、**不自动发送**；空态主按钮先切模式再按住。
 *
 * 只测常量与纯映射：Compose 页面里的字面量没法在纯 JVM 里反射收集（同 `ImageCopyTest` 做法）。
 */
class VoiceCopyTest {

    companion object {
        /** 禁用词 + 补充（TTS / 语音合成 / 识别引擎 / 模型）。 */
        private val TECH_BANNED: List<String> = listOf(
            "token", "上下文", "窗口", "向量", "embedding", "Embedding",
            "记忆检索", "关键词", "缓存", "TTS", "语音合成", "合成引擎", "识别引擎",
            "模型", "deepseek-flash",
        )

        /** 角色代词只允许 TA。 */
        private val PRONOUN_BANNED: List<String> = listOf("她", "他")

        /** 绝对承诺 / 制造不存在功能 / 客服腔 / 营销词。 */
        private val PROMISE_BANNED: List<String> = listOf(
            "免费",
            "不额外收费",
            "永久",
            "绝对",
            "本版未提供",
            "我们",
            "全新升级",
            "智能语音",
            "AI 朗读",
            "官方音色",
        )

        /** 本产出的全部用户可见文案（名称 → 文案），供批量断言。 */
        private fun allCopy(): List<Pair<String, String>> = listOf(
            "输入框占位" to VoiceCopy.PLACEHOLDER,
            "切换至语音" to VoiceCopy.SWITCH_TO_VOICE,
            "切换至键盘" to VoiceCopy.SWITCH_TO_KEYBOARD,
            "用语音说话" to VoiceCopy.USE_VOICE,
            "按住说话" to VoiceCopy.HOLD_TO_TALK,
            "录音中" to VoiceCopy.RECORDING_LABEL,
            "上滑取消" to VoiceCopy.SLIDE_CANCEL,
            "松手取消" to VoiceCopy.SLIDE_CANCEL_ARMED,
            "转写中" to VoiceCopy.TRANSCRIBING,
            "自停轻提示" to VoiceCopy.RECORDING_AUTO_STOPPED,
            "取消未上传轻提示" to VoiceCopy.CANCELED_NO_UPLOAD,
            "开始录音动作" to VoiceCopy.RECORD_START_ACTION,
            "结束录音动作" to VoiceCopy.RECORD_STOP_ACTION,
            "录音读屏" to VoiceCopy.RECORDING_CONTENT_DESCRIPTION,
            "太短" to VoiceCopy.TOO_SHORT,
            "空转写" to VoiceCopy.EMPTY_TRANSCRIPT,
            "无麦克风" to VoiceCopy.UNAVAILABLE_NO_MIC,
            "录音失败" to VoiceCopy.RECORDING_FAILED,
            "不支持" to VoiceCopy.SERVICE_UNSUPPORTED,
            "没网" to VoiceCopy.NO_NETWORK,
            "忙" to VoiceCopy.BUSY,
            "被拦" to VoiceCopy.FILTERED,
            "额度" to VoiceCopy.QUOTA,
            "密钥" to VoiceCopy.AUTH,
            "空音频" to VoiceCopy.EMPTY_AUDIO,
            "太长" to VoiceCopy.TOO_LONG,
            "兜底失败" to VoiceCopy.FAILED,
            "重试按钮" to VoiceCopy.RETRY,
            "打字按钮" to VoiceCopy.TYPE_INSTEAD,
            "权限说明标题" to VoiceCopy.PERMISSION_INTRO_TITLE,
            "权限说明 1" to VoiceCopy.PERMISSION_INTRO_LINE_1,
            "权限说明 2" to VoiceCopy.PERMISSION_INTRO_LINE_2,
            "权限说明 3" to VoiceCopy.PERMISSION_INTRO_LINE_3,
            "权限继续" to VoiceCopy.PERMISSION_CONTINUE,
            "权限先不用" to VoiceCopy.PERMISSION_DECLINE,
            "被拒标题" to VoiceCopy.PERMISSION_DENIED_TITLE,
            "被拒说明" to VoiceCopy.PERMISSION_DENIED,
            "去系统设置" to VoiceCopy.OPEN_SETTINGS,
            "首用标题" to VoiceCopy.INTRO_TITLE,
            "首用 1" to VoiceCopy.INTRO_LINE_1,
            "首用 2" to VoiceCopy.INTRO_LINE_2,
            "首用 3" to VoiceCopy.INTRO_LINE_3,
            "首用 4" to VoiceCopy.INTRO_LINE_4,
            "首用 5" to VoiceCopy.INTRO_LINE_5,
            "首用开始" to VoiceCopy.INTRO_CONFIRM,
            "首用先不用" to VoiceCopy.INTRO_DISMISS,
            "授权后待按提示" to VoiceCopy.PERMISSION_GRANTED_READY,
            "待按提示" to VoiceCopy.READY_TO_HOLD,
            "非 DeepSeek" to VoiceCopy.OTHER_PROVIDER,
            "没密钥" to VoiceCopy.NO_KEY,
        )
    }

    // ------------------------------------------------------------ 模式 / 录音逐字

    @Test
    fun modeAndRecordingCopyIsVerbatim() {
        assertEquals("说点什么……", VoiceCopy.PLACEHOLDER)
        // 官方 APK 逐字：switch_to_voice = 切换至语音。
        assertEquals("切换至语音", VoiceCopy.SWITCH_TO_VOICE)
        assertEquals("切换至键盘", VoiceCopy.SWITCH_TO_KEYBOARD)
        assertEquals("按住说话", VoiceCopy.HOLD_TO_TALK)
        // PulseZ 不自动发送，所以录音中不说官方「松手发送」。
        assertEquals("松手转文字", VoiceCopy.RECORDING_LABEL)
        assertEquals("上滑取消", VoiceCopy.SLIDE_CANCEL)
        assertEquals("松手取消", VoiceCopy.SLIDE_CANCEL_ARMED)
        assertEquals("正在转文字……", VoiceCopy.TRANSCRIBING)
        assertEquals("到最长了，先转这一段。", VoiceCopy.RECORDING_AUTO_STOPPED)
        // （05）：armed 后 60 秒自停丢弃的轻提示，逐字（A）。
        assertEquals("已经取消了，没有上传。", VoiceCopy.CANCELED_NO_UPLOAD)
        // （08）：第二行独立麦克风的读屏名，逐字（D）。
        assertEquals("用语音说话", VoiceCopy.USE_VOICE)
        assertEquals("开始录音", VoiceCopy.RECORD_START_ACTION)
        assertEquals("结束录音", VoiceCopy.RECORD_STOP_ACTION)
    }

    // ------------------------------------------------------------ 权限

    @Test
    fun permissionIntroSaysWhatWhereAndFallback() {
        // +：用途 / 音频发给谁 / 拒绝也能打字。
        assertTrue(VoiceCopy.PERMISSION_INTRO_LINE_1.contains("转成文字"))
        assertTrue(VoiceCopy.PERMISSION_INTRO_LINE_1.contains("你填的 AI 服务商"))
        assertTrue(VoiceCopy.PERMISSION_INTRO_LINE_2.contains("删掉"))
        assertTrue(VoiceCopy.PERMISSION_INTRO_LINE_3.contains("打字"))
        assertEquals(3, VoiceCopy.PERMISSION_INTRO_LINES.size)
        assertEquals(
            listOf(
                VoiceCopy.PERMISSION_INTRO_LINE_1,
                VoiceCopy.PERMISSION_INTRO_LINE_2,
                VoiceCopy.PERMISSION_INTRO_LINE_3,
            ),
            VoiceCopy.PERMISSION_INTRO_LINES,
        )
        assertEquals("继续", VoiceCopy.PERMISSION_CONTINUE)
        assertEquals("先不用", VoiceCopy.PERMISSION_DECLINE)
    }

    @Test
    fun permissionDeniedCopyGivesTwoWaysOut() {
        // 不说「无法使用语音」这种没出路的话：去系统设置 + 先打字。
        assertTrue(VoiceCopy.PERMISSION_DENIED.contains("系统设置"))
        assertTrue(VoiceCopy.PERMISSION_DENIED.contains("打字"))
        assertEquals("去系统设置", VoiceCopy.OPEN_SETTINGS)
        assertEquals("先打字", VoiceCopy.TYPE_INSTEAD)
        assertFalse("不指责用户、不说功能不可用", VoiceCopy.PERMISSION_DENIED.contains("无法使用"))
    }

    // ------------------------------------------------------------ 首次费用 / 隐私说明

    @Test
    fun introHasExactlyFiveLinesInOrder() {
        assertEquals(5, VoiceCopy.INTRO_LINES.size)
        assertEquals(
            listOf(
                VoiceCopy.INTRO_LINE_1,
                VoiceCopy.INTRO_LINE_2,
                VoiceCopy.INTRO_LINE_3,
                VoiceCopy.INTRO_LINE_4,
                VoiceCopy.INTRO_LINE_5,
            ),
            VoiceCopy.INTRO_LINES,
        )
        VoiceCopy.INTRO_LINES.forEach { line ->
            assertTrue("说明行不能为空", line.isNotBlank())
        }
    }

    @Test
    fun introAnswersAllRequiredQuestions() {
        // 是什么 / 结果去哪 / 不自动发送。
        assertTrue(VoiceCopy.INTRO_LINE_1.contains("转成文字"))
        assertTrue(VoiceCopy.INTRO_LINE_1.contains("放进输入框"))
        assertTrue("必须说清可编辑后再发（不自动发送）", VoiceCopy.INTRO_LINE_1.contains("改完再发"))
        // 发给谁 + 谁付钱（BYOK）。
        assertTrue(VoiceCopy.INTRO_LINE_2.contains("你填的 AI 服务商"))
        assertTrue(VoiceCopy.INTRO_LINE_2.contains("你自己的账户"))
        // 大概多少钱 + 预估口径（10 秒 ≈ ¥0.0023 → 十秒不到一分钱）。
        assertTrue(VoiceCopy.INTRO_LINE_3.contains("十秒不到一分钱"))
        assertTrue(VoiceCopy.INTRO_LINE_3.contains("预估"))
        assertTrue(VoiceCopy.INTRO_LINE_3.contains("以服务商账单为准"))
        // 音频去留。
        assertTrue(VoiceCopy.INTRO_LINE_4.contains("临时"))
        assertTrue(VoiceCopy.INTRO_LINE_4.contains("转完就删"))
        // 不用怎样 / 怎么关。
        assertTrue(VoiceCopy.INTRO_LINE_5.contains("打字"))
        assertTrue(VoiceCopy.INTRO_LINE_5.contains("不点麦克风"))
        assertEquals("开始", VoiceCopy.INTRO_CONFIRM)
        assertEquals("先不用", VoiceCopy.INTRO_DISMISS)
    }

    @Test
    fun permissionGrantedHintAsksUserToHoldInsteadOfAutoRecording() {
        // 送检前修复：授权/说明流程结束后不自动录音，只提示用户自己按住。
        assertEquals("权限好了。按住说话就能开始。", VoiceCopy.PERMISSION_GRANTED_READY)
        assertEquals("按住说话就能开始。", VoiceCopy.READY_TO_HOLD)
        assertTrue(
            "授权后的提示要告诉用户下一步是按住说话：${VoiceCopy.PERMISSION_GRANTED_READY}",
            VoiceCopy.PERMISSION_GRANTED_READY.contains("按住说话"),
        )
        assertTrue(
            "通用待按提示要告诉用户下一步是按住说话：${VoiceCopy.READY_TO_HOLD}",
            VoiceCopy.READY_TO_HOLD.contains("按住说话"),
        )
        // 不说「已开始录音 / 正在录音」这类会让人以为不用按的话（否则就是文案层面的自动录音暗示）。
        listOf(VoiceCopy.PERMISSION_GRANTED_READY, VoiceCopy.READY_TO_HOLD).forEach { text ->
            assertFalse("不能暗示已经自动开始录音：$text", text.contains("正在录音"))
            assertFalse("不能暗示已经自动开始录音：$text", text.contains("已开始录音"))
        }
    }

    // ------------------------------------------------------------ 失败文案：每句都有出路

    @Test
    fun everyFailureLineOffersARetryOrTyping() {
        val failures = listOf(
            "太短" to VoiceCopy.TOO_SHORT,
            "空转写" to VoiceCopy.EMPTY_TRANSCRIPT,
            "无麦克风" to VoiceCopy.UNAVAILABLE_NO_MIC,
            "录音失败" to VoiceCopy.RECORDING_FAILED,
            "不支持" to VoiceCopy.SERVICE_UNSUPPORTED,
            "没网" to VoiceCopy.NO_NETWORK,
            "忙" to VoiceCopy.BUSY,
            "被拦" to VoiceCopy.FILTERED,
            "额度" to VoiceCopy.QUOTA,
            "密钥" to VoiceCopy.AUTH,
            "空音频" to VoiceCopy.EMPTY_AUDIO,
            "太长" to VoiceCopy.TOO_LONG,
            "兜底" to VoiceCopy.FAILED,
        )
        failures.forEach { (name, text) ->
            val hasRetry = text.contains("再试") || text.contains("再说")
            val hasTyping = text.contains("打字")
            assertTrue("「$name」既没有重试也没有打字出路：$text", hasRetry || hasTyping)
            assertFalse("「$name」不能只说「失败」不解释：$text", text == "失败了")
        }
    }

    // ------------------------------------------------------------ 文案纪律

    @Test
    fun copyAvoidsBannedWordsAndPromises() {
        allCopy().forEach { (name, text) ->
            TECH_BANNED.forEach { word ->
                assertFalse("「$name」出现技术词「$word」：$text", text.contains(word))
            }
            PRONOUN_BANNED.forEach { pronoun ->
                assertFalse("「$name」出现角色代词「$pronoun」（只许 TA）：$text", text.contains(pronoun))
            }
            PROMISE_BANNED.forEach { phrase ->
                assertFalse("「$name」出现不该有的说法「$phrase」：$text", text.contains(phrase))
            }
        }
    }

    @Test
    fun buttonsAreShortAndChinesePunctuationOnly() {
        val buttons = listOf(
            VoiceCopy.RETRY,
            VoiceCopy.TYPE_INSTEAD,
            VoiceCopy.PERMISSION_CONTINUE,
            VoiceCopy.PERMISSION_DECLINE,
            VoiceCopy.OPEN_SETTINGS,
            VoiceCopy.INTRO_CONFIRM,
            VoiceCopy.INTRO_DISMISS,
            VoiceCopy.SWITCH_TO_VOICE,
            VoiceCopy.SWITCH_TO_KEYBOARD,
            VoiceCopy.USE_VOICE,
        )
        buttons.forEach { label ->
            assertTrue("按钮「$label」超过 20 字", label.length <= 20)
            assertFalse("按钮「$label」不能用半角省略号/感叹号", label.contains("...") || label.contains("!"))
        }
    }

    // ------------------------------------------------------------ 映射

    @Test
    fun transcribeFailureMapsApiErrorKinds() {
        assertEquals(VoiceCopy.NO_NETWORK, VoiceCopy.transcribeFailure(ApiError.Network()))
        assertEquals(VoiceCopy.QUOTA, VoiceCopy.transcribeFailure(ApiError.Quota(httpCode = 402)))
        assertEquals(VoiceCopy.AUTH, VoiceCopy.transcribeFailure(ApiError.Auth(httpCode = 401)))
        assertEquals(VoiceCopy.AUTH, VoiceCopy.transcribeFailure(ApiError.NoKey()))
        assertEquals(VoiceCopy.FILTERED, VoiceCopy.transcribeFailure(ApiError.Filtered(httpCode = 400)))
        assertEquals(VoiceCopy.BUSY, VoiceCopy.transcribeFailure(ApiError.Server(httpCode = 503)))
        assertEquals(
            VoiceCopy.FAILED,
            VoiceCopy.transcribeFailure(ApiError.BadResponse(httpCode = 418)),
        )
    }

    @Test
    fun transcribeFailureMapsThrowablesAndRejections() {
        assertEquals(
            VoiceCopy.FILTERED,
            VoiceCopy.transcribeFailure(ApiException(ApiError.Filtered(httpCode = 400))),
        )
        assertEquals(
            VoiceCopy.AUTH,
            VoiceCopy.transcribeFailure(AsrRejectedException(AsrRejectReason.NO_KEY)),
        )
        assertEquals(
            VoiceCopy.SERVICE_UNSUPPORTED,
            VoiceCopy.transcribeFailure(AsrRejectedException(AsrRejectReason.NOT_WAV)),
        )
        assertEquals(
            VoiceCopy.EMPTY_AUDIO,
            VoiceCopy.transcribeFailure(AsrRejectedException(AsrRejectReason.FILE_EMPTY)),
        )
        assertEquals(
            VoiceCopy.TOO_LONG,
            VoiceCopy.transcribeFailure(AsrRejectedException(AsrRejectReason.DURATION_TOO_LONG)),
        )
        assertEquals(VoiceCopy.FAILED, VoiceCopy.transcribeFailure(IllegalStateException("boom")))
        assertFalse(
            "不把异常文本透给用户",
            VoiceCopy.transcribeFailure(IllegalStateException("secret audio text")).contains("secret"),
        )
    }

    @Test
    fun recordingErrorMapsVoiceInputCodes() {
        assertEquals(VoiceCopy.TOO_SHORT, VoiceCopy.recordingError(VoiceInputError.TOO_SHORT))
        assertEquals(
            VoiceCopy.PERMISSION_DENIED,
            VoiceCopy.recordingError(VoiceInputError.PERMISSION_DENIED),
        )
        assertEquals(
            VoiceCopy.RECORDING_FAILED,
            VoiceCopy.recordingError(VoiceInputError.RECORDING_UNAVAILABLE),
        )
        assertEquals(
            VoiceCopy.RECORDING_FAILED,
            VoiceCopy.recordingError(VoiceInputError.RECORDING_FAILED),
        )
        assertEquals(VoiceCopy.FAILED, VoiceCopy.recordingError(VoiceInputError.TRANSCRIBE_FAILED))
        assertEquals(VoiceCopy.FAILED, VoiceCopy.recordingError(null))
    }

    @Test
    fun quotaMessageReusesChatUsageLimitWording() {
        // 与打字发送被拦时完全同一句话，用户不需要学第二套。
        assertEquals(
            UsageLimits.DAILY_LIMIT_MESSAGE,
            VoiceCopy.quotaMessage(VoiceQuotaReason.DAILY_LIMIT),
        )
        assertEquals(
            UsageLimits.MONTHLY_LIMIT_MESSAGE,
            VoiceCopy.quotaMessage(VoiceQuotaReason.MONTHLY_LIMIT),
        )
        assertTrue(VoiceCopy.quotaMessage(VoiceQuotaReason.DAILY_LIMIT).contains("调高"))
    }

    // ------------------------------------------------------------ 用量页 / 计时格式化

    @Test
    fun usageLineIsFormattedAndMarkedAsEstimate() {
        assertEquals(
            "其中语音 ¥0.0023（预估，以服务商账单为准）",
            VoiceCopy.voiceUsageLine(0.0023),
        )
        assertEquals(
            "其中语音 ¥0.0000（预估，以服务商账单为准）",
            VoiceCopy.voiceUsageLine(0.0),
        )
        assertTrue(VoiceCopy.voiceUsageLine(1.2345).contains("1.2345"))
    }

    @Test
    fun elapsedLabelFormatsZeroToSixtySeconds() {
        assertEquals("0:00", VoiceCopy.elapsedLabel(0L))
        assertEquals("0:07", VoiceCopy.elapsedLabel(7_400L))
        assertEquals("0:59", VoiceCopy.elapsedLabel(59_999L))
        assertEquals("1:00", VoiceCopy.elapsedLabel(60_000L))
        // 超过 60 秒自限一律显示 1:00（不会出现 1:01，录音器到 60 秒就自动停）。
        assertEquals("1:00", VoiceCopy.elapsedLabel(75_000L))
        assertEquals("0:00", VoiceCopy.elapsedLabel(-5L))
    }

    @Test
    fun secondsLeftHintOnlyAppearsInLastTenSeconds() {
        assertNull(VoiceCopy.secondsLeftHint(0L))
        assertNull(VoiceCopy.secondsLeftHint(49_000L))
        assertEquals("还能说 10 秒", VoiceCopy.secondsLeftHint(50_000L))
        assertEquals("还能说 1 秒", VoiceCopy.secondsLeftHint(59_500L))
        assertNull("正好到自限时不再提示剩余", VoiceCopy.secondsLeftHint(60_000L))
    }
}
