package dev.warp.mobile.ssh

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class SshSessionState {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    ERROR
}

class SshSessionViewModel : ViewModel() {
    private val _sessionState = MutableStateFlow(SshSessionState.DISCONNECTED)
    val sessionState: StateFlow<SshSessionState> = _sessionState.asStateFlow()
    
    private val connectionManager = SshConnectionManager()

    fun connect(config: SshConnectionConfig) {
        _sessionState.value = SshSessionState.CONNECTING
        try {
            val result = connectionManager.connect(config)
            if (result is SshHostKeyVerification.Accept) {
                _sessionState.value = SshSessionState.CONNECTED
            } else {
                _sessionState.value = SshSessionState.ERROR
            }
        } catch (e: Exception) {
            _sessionState.value = SshSessionState.ERROR
        }
    }

    fun disconnect() {
        connectionManager.disconnect()
        _sessionState.value = SshSessionState.DISCONNECTED
    }
}
