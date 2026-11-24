package cc.pulsez.app.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 语音输入状态机（ 基建层，纯 JVM）。
 *
 * 覆盖约束：TRANSCRIBING 不能重录、录音中离开页面/锁屏/开始发送要取消并丢弃、
 * 错误后能回 IDLE、转写成功不自动发送（只回 IDLE）。
 */
class VoiceInputStateTest {

    private fun machine(): VoiceInputMachine = VoiceInputMachine()

    @Test
    fun `初始状态是空闲`() {
        assertEquals(VoiceInputState.Idle, machine().state.value)
    }

    @Test
    fun `已授权时按下麦克风直接开始录音`() {
        val transition = VoiceInputReducer.reduce(
            VoiceInputState.Idle,
            VoiceInputEvent.Start(permissionGranted = true),
        )
        assertEquals(VoiceInputState.Recording, transition.state)
        assertEquals(listOf(VoiceInputEffect.START_RECORDING), transition.effects)
    }

    @Test
    fun `未授权时先请求权限`() {
        val transition = VoiceInputReducer.reduce(
            VoiceInputState.Idle,
            VoiceInputEvent.Start(permissionGranted = false),
        )
        assertEquals(VoiceInputState.RequestingPermission, transition.state)
        assertEquals(listOf(VoiceInputEffect.REQUEST_PERMISSION), transition.effects)
    }

    @Test
    fun `权限允许后只回空闲 不自动录音`() {
        // 送检前修复：用户点「允许」时已经松手，不能自动开录音；
        // 否则会变成「没按住却在录」，到 60 秒自停还会转写计费。
        val transition = VoiceInputReducer.reduce(
            VoiceInputState.RequestingPermission,
            VoiceInputEvent.PermissionResult(granted = true),
        )
        assertEquals(VoiceInputState.Idle, transition.state)
        assertTrue(
            "授权成功不能产生 START_RECORDING（录音必须等用户再按住）",
            transition.effects.isEmpty(),
        )
    }

    @Test
    fun `授权成功后必须重新按住才开始录音`() {
        val machine = machine()
        machine.dispatch(VoiceInputEvent.Start(permissionGranted = false))
        assertEquals(
            "授权只回待按，不产生副作用",
            emptyList<VoiceInputEffect>(),
            machine.dispatch(VoiceInputEvent.PermissionResult(granted = true)),
        )
        assertEquals(VoiceInputState.Idle, machine.state.value)

        // 用户真正再按住：这时才允许出 START_RECORDING。
        assertEquals(
            listOf(VoiceInputEffect.START_RECORDING),
            machine.dispatch(VoiceInputEvent.Start(permissionGranted = true)),
        )
        assertEquals(VoiceInputState.Recording, machine.state.value)
    }

    @Test
    fun `非等待权限状态下的权限回调不产生副作用`() {
        // 迟到的回调（页面已清理/状态已回空闲）不能把状态机推进录音。
        listOf(
            VoiceInputState.Idle,
            VoiceInputState.PermissionRequired,
            VoiceInputState.Recording,
        ).forEach { state ->
            val transition = VoiceInputReducer.reduce(
                state,
                VoiceInputEvent.PermissionResult(granted = true),
            )
            assertEquals("$state：迟到回调不改状态", state, transition.state)
            assertTrue("$state：迟到回调没有副作用", transition.effects.isEmpty())
        }
    }

    @Test
    fun `权限拒绝后进入需要权限 不崩也不卡死`() {
        val transition = VoiceInputReducer.reduce(
            VoiceInputState.RequestingPermission,
            VoiceInputEvent.PermissionResult(granted = false),
        )
        assertEquals(VoiceInputState.PermissionRequired, transition.state)
        assertTrue("拒绝权限没有副作用", transition.effects.isEmpty())
        // 用户再点一次仍可再次请求（系统可能不再弹，由 UI 引导去设置）。
        val again = VoiceInputReducer.reduce(
            VoiceInputState.PermissionRequired,
            VoiceInputEvent.Start(permissionGranted = false),
        )
        assertEquals(VoiceInputState.RequestingPermission, again.state)
    }

    @Test
    fun `录音完成且够长进入转写`() {
        val transition = VoiceInputReducer.reduce(
            VoiceInputState.Recording,
            VoiceInputEvent.RecordingFinished(durationMs = 1_000L),
        )
        assertEquals(VoiceInputState.Transcribing, transition.state)
        assertEquals(listOf(VoiceInputEffect.START_TRANSCRIBE), transition.effects)
    }

    @Test
    fun `不足 500ms 报太短且不上传`() {
        val transition = VoiceInputReducer.reduce(
            VoiceInputState.Recording,
            VoiceInputEvent.RecordingFinished(durationMs = 499L),
        )
        assertEquals(VoiceInputState.Error(VoiceInputError.TOO_SHORT), transition.state)
        assertFalse(
            "太短不能产生转写副作用（不上传、不花钱）",
            transition.effects.contains(VoiceInputEffect.START_TRANSCRIBE),
        )
    }

    @Test
    fun `500ms 边界算有效`() {
        val transition = VoiceInputReducer.reduce(
            VoiceInputState.Recording,
            VoiceInputEvent.RecordingFinished(durationMs = 500L),
        )
        assertEquals(VoiceInputState.Transcribing, transition.state)
    }

    @Test
    fun `转写中不能重录`() {
        val machine = machine()
        machine.dispatch(VoiceInputEvent.Start(permissionGranted = true))
        machine.dispatch(VoiceInputEvent.RecordingFinished(durationMs = 1_000L))
        assertEquals(VoiceInputState.Transcribing, machine.state.value)

        val effects = machine.dispatch(VoiceInputEvent.Start(permissionGranted = true))
        assertEquals("重按不能打断转写、不能重开录音", VoiceInputState.Transcribing, machine.state.value)
        assertTrue(effects.isEmpty())
    }

    @Test
    fun `录音中重复按下不重复开始`() {
        val transition = VoiceInputReducer.reduce(
            VoiceInputState.Recording,
            VoiceInputEvent.Start(permissionGranted = true),
        )
        assertEquals(VoiceInputState.Recording, transition.state)
        assertTrue(transition.effects.isEmpty())
    }

    @Test
    fun `录音失败映射成明确错误码`() {
        val cases = mapOf(
            VoiceRecorderError.PERMISSION_DENIED to VoiceInputError.PERMISSION_DENIED,
            VoiceRecorderError.MIC_UNAVAILABLE to VoiceInputError.RECORDING_UNAVAILABLE,
            VoiceRecorderError.INIT_FAILED to VoiceInputError.RECORDING_UNAVAILABLE,
            VoiceRecorderError.START_FAILED to VoiceInputError.RECORDING_UNAVAILABLE,
            VoiceRecorderError.ALREADY_RECORDING to VoiceInputError.RECORDING_UNAVAILABLE,
            VoiceRecorderError.NOT_RECORDING to VoiceInputError.RECORDING_UNAVAILABLE,
            VoiceRecorderError.IO_ERROR to VoiceInputError.RECORDING_FAILED,
        )
        cases.forEach { (recorderError, expected) ->
            val transition = VoiceInputReducer.reduce(
                VoiceInputState.Recording,
                VoiceInputEvent.RecordingFailed(recorderError),
            )
            assertEquals(VoiceInputState.Error(expected), transition.state)
        }
    }

    @Test
    fun `转写成功只回空闲 不自动发送`() {
        val transition = VoiceInputReducer.reduce(
            VoiceInputState.Transcribing,
            VoiceInputEvent.TranscribeSucceeded,
        )
        assertEquals(VoiceInputState.Idle, transition.state)
        assertTrue("成功不能产生发送副作用（结果由 UI 填入输入框）", transition.effects.isEmpty())
    }

    @Test
    fun `转写失败进入错误`() {
        val transition = VoiceInputReducer.reduce(
            VoiceInputState.Transcribing,
            VoiceInputEvent.TranscribeFailed,
        )
        assertEquals(VoiceInputState.Error(VoiceInputError.TRANSCRIBE_FAILED), transition.state)
    }

    @Test
    fun `录音中离开页面要取消并丢弃`() {
        listOf(
            VoiceInputEvent.PageLeft,
            VoiceInputEvent.AppBackgrounded,
            VoiceInputEvent.SendStarted,
            VoiceInputEvent.Cancel,
        ).forEach { event ->
            val transition = VoiceInputReducer.reduce(VoiceInputState.Recording, event)
            assertEquals("$event 应回到空闲", VoiceInputState.Idle, transition.state)
            assertEquals(
                "$event 必须停麦克风并删临时文件",
                listOf(VoiceInputEffect.CANCEL_RECORDING_DISCARD),
                transition.effects,
            )
        }
    }

    @Test
    fun `转写中离开页面要取消请求并丢弃结果`() {
        listOf(
            VoiceInputEvent.PageLeft,
            VoiceInputEvent.AppBackgrounded,
            VoiceInputEvent.SendStarted,
            VoiceInputEvent.Cancel,
        ).forEach { event ->
            val transition = VoiceInputReducer.reduce(VoiceInputState.Transcribing, event)
            assertEquals(VoiceInputState.Idle, transition.state)
            assertEquals(listOf(VoiceInputEffect.CANCEL_TRANSCRIBE), transition.effects)
        }
    }

    @Test
    fun `空闲时取消是安全的空操作`() {
        val transition = VoiceInputReducer.reduce(VoiceInputState.Idle, VoiceInputEvent.Cancel)
        assertEquals(VoiceInputState.Idle, transition.state)
        assertTrue(transition.effects.isEmpty())
    }

    @Test
    fun `错误可以被关闭回空闲`() {
        val errorState = VoiceInputState.Error(VoiceInputError.TRANSCRIBE_FAILED)
        val transition = VoiceInputReducer.reduce(errorState, VoiceInputEvent.DismissError)
        assertEquals(VoiceInputState.Idle, transition.state)
        assertTrue(transition.effects.isEmpty())
    }

    @Test
    fun `错误状态下可以直接重试`() {
        val transition = VoiceInputReducer.reduce(
            VoiceInputState.Error(VoiceInputError.TOO_SHORT),
            VoiceInputEvent.Start(permissionGranted = true),
        )
        assertEquals(VoiceInputState.Recording, transition.state)
        assertEquals(listOf(VoiceInputEffect.START_RECORDING), transition.effects)
    }

    @Test
    fun `无关事件不会改变状态也不会产生副作用`() {
        val events = listOf(
            VoiceInputEvent.RecordingFinished(1_000L),
            VoiceInputEvent.TranscribeSucceeded,
            VoiceInputEvent.TranscribeFailed,
            VoiceInputEvent.DismissError,
        )
        events.forEach { event ->
            val transition = VoiceInputReducer.reduce(VoiceInputState.Idle, event)
            assertEquals(VoiceInputState.Idle, transition.state)
            assertTrue("$event 在空闲态应被忽略", transition.effects.isEmpty())
        }
    }

    @Test
    fun `machine 串起整条成功链路`() {
        val machine = machine()
        assertEquals(
            listOf(VoiceInputEffect.START_RECORDING),
            machine.dispatch(VoiceInputEvent.Start(permissionGranted = true)),
        )
        assertEquals(
            listOf(VoiceInputEffect.START_TRANSCRIBE),
            machine.dispatch(VoiceInputEvent.RecordingFinished(durationMs = 3_200L)),
        )
        assertTrue(machine.dispatch(VoiceInputEvent.TranscribeSucceeded).isEmpty())
        assertEquals(VoiceInputState.Idle, machine.state.value)
    }

    @Test
    fun `machine 串起权限链路`() {
        val machine = machine()
        assertEquals(
            listOf(VoiceInputEffect.REQUEST_PERMISSION),
            machine.dispatch(VoiceInputEvent.Start(permissionGranted = false)),
        )
        // 允许后回 Idle、不录音（送检前修复）。
        assertTrue(machine.dispatch(VoiceInputEvent.PermissionResult(granted = true)).isEmpty())
        assertEquals(VoiceInputState.Idle, machine.state.value)
        // 再按住才真正录音。
        assertEquals(
            listOf(VoiceInputEffect.START_RECORDING),
            machine.dispatch(VoiceInputEvent.Start(permissionGranted = true)),
        )
        assertEquals(VoiceInputState.Recording, machine.state.value)
        assertEquals(
            listOf(VoiceInputEffect.CANCEL_RECORDING_DISCARD),
            machine.dispatch(VoiceInputEvent.PageLeft),
        )
        assertEquals(VoiceInputState.Idle, machine.state.value)
    }

    @Test
    fun `只有已授权的开始事件能进入录音`() {
        // 结构级护栏：把「所有状态 × 所有事件」跑一遍，只有 Start(permissionGranted = true)
        // 能进 RECORDING 并带 START_RECORDING——防止以后有人给别的路径（如权限回调）偷偷加回录音。
        val states = listOf(
            VoiceInputState.Idle,
            VoiceInputState.PermissionRequired,
            VoiceInputState.RequestingPermission,
            VoiceInputState.Recording,
            VoiceInputState.Transcribing,
            VoiceInputState.Error(VoiceInputError.TOO_SHORT),
        )
        val events = listOf(
            VoiceInputEvent.Start(permissionGranted = true),
            VoiceInputEvent.Start(permissionGranted = false),
            VoiceInputEvent.PermissionResult(granted = true),
            VoiceInputEvent.PermissionResult(granted = false),
            VoiceInputEvent.RecordingFinished(1_000L),
            VoiceInputEvent.RecordingFailed(VoiceRecorderError.IO_ERROR),
            VoiceInputEvent.TranscribeSucceeded,
            VoiceInputEvent.TranscribeFailed,
            VoiceInputEvent.Cancel,
            VoiceInputEvent.PageLeft,
            VoiceInputEvent.AppBackgrounded,
            VoiceInputEvent.SendStarted,
            VoiceInputEvent.DismissError,
        )
        val recordingStarts = mutableListOf<String>()
        states.forEach { state ->
            events.forEach { event ->
                val transition = VoiceInputReducer.reduce(state, event)
                if (VoiceInputEffect.START_RECORDING in transition.effects) {
                    recordingStarts += "$state + $event → ${transition.state}"
                }
                // 产生 START_RECORDING 就必须同时进入 RECORDING（不能只开录音不落状态）。
                if (VoiceInputEffect.START_RECORDING in transition.effects) {
                    assertEquals(
                        "$state + $event：开始录音必须进 RECORDING",
                        VoiceInputState.Recording,
                        transition.state,
                    )
                }
            }
        }
        assertEquals(
            "只有「已授权 + 用户按下」的 Start 能开始录音（Idle / PermissionRequired / Error 三个待按态）：$recordingStarts",
            3,
            recordingStarts.size,
        )
        recordingStarts.forEach { entry ->
            assertTrue(
                "出现非「按住且已授权」的录音入口：$entry",
                entry.contains("Start(permissionGranted=true)"),
            )
            assertFalse("授权回调不得自动录音：$entry", entry.contains("PermissionResult"))
        }
    }
}
