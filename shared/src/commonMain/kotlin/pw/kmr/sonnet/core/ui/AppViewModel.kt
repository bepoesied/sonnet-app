import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
fun appViewModelFactory(
    repository: AppViewModelRepository,
    authSessionManager: AuthSessionManager
): ViewModelProvider.Factory = viewModelFactory {
    initializer {
        AppViewModel(repository, authSessionManager)
    }
}

package pw.kmr.sonnet.core.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import pw.kmr.sonnet.shared.auth.AuthSessionManager
import pw.kmr.sonnet.shared.model.AuthSession

class AppViewModel(
    private val repository: AppViewModelRepository,
    authSessionManager: AuthSessionManager
) : ViewModel() {
    private val bootstrapComplete = MutableStateFlow(false)
    private val session = authSessionManager.currentSession.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = null
    )

    val uiState: StateFlow<AppUiState> = combine(bootstrapComplete, session) { complete, currentSession ->
        if (complete) AppUiState.Ready(currentSession) else AppUiState.Loading
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AppUiState.Loading)

    init {
        viewModelScope.launch {
            repository.bootstrapSession()
            bootstrapComplete.value = true
        }
    }

    fun logout() {
        viewModelScope.launch {
            repository.logout()
        }
    }
}

interface AppViewModelRepository {
    suspend fun bootstrapSession()

    suspend fun logout()
}

sealed interface AppUiState {
    data object Loading : AppUiState
    data class Ready(val session: AuthSession?) : AppUiState
}