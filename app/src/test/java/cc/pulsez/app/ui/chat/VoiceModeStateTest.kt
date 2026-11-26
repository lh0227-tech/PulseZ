package cc.pulsez.app.ui.chat

import cc.pulsez.app.audio.AsrAvailability
import cc.pulsez.app.audio.VoiceInputEffect
import cc.pulsez.app.audio.VoiceInputError
import cc.pulsez.app.audio.VoiceInputEvent
import cc.pulsez.app.audio.VoiceInputMachine
import cc.pulsez.app.audio.VoiceInputState
import cc.pulsez.app.audio.VoiceQuotaDecision
import cc.pulsez.app.audio.VoiceQuotaGate
import cc.pulsez.app.audio.VoiceQuotaReason
import cc.pulsez.app.core.UsageLimits
import cc.pulsez.app.data.repo.UsageSnapshot
import cc.pulsez.app.media.DraftImage
import cc.pulsez.app.media.DraftStatus
import cc.pulsez.app.net.FilesClient
import cc.pulsez.app.ui.copy.VoiceCopy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 语音输入 UI 纯逻辑回归（纯 JVM，不碰 Android / Compose / Room）。
 *
 * 覆盖：
 * 1. 主按钮形态映射（普通 / 语音 / 有字 / 生成中 / 图片准备中 / 有可发图）；
 * 2. 状态机状态 → UI 阶段（含 送检前修复：授权成功回 Idle，UI 是「按住说话」就绪态、不录音）；
 * 3. 权限状态映射（未请求 / 已授权 / 拒绝 / 永久拒绝 → UI 动作）与系统回调归档；
 * 4. 门禁与额度拦截的 UI 决策；
 * 5. 转写文本插入规则（空输入 / 光标处 / 替换选中 / 英文空格 / 不自动发送）；
 * 6. 按住手势的上滑取消阈值；
 * 7. ：armed 收尾丢弃（05）、第二行语音入口真值表（08）、缺时长回退本地时长（09）。
 *
 * / /； / /；
 * （授权/首用流程结束不得自动录音）；
 * ：空输入主按钮 = 语音模式入口、语音模式按住说话、转写进输入框不自动发送。
 */
class VoiceModeStateTest {

    private fun ready(id: String = "ready") = DraftImage(
        id = id,
        localPath = "attachments/$id.jpg",
        status = DraftStatus.READY,
    )

    private fun preparing(id: String = "preparing") = DraftImage(
        id = "draft-$id",
        localPath = "",
        status = DraftStatus.PREPARING,
    )

    // ------------------------------------------------------------ 主按钮形态

    @Test
    fun textModeEmptyInputIsVoiceEntryNotDisabledArrow() {
        // 起空输入主按钮 = 语音模式入口（官方同一位置；「点一下切模式」）。
        val state = ComposerMainButtonResolver.resolve(
            drafts = emptyList(),
            hasText = false,
            streaming = false,
            mode = ComposerMode.TEXT,
        )
        assertEquals(ComposerMainIcon.MIC, state.icon)
        assertEquals(ComposerMainAction.SWITCH_TO_VOICE, state.action)
        assertTrue("语音入口必须可点（不是假按钮）", state.enabled)
        assertEquals(VoiceCopy.SWITCH_TO_VOICE, state.contentDescription)
    }

    @Test
    fun hiddenVoiceEntryFallsBackToDisabledArrow() {
        // 门禁隐藏（非 DeepSeek / 没密钥 / 没麦克风）时不能留麦克风死按钮：
        // 空输入退回 的禁用发送箭头（不制造假入口）。
        val state = ComposerMainButtonResolver.resolve(
            drafts = emptyList(),
            hasText = false,
            streaming = false,
            mode = ComposerMode.TEXT,
            voiceEntryVisible = false,
        )
        assertEquals(ComposerMainIcon.ARROW_UP, state.icon)
        assertEquals(ComposerMainAction.SEND, state.action)
        assertFalse("语音不可用时不能点出麦克风", state.enabled)
        assertEquals("发送", state.contentDescription)
    }

    @Test
    fun voiceModeEmptyInputIsKeyboardEntry() {
        val state = ComposerMainButtonResolver.resolve(
            drafts = emptyList(),
            hasText = false,
            streaming = false,
            mode = ComposerMode.VOICE,
        )
        assertEquals(ComposerMainIcon.KEYBOARD, state.icon)
        assertEquals(ComposerMainAction.SWITCH_TO_KEYBOARD, state.action)
        assertTrue(state.enabled)
        assertEquals(VoiceCopy.SWITCH_TO_KEYBOARD, state.contentDescription)
    }

    @Test
    fun textTakesPriorityOverModeInBothModes() {
        // 有字优先发送（与官方一致；语音模式里若有已转写文字也允许发送）。
        listOf(ComposerMode.TEXT, ComposerMode.VOICE).forEach { mode ->
            val state = ComposerMainButtonResolver.resolve(
                drafts = emptyList(),
                hasText = true,
                streaming = false,
                mode = mode,
            )
            assertEquals("$mode：有字 = 发送箭头", ComposerMainIcon.ARROW_UP, state.icon)
            assertEquals(ComposerMainAction.SEND, state.action)
            assertTrue(state.enabled)
            assertEquals("发送", state.contentDescription)
        }
    }

    @Test
    fun streamingAlwaysShowsStop() {
        listOf(ComposerMode.TEXT, ComposerMode.VOICE).forEach { mode ->
            listOf(false, true).forEach { hasText ->
                val state = ComposerMainButtonResolver.resolve(
                    drafts = emptyList(),
                    hasText = hasText,
                    streaming = true,
                    mode = mode,
                )
                assertEquals(ComposerMainIcon.STOP, state.icon)
                assertEquals(ComposerMainAction.STOP, state.action)
                assertTrue("生成中停止永远可点", state.enabled)
                assertEquals("停下", state.contentDescription)
            }
        }
    }

    @Test
    fun readyDraftAloneStillSends() {
        val state = ComposerMainButtonResolver.resolve(
            drafts = listOf(ready()),
            hasText = false,
            streaming = false,
            mode = ComposerMode.VOICE,
        )
        assertEquals(ComposerMainIcon.ARROW_UP, state.icon)
        assertEquals(ComposerMainAction.SEND, state.action)
        assertTrue("只有图片也能发", state.enabled)
    }

    @Test
    fun preparingDraftBlocksSendButNotVoiceEntry() {
        val withText = ComposerMainButtonResolver.resolve(
            drafts = listOf(preparing()),
            hasText = true,
            streaming = false,
            mode = ComposerMode.TEXT,
        )
        assertEquals(ComposerMainIcon.ARROW_UP, withText.icon)
        assertFalse("图片还在处理时不能发送", withText.enabled)

        val emptyTextMode = ComposerMainButtonResolver.resolve(
            drafts = listOf(preparing()),
            hasText = false,
            streaming = false,
            mode = ComposerMode.TEXT,
        )
        assertEquals("空输入仍是语音入口（切模式不涉及发送）", ComposerMainIcon.MIC, emptyTextMode.icon)
        assertTrue(emptyTextMode.enabled)

        val emptyVoiceMode = ComposerMainButtonResolver.resolve(
            drafts = listOf(preparing()),
            hasText = false,
            streaming = false,
            mode = ComposerMode.VOICE,
        )
        assertEquals(ComposerMainIcon.KEYBOARD, emptyVoiceMode.icon)
        assertTrue(emptyVoiceMode.enabled)
    }

    // ------------------------------------------------------------ 阶段映射

    @Test
    fun machineStateMapsToFiveUiPhases() {
        assertEquals(VoiceUiPhase.IDLE, VoicePhaseMapper.phaseOf(VoiceInputState.Idle))
        assertEquals(
            VoiceUiPhase.REQUESTING_PERMISSION,
            VoicePhaseMapper.phaseOf(VoiceInputState.PermissionRequired),
        )
        assertEquals(
            VoiceUiPhase.REQUESTING_PERMISSION,
            VoicePhaseMapper.phaseOf(VoiceInputState.RequestingPermission),
        )
        assertEquals(VoiceUiPhase.RECORDING, VoicePhaseMapper.phaseOf(VoiceInputState.Recording))
        assertEquals(VoiceUiPhase.TRANSCRIBING, VoicePhaseMapper.phaseOf(VoiceInputState.Transcribing))
        assertEquals(
            VoiceUiPhase.ERROR,
            VoicePhaseMapper.phaseOf(VoiceInputState.Error(VoiceInputError.TOO_SHORT)),
        )
    }

    @Test
    fun permissionGrantLeavesUiInHoldToTalkReadyStateNotRecording() {
        // 送检前修复：系统权限框点「允许」后，UI 必须是「按住说话」
        // 就绪态（IDLE），不能变成录音态、不能启动计时；只有用户再按住（Start(true)）才录音。
        val machine = VoiceInputMachine()
        machine.dispatch(VoiceInputEvent.Start(permissionGranted = false))
        machine.dispatch(VoiceInputEvent.PermissionResult(granted = true))

        assertEquals("授权成功回 Idle", VoiceInputState.Idle, machine.state.value)
        assertEquals(
            "授权后 UI 是待按就绪态，不是录音态",
            VoiceUiPhase.IDLE,
            VoicePhaseMapper.phaseOf(machine.state.value),
        )

        // 就绪态下用户再按住：这时才进录音（大按钮才是「松手转文字」、计时才开始）。
        assertEquals(
            listOf(VoiceInputEffect.START_RECORDING),
            machine.dispatch(VoiceInputEvent.Start(permissionGranted = true)),
        )
        assertEquals(
            VoiceUiPhase.RECORDING,
            VoicePhaseMapper.phaseOf(machine.state.value),
        )
    }

    @Test
    fun voiceUiStateAfterGrantIsIdleInVoiceModeWithZeroTimer() {
        // 授权后 ViewModel 写回的 UI 状态：仍在语音模式、phase=IDLE、计时 0、无错误。
        // 计时器只在 RECORDING 显示（ChatComponents.VoiceHoldToTalkRow 的 `if (recording)`），
        // 所以这里 IDLE + 0ms 就代表「计时器不会启动」。
        val state = VoiceUiState(
            visible = true,
            mode = ComposerMode.VOICE,
            phase = VoiceUiPhase.IDLE,
            elapsedMs = 0L,
        )
        assertTrue("保持语音模式（不是退回文字模式）", state.isVoiceMode)
        assertEquals(VoiceUiPhase.IDLE, state.phase)
        assertEquals("没开始录音，计时必须是 0", 0L, state.elapsedMs)
        assertNull(state.error)
        assertFalse(state.showIntro)
        assertFalse(state.showPermissionIntro)
        assertFalse(state.showPermissionDenied)
    }

    // ------------------------------------------------------------ 权限映射

    @Test
    fun notRequestedShowsAppIntroThenSystemDialog() {
        val ui = VoicePermissionPolicy.resolve(VoicePermissionState.NOT_REQUESTED)
        assertTrue(ui.requestSystemDialog)
        assertTrue("系统框前必须先在 App 内说明", ui.showAppIntroFirst)
        assertFalse(ui.showOpenSettings)
        assertFalse(ui.showTypeInstead)
    }

    @Test
    fun grantedNeedsNoPermissionUi() {
        val ui = VoicePermissionPolicy.resolve(VoicePermissionState.GRANTED)
        assertFalse(ui.requestSystemDialog)
        assertFalse(ui.showAppIntroFirst)
        assertFalse(ui.showOpenSettings)
        assertFalse(ui.showTypeInstead)
    }

    @Test
    fun deniedAndPermanentlyDeniedOnlyOfferSettingsAndTyping() {
        listOf(VoicePermissionState.DENIED, VoicePermissionState.PERMANENTLY_DENIED).forEach { state ->
            val ui = VoicePermissionPolicy.resolve(state)
            assertFalse("不重复弹系统框", ui.requestSystemDialog)
            assertFalse(ui.showAppIntroFirst)
            assertTrue("$state：去系统设置", ui.showOpenSettings)
            assertTrue("$state：先打字", ui.showTypeInstead)
        }
    }

    @Test
    fun entryFlowNeverStartsRecordingAndHoldsWhenAlreadyGranted() {
        // 送检前修复：首用说明/授权流程收尾只有三种下一步，
        // 没有「开始录音」这一支；已授权时一律回「按住说话」就绪态。
        assertEquals(
            VoiceEntryFlowNext.READY_TO_HOLD,
            VoiceEntryFlowPolicy.next(permissionGranted = true, VoicePermissionState.NOT_REQUESTED),
        )
        assertEquals(
            VoiceEntryFlowNext.READY_TO_HOLD,
            VoiceEntryFlowPolicy.next(permissionGranted = false, VoicePermissionState.GRANTED),
        )
        assertEquals(
            "还没请求过权限：继续弹 App 内说明（仍不录音）",
            VoiceEntryFlowNext.ASK_PERMISSION,
            VoiceEntryFlowPolicy.next(permissionGranted = false, VoicePermissionState.NOT_REQUESTED),
        )
        listOf(VoicePermissionState.DENIED, VoicePermissionState.PERMANENTLY_DENIED).forEach { state ->
            assertEquals(
                "$state：给出路，不录音",
                VoiceEntryFlowNext.PERMISSION_DENIED,
                VoiceEntryFlowPolicy.next(permissionGranted = false, state),
            )
        }
        // 结构保证：下一步的枚举里没有「录音」这个值（以后若有人加回自动录音分支，这里会先炸）。
        assertEquals(
            "首用/授权流程的下一步只能是这三支，不能有「开始录音」",
            listOf(
                VoiceEntryFlowNext.READY_TO_HOLD,
                VoiceEntryFlowNext.ASK_PERMISSION,
                VoiceEntryFlowNext.PERMISSION_DENIED,
            ),
            VoiceEntryFlowNext.entries,
        )
    }

    @Test
    fun systemResultClassifiesFirstDenialVersusPermanent() {        assertEquals(
            VoicePermissionState.GRANTED,
            VoicePermissionPolicy.afterSystemResult(
                granted = true,
                rationaleVisible = false,
                requestedBefore = false,
            ),
        )
        assertEquals(
            "第一次拒绝（rationale 也是 false）不能误判成永久拒绝",
            VoicePermissionState.DENIED,
            VoicePermissionPolicy.afterSystemResult(
                granted = false,
                rationaleVisible = false,
                requestedBefore = false,
            ),
        )
        assertEquals(
            VoicePermissionState.DENIED,
            VoicePermissionPolicy.afterSystemResult(
                granted = false,
                rationaleVisible = true,
                requestedBefore = true,
            ),
        )
        assertEquals(
            VoicePermissionState.PERMANENTLY_DENIED,
            VoicePermissionPolicy.afterSystemResult(
                granted = false,
                rationaleVisible = false,
                requestedBefore = true,
            ),
        )
    }

    // ------------------------------------------------------------ 门禁 / 额度

    @Test
    fun gateHidesEntryForThirdPartyNoKeyAndNoMicrophone() {
        val otherProvider = VoiceGateUiResolver.resolve(AsrAvailability.NOT_DEEPSEEK, true)
        assertFalse(otherProvider.entryVisible)
        assertEquals(VoiceGateHiddenReason.NOT_DEEPSEEK, otherProvider.hiddenReason)

        val noKey = VoiceGateUiResolver.resolve(AsrAvailability.NO_KEY, true)
        assertFalse(noKey.entryVisible)
        assertEquals(VoiceGateHiddenReason.NO_KEY, noKey.hiddenReason)

        val noMic = VoiceGateUiResolver.resolve(AsrAvailability.AVAILABLE, false)
        assertFalse(noMic.entryVisible)
        assertEquals(VoiceGateHiddenReason.NO_MICROPHONE, noMic.hiddenReason)

        val ok = VoiceGateUiResolver.resolve(AsrAvailability.AVAILABLE, true)
        assertTrue(ok.entryVisible)
        assertNull(ok.hiddenReason)
    }

    @Test
    fun startGateBlocksWithActionableCopy() {
        val daily = VoiceStartGate.decide(
            availability = AsrAvailability.AVAILABLE,
            hasMicrophone = true,
            quota = VoiceQuotaDecision.Blocked(VoiceQuotaReason.DAILY_LIMIT),
        )
        assertFalse(daily.allowed)
        assertEquals(UsageLimits.DAILY_LIMIT_MESSAGE, daily.blockedMessage)

        val monthly = VoiceStartGate.decide(
            availability = AsrAvailability.AVAILABLE,
            hasMicrophone = true,
            quota = VoiceQuotaDecision.Blocked(VoiceQuotaReason.MONTHLY_LIMIT),
        )
        assertEquals(UsageLimits.MONTHLY_LIMIT_MESSAGE, monthly.blockedMessage)

        assertFalse(
            VoiceStartGate.decide(AsrAvailability.NOT_DEEPSEEK, true, VoiceQuotaDecision.Allowed).allowed,
        )
        assertEquals(
            VoiceCopy.UNAVAILABLE_NO_MIC,
            VoiceStartGate.decide(AsrAvailability.AVAILABLE, false, VoiceQuotaDecision.Allowed).blockedMessage,
        )
        assertTrue(
            VoiceStartGate.decide(AsrAvailability.AVAILABLE, true, VoiceQuotaDecision.Allowed).allowed,
        )
    }

    @Test
    fun quotaPrecheckUsesSixtySecondEstimate() {
        // 已花 ¥0.99、日上限 ¥1：60 秒预估约 ¥0.014 → 会顶到上限，预检拦下（不开始、不花钱）。
        val nearLimit = VoiceQuotaGate.check(
            snapshot = UsageSnapshot(todayYuan = 0.99, monthYuan = 0.99, todayMessages = 0, monthMessages = 0, savedRatio = 0.0),
            dailyLimitYuanX100 = 100,
            monthlyLimitYuanX100 = 0,
        )
        assertTrue(nearLimit is VoiceQuotaDecision.Blocked)
        assertEquals(
            VoiceQuotaReason.DAILY_LIMIT,
            (nearLimit as VoiceQuotaDecision.Blocked).reason,
        )

        val farFromLimit = VoiceQuotaGate.check(
            snapshot = UsageSnapshot(todayYuan = 0.0, monthYuan = 0.0, todayMessages = 0, monthMessages = 0, savedRatio = 0.0),
            dailyLimitYuanX100 = 100,
            monthlyLimitYuanX100 = 0,
        )
        assertEquals(VoiceQuotaDecision.Allowed, farFromLimit)
    }

    // ------------------------------------------------------------ 转写文本插入

    @Test
    fun insertIntoEmptyInputFillsTheBox() {
        val result = VoiceTranscriptInsert.insert(
            current = "",
            selectionStart = 0,
            selectionEnd = 0,
            transcript = "今天有点累",
        )
        assertEquals("今天有点累", result.text)
        assertEquals(result.text.length, result.cursor)
    }

    @Test
    fun insertAtCursorKeepsSurroundingText() {
        val current = "你今天"
        val result = VoiceTranscriptInsert.insert(
            current = current,
            selectionStart = 1,
            selectionEnd = 1,
            transcript = "还好吗",
        )
        assertEquals("你还好吗今天", result.text)
        assertEquals(4, result.cursor)
    }

    @Test
    fun insertReplacesSelectedText() {
        val current = "今天很开心"
        val result = VoiceTranscriptInsert.insert(
            current = current,
            selectionStart = 2,
            selectionEnd = 5,
            transcript = "有点累",
        )
        assertEquals("今天有点累", result.text)
        assertEquals("今天".length + "有点累".length, result.cursor)
    }

    @Test
    fun insertTrimsTranscriptWhitespace() {
        val result = VoiceTranscriptInsert.insert(
            current = "",
            selectionStart = 0,
            selectionEnd = 0,
            transcript = "  你好\n",
        )
        assertEquals("你好", result.text)
        assertEquals(2, result.cursor)
    }

    @Test
    fun insertAddsSpaceBetweenAsciiWordsButNotBetweenChinese() {
        val english = VoiceTranscriptInsert.insert(
            current = "hello",
            selectionStart = 5,
            selectionEnd = 5,
            transcript = "world",
        )
        assertEquals("hello world", english.text)

        val chinese = VoiceTranscriptInsert.insert(
            current = "你好",
            selectionStart = 2,
            selectionEnd = 2,
            transcript = "再见",
        )
        assertEquals("你好再见", chinese.text)

        // 插入点原文以空白结尾时不再补空格。
        val trailingSpace = VoiceTranscriptInsert.insert(
            current = "hello ",
            selectionStart = 6,
            selectionEnd = 6,
            transcript = "world",
        )
        assertEquals("hello world", trailingSpace.text)
    }

    @Test
    fun insertEmptyTranscriptChangesNothing() {
        val result = VoiceTranscriptInsert.insert(
            current = "已经写好的字",
            selectionStart = 3,
            selectionEnd = 3,
            transcript = "   ",
        )
        assertEquals("已经写好的字", result.text)
        assertEquals(3, result.cursor)
    }

    @Test
    fun insertClampsOutOfRangeCursor() {
        // 越界光标夹到 0 / 末尾；英文单词相邻仍会补一个空格（与 insertAddsSpace 同一条规则）。
        val negative = VoiceTranscriptInsert.insert(
            current = "abc",
            selectionStart = -3,
            selectionEnd = -3,
            transcript = "X",
        )
        assertEquals("X abc", negative.text)

        val beyond = VoiceTranscriptInsert.insert(
            current = "abc",
            selectionStart = 99,
            selectionEnd = 99,
            transcript = "X",
        )
        assertEquals("abc X", beyond.text)
    }

    @Test
    fun insertionResultHasNoAutoSendConcept() {
        // 「不自动发送」的结构保证：插入纯函数只返回文本与光标，不产生任何发送/请求动作；
        // 真正发送只发生在用户点主按钮（ComposerMainAction.SEND）。这里用类型/字段锁死这一点。
        val insertion = VoiceTranscriptInsert.insert("", 0, 0, "转写结果")
        assertEquals("转写结果", insertion.text)
        assertEquals(insertion.cursor, insertion.text.length)
    }

    // ------------------------------------------------------------ 手势阈值

    @Test
    fun slideUpBeyondThresholdCancels() {
        val threshold = 160f
        assertFalse(VoiceHoldGesture.shouldCancel(dragUpPx = 0f, thresholdPx = threshold))
        assertFalse(VoiceHoldGesture.shouldCancel(dragUpPx = 159f, thresholdPx = threshold))
        assertTrue(VoiceHoldGesture.shouldCancel(dragUpPx = 160f, thresholdPx = threshold))
        assertTrue(VoiceHoldGesture.shouldCancel(dragUpPx = 400f, thresholdPx = threshold))
        // 阈值非法时永远不取消（防御除零/未初始化）。
        assertFalse(VoiceHoldGesture.shouldCancel(dragUpPx = 999f, thresholdPx = 0f))
    }

    // ------------------------------------------------------------ 05：armed 收尾
    // 05「上滑取消后 60s 边界仍转写」：armed 后收尾必须丢弃，不能进 START_TRANSCRIBE。

    @Test
    fun armedSlideCancelMeansDiscard() {
        assertTrue(VoiceFinishPolicy.shouldDiscard(armed = true))
        assertFalse(VoiceFinishPolicy.shouldDiscard(armed = false))
    }

    @Test
    fun armedCancelLandsInIdleWithoutStartingTranscribe() {
        val machine = VoiceInputMachine()
        assertEquals(
            listOf(VoiceInputEffect.START_RECORDING),
            machine.dispatch(VoiceInputEvent.Start(permissionGranted = true)),
        )
        assertEquals(VoiceInputState.Recording, machine.state.value)
        assertTrue(VoiceFinishPolicy.shouldDiscard(armed = true))

        // 60 秒自停/松手收尾走的就是这一条：Cancel → Idle + 丢弃录音（删临时文件）。
        val effects = machine.dispatch(VoiceInputEvent.Cancel)
        assertEquals("armed 后取消必须回到空闲", VoiceInputState.Idle, machine.state.value)
        assertEquals(listOf(VoiceInputEffect.CANCEL_RECORDING_DISCARD), effects)
        assertFalse("armed 后取消绝不能开始转写", effects.contains(VoiceInputEffect.START_TRANSCRIBE))

        // 迟到的 RecordingFinished 在 Idle 被吞：不会复活转写（60 秒边界的第二道保险）。
        val late = machine.dispatch(VoiceInputEvent.RecordingFinished(durationMs = 60_000L))
        assertEquals(VoiceInputState.Idle, machine.state.value)
        assertTrue("Idle 下的 RecordingFinished 不产生任何副作用", late.isEmpty())
    }

    // ------------------------------------------------------------ 08：第二行语音入口

    @Test
    fun voiceMicOnlyInTextModeWithContentStreamingOffAndGateVisible() {
        // 全真值表（四个输入）。
        assertTrue(
            "文字模式 + 有内容 + 未生成 + 门禁可见 → 显示",
            shouldShowVoiceMic(
                voiceEntryVisible = true,
                mode = ComposerMode.TEXT,
                streaming = false,
                hasComposableContent = true,
            ),
        )
        assertFalse(
            "空输入不显示（主按钮本身就是麦克风，避免两个麦克风）",
            shouldShowVoiceMic(true, ComposerMode.TEXT, false, hasComposableContent = false),
        )
        assertFalse(
            "语音模式不显示（第一行已是按住说话）",
            shouldShowVoiceMic(true, ComposerMode.VOICE, false, hasComposableContent = true),
        )
        assertFalse(
            "生成中不显示（主按钮是停下）",
            shouldShowVoiceMic(true, ComposerMode.TEXT, streaming = true, hasComposableContent = true),
        )
        assertFalse(
            "门禁隐藏（非 DeepSeek/没密钥/没麦克风）不显示",
            shouldShowVoiceMic(voiceEntryVisible = false, ComposerMode.TEXT, false, true),
        )
        // 穷举四输入的所有组合，只有唯一一支为真（防止以后把条件改宽/改窄）。
        var truthCount = 0
        listOf(false, true).forEach { visible ->
            listOf(ComposerMode.TEXT, ComposerMode.VOICE).forEach { mode ->
                listOf(false, true).forEach { streaming ->
                    listOf(false, true).forEach { hasContent ->
                        if (shouldShowVoiceMic(visible, mode, streaming, hasContent)) truthCount++
                    }
                }
            }
        }
        assertEquals("16 种组合里只有「可见 + 文字模式 + 未生成 + 有内容」为真", 1, truthCount)
    }

    // ------------------------------------------------------------ 09：缺时长回退

    @Test
    fun accountedDurationUsesServerFirstThenLocalFallback() {
        assertEquals("服务端有正时长 → 用服务端", 3_200L, accountedDurationMs(serverMs = 3_200L, localMs = 1_000L))
        assertEquals("服务端缺时长（0）→ 回退本地录音时长", 1_000L, accountedDurationMs(serverMs = 0L, localMs = 1_000L))
        assertEquals("服务端负数 → 回退本地录音时长", 1_000L, accountedDurationMs(serverMs = -5L, localMs = 1_000L))
        assertEquals("两者都无效才记 0", 0L, accountedDurationMs(serverMs = 0L, localMs = 0L))
        assertEquals("本地负数夹到 0（不记负数费用）", 0L, accountedDurationMs(serverMs = 0L, localMs = -1L))
    }

    // ------------------------------------------------------------ UI 状态默认值

    @Test
    fun voiceUiStateDefaultsAreTextIdleHidden() {
        val state = VoiceUiState()
        assertFalse(state.visible)
        assertNull(state.hiddenReason)
        assertEquals(ComposerMode.TEXT, state.mode)
        assertEquals(VoiceUiPhase.IDLE, state.phase)
        assertEquals(0L, state.elapsedMs)
        assertNull(state.error)
        assertFalse(state.showIntro)
        assertFalse(state.showPermissionIntro)
        assertFalse(state.showPermissionDenied)
        assertEquals(0, state.permissionLaunchToken)
        assertNull(state.insertText)
        assertEquals(0, state.insertToken)
        assertFalse("默认不在「松手取消」态", state.slideCancelArmed)
        assertFalse(state.isVoiceMode)
    }

    // ------------------------------------------------------------ v0.6.10 文件草稿与主按钮

    private fun readyFile(id: String = "f1") = DraftFile(
        id = id,
        localPath = "attachments/$id.pdf",
        fileName = "$id.pdf",
        mime = "application/pdf",
        sizeBytes = 1024L,
        purpose = FilesClient.PURPOSE_TEXT_RECOGNITION,
        status = DraftStatus.READY,
    )

    private fun preparingFile(id: String = "f-prep") = readyFile(id).copy(status = DraftStatus.PREPARING)

    private fun failedFile(id: String = "f-fail") = readyFile(id).copy(status = DraftStatus.FAILED)

    @Test
    fun fileDraftRulesEnforceMaxThreeAndIgnoreFailedInQuota() {
        val three = listOf(readyFile("1"), readyFile("2"), readyFile("3"))
        assertFalse(FileDraftRules.canAdd(three))
        assertEquals(3, FileDraftRules.activeCount(three))

        val twoPlusFailed = listOf(readyFile("1"), readyFile("2"), failedFile("bad"))
        assertTrue("失败文件不占名额", FileDraftRules.canAdd(twoPlusFailed))
        assertEquals(2, FileDraftRules.activeCount(twoPlusFailed))
        assertTrue(FileDraftRules.hasReady(twoPlusFailed))
        assertFalse(FileDraftRules.isPreparing(twoPlusFailed))

        assertTrue(FileDraftRules.isPreparing(listOf(preparingFile())))
    }

    @Test
    fun readyFilesOnlyEnableSendAndPreparingOrUploadingDisablesSend() {
        val readyOnly = ComposerMainButtonResolver.resolve(
            drafts = emptyList(),
            hasText = false,
            streaming = false,
            mode = ComposerMode.TEXT,
            fileDrafts = listOf(readyFile()),
        )
        assertEquals(ComposerMainIcon.ARROW_UP, readyOnly.icon)
        assertEquals(ComposerMainAction.SEND, readyOnly.action)
        assertTrue("只有就绪文件时必须显示可点的发送按钮", readyOnly.enabled)

        val preparingFileState = ComposerMainButtonResolver.resolve(
            drafts = emptyList(),
            hasText = true,
            streaming = false,
            mode = ComposerMode.TEXT,
            fileDrafts = listOf(preparingFile()),
        )
        assertEquals(ComposerMainIcon.ARROW_UP, preparingFileState.icon)
        assertFalse("文件准备中必须禁用发送", preparingFileState.enabled)

        val uploadingState = ComposerMainButtonResolver.resolve(
            drafts = emptyList(),
            hasText = false,
            streaming = false,
            mode = ComposerMode.TEXT,
            fileDrafts = listOf(readyFile()),
            uploadingFiles = true,
        )
        assertEquals(ComposerMainIcon.ARROW_UP, uploadingState.icon)
        assertFalse("文件上传中必须禁用发送", uploadingState.enabled)
    }
}
