package com.kadr.app.ui.pair

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kadr.app.data.prefs.SettingsStore
import com.kadr.app.data.remote.HealthResponse
import com.kadr.app.data.repo.BackupRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class PairUiState(
    val serverUrl: String = "",
    val code: String = "",
    val busy: Boolean = false,
    val error: String? = null,
    val health: HealthResponse? = null,
)

@HiltViewModel
class PairViewModel @Inject constructor(
    private val repository: BackupRepository,
    settingsStore: SettingsStore,
) : ViewModel() {

    private val _state = MutableStateFlow(PairUiState(serverUrl = settingsStore.current.serverUrl))
    val state: StateFlow<PairUiState> = _state.asStateFlow()

    fun onServerUrlChange(value: String) {
        _state.update { it.copy(serverUrl = value, error = null, health = null) }
    }

    fun onCodeChange(value: String) {
        _state.update { it.copy(code = value.filter(Char::isDigit).take(6), error = null) }
    }

    /** Reachability check before asking for a code — a 5-minute code is precious. */
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

    fun pair(onPaired: () -> Unit) {
        val current = _state.value
        if (current.code.length != 6) {
            _state.update { it.copy(error = "The pairing code is 6 digits.") }
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(busy = true, error = null) }
            repository.pair(current.serverUrl, current.code)
                .onSuccess {
                    _state.update { it.copy(busy = false) }
                    onPaired()
                }
                .onFailure { e -> _state.update { it.copy(busy = false, error = e.readable()) } }
        }
    }
}

internal fun Throwable.readable(): String =
    message?.takeIf { it.isNotBlank() } ?: this::class.simpleName ?: "Something went wrong."
