package cc.pulsez.app.ui.group

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import cc.pulsez.app.AppContainer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** 成员选择弹层的状态。selectedIds 是唯一真相，弹层与按钮都从它推导。 */
data class GroupPickerUiState(
    val members: List<GroupPickerMember> = emptyList(),
    val selectedIds: Set<String> = emptySet(),
)

/**
 * 成员选择。
 *
 * 这一版只读**现有**的 `container.characters.list()` 拿名字，不碰任何 data/core 新 API：
 * 「一起聊」通过写入 `thread.kind=GROUP` 和 `thread_member` 建群，并返回真实 `threadId`；
 * 见 [buildDraft] 里的 TODO。
 */
class GroupPickerViewModel(private val container: AppContainer) : ViewModel() {

    private val _state = MutableStateFlow(GroupPickerUiState())
    val state: StateFlow<GroupPickerUiState> = _state.asStateFlow()

    init {
        refresh()
    }

    /** 每次打开弹层前重新拉一次角色列表：新建的角色要立刻能选。 */
    fun refresh() {
        viewModelScope.launch {
            val members = container.characters.list().map { character ->
                GroupPickerMember(id = character.id, name = character.name)
            }
            val aliveIds = members.map { it.id }.toSet()
            _state.value = _state.value.copy(
                members = members,
                // 角色可能已被删：把选择里失效的 id 清掉
                selectedIds = _state.value.selectedIds.filter { it in aliveIds }.toSet(),
            )
        }
    }

    /** 勾选 / 取消勾选；已满 4 人时忽略新增（UI 也把未选项置灰，这里是第二道闸）。 */
    fun toggle(id: String) {
        val current = _state.value
        val selected = current.selectedIds
        val next = when {
            id in selected -> selected - id
            selected.size >= GroupRules.MAX_MEMBERS -> selected
            else -> selected + id
        }
        _state.value = current.copy(selectedIds = next)
    }

    fun clearSelection() {
        _state.value = _state.value.copy(selectedIds = emptySet())
    }

    /**
     * 把当前选择整理成群聊页的初始 UI 状态；人数不合法（<2 或 >4）返回 null。
     *
     * 只负责校验与 UI 模型；真正落 GROUP thread + thread_member、拿真实 threadId 由
     * `AppRoot` 调 `ChatRepository.createGroupThread`（接线已完成，见 AppRoot「一起聊」onConfirm）。
     */
    fun buildDraft(): GroupChatUiState? {
        val current = _state.value
        val selectedCount = current.selectedIds.size
        if (selectedCount !in GroupRules.MIN_MEMBERS..GroupRules.MAX_MEMBERS) return null
        val members = current.members
            .filter { it.id in current.selectedIds }
            .map { GroupMemberUi(id = it.id, name = it.name) }
        if (members.size < GroupRules.MIN_MEMBERS) return null
        return GroupChatUiState(
            members = members,
            // 群名先留默认「你们几个」 允许自定义群名，v0.2 不做入口
            title = GroupChatUiState.TITLE_DEFAULT,
        )
    }

    companion object {
        fun factory(container: AppContainer): ViewModelProvider.Factory = viewModelFactory {
            initializer { GroupPickerViewModel(container) }
        }
    }
}
