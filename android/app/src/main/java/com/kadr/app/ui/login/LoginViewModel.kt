package com.kadr.app.ui.login

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kadr.app.data.prefs.SettingsStore
import com.kadr.app.data.remote.HealthResponse
import com.kadr.app.data.repo.BackupRepository
import com.kadr.app.ui.readable
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class LoginUiState(
    val serverUrl: String = "",
    val username: String = "",
    val password: String = "",
    val busy: Boolean = false,
    val error: String? = null,
    val health: HealthResponse? = null,
) {
    val canSubmit: Boolean
        get() = !busy && serverUrl.isNotBlank() && username.isNotBlank() && password.isNotBlank()
}

@HiltViewModel
class LoginViewModel @Inject constructor(
    private val repository: BackupRepository,
    settingsStore: SettingsStore,
) : ViewModel() {

    private val _state = MutableStateFlow(LoginUiState(serverUrl = settingsStore.current.serverUrl))
    val state: StateFlow<LoginUiState> = _state.asStateFlow()

    fun onServerUrlChange(value: String) {
        _state.update { it.copy(serverUrl = value, error = null, health = null) }
    }

    fun onUsernameChange(value: String) {
        _state.update { it.copy(username = value, error = null) }
    }

    fun onPasswordChange(value: String) {
        _state.update { it.copy(password = value, error = null) }
    }

    /** Reachability check before asking for credentials. */
    fun testConnection() {
        val url = _state.value.serverUrl
        if (url.isBlank()) {
            _state.update { it.copy(error = "Enter the server address first.") }
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(busy = true, error = null, health = null) }
            repository.health(url)
                .onSuccess { health -> _state.update { it.copy(busy = false, health = health) } }
                .onFailure { e -> _state.update { it.copy(busy = false, error = e.readable()) } }
        }
    }

    fun signIn(onSignedIn: () -> Unit) {
        val current = _state.value
        if (!current.canSubmit) return

        viewModelScope.launch {
            _state.update { it.copy(busy = true, error = null) }
            repository.login(current.serverUrl, current.username, current.password)
                .onSuccess {
                    // The password is not kept anywhere, not even in this state.
                    _state.update { it.copy(busy = false, password = "") }
                    onSignedIn()
                }
                .onFailure { e -> _state.update { it.copy(busy = false, error = e.readable()) } }
        }
    }
}
