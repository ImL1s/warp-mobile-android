package dev.warp.mobile.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import dev.warp.mobile.SessionManager
import dev.warp.mobile.WarpAppState
import kotlinx.coroutines.flow.StateFlow

class WarpMainViewModel(application: Application) : AndroidViewModel(application) {
    private val sessionManager = SessionManager.getInstance(application)

    val appState: StateFlow<WarpAppState> = sessionManager.appState

    fun createNewSession(title: String? = null, cwd: String = "~") {
        sessionManager.createSession(title = title, cwd = cwd)
    }

    fun selectSession(sessionId: String) {
        sessionManager.switchSession(sessionId)
    }

    fun closeSession(sessionId: String) {
        sessionManager.closeSession(sessionId)
    }
}
