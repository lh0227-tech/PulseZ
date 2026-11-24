package cc.pulsez.app.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import cc.pulsez.app.AppContainer
import cc.pulsez.app.data.repo.UsageSnapshot
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 用量与花费。
 * 不说 token，说"字"；不说命中率，说"省下"。上限默认不限制，用户填了才生效，改完立即生效。
 */
data class UsageUiState(
    val snapshot: UsageSnapshot = UsageSnapshot(0.0, 0.0, 0, 0, 0.0),
    val dailyLimitYuan: Int = 0,
    val monthlyLimitYuan: Int = 0,
    val editing: Boolean = false,
    val message: String? = null,
)

class UsageViewModel(private val container: AppContainer) : ViewModel() {

    private val _state = MutableStateFlow(UsageUiState())
    val state: StateFlow<UsageUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            container.usage.flow.collect { snapshot ->
                _state.value = _state.value.copy(snapshot = snapshot)
            }
        }
        viewModelScope.launch {
            container.settings.flow.collect { settings ->
                _state.value = _state.value.copy(
                    dailyLimitYuan = settings.dailyLimitYuanX100 / 100,
                    monthlyLimitYuan = settings.monthlyLimitYuanX100 / 100,
                )
            }
        }
    }

    fun setDailyLimit(yuan: Int) {
        viewModelScope.launch {
            container.settings.setDailyLimit(yuan * 100)
            _state.value = _state.value.copy(message = "改好了，马上生效。")
        }
    }

    fun setMonthlyLimit(yuan: Int) {
        viewModelScope.launch {
            container.settings.setMonthlyLimit(yuan * 100)
            _state.value = _state.value.copy(message = "改好了，马上生效。")
        }
    }

    fun clearMessage() {
        _state.value = _state.value.copy(message = null)
    }

    companion object {
        fun factory(container: AppContainer): ViewModelProvider.Factory = viewModelFactory {
            initializer { UsageViewModel(container) }
        }
    }
}
