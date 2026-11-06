package com.example.aura.auth

import android.content.Intent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel

private const val REAUTHENTICATION_TIMEOUT_MS = 120_000L

// Estado solo en memoria: nunca restaurar una autorización desde disco o un Bundle.
class AppLock : ViewModel() {
    data class ActivityResult(val requestCode: Int, val resultCode: Int, val data: Intent?)

    private val pendingActivityResults = mutableListOf<ActivityResult>()
    private var authenticatedExitAt: Long? = null
    var isUnlocked by mutableStateOf(false)
        private set
    var hasUnlockedOnce by mutableStateOf(false)
        private set
    var isAuthenticating by mutableStateOf(false)
        private set
    var isConfirmingDeviceCredential = false
        private set
    var hasAttemptedAuthentication = false
        private set
    var message by mutableStateOf("Usa la huella, el PIN, el patrón o la contraseña del teléfono para abrir AURA.")
        private set

    fun beginAuthentication(): Boolean {
        if (isUnlocked || isAuthenticating) return false
        isAuthenticating = true
        hasAttemptedAuthentication = true
        message = "Confirma tu identidad en el diálogo de Android."
        return true
    }

    fun authenticated() {
        if (!isAuthenticating || isConfirmingDeviceCredential) return
        isAuthenticating = false
        isUnlocked = true
        hasUnlockedOnce = true
    }

    fun authenticationError(message: String) {
        lock()
        hasAttemptedAuthentication = true
        this.message = message
    }

    fun beginDeviceCredential(): Boolean {
        if (!isAuthenticating || isConfirmingDeviceCredential) return false
        isConfirmingDeviceCredential = true
        return true
    }

    fun deviceCredentialResult(success: Boolean) {
        if (!isConfirmingDeviceCredential) return
        isConfirmingDeviceCredential = false
        if (success) authenticated()
        else authenticationError("No se confirmó tu identidad. AURA permanece bloqueada.")
    }

    fun lock() {
        isUnlocked = false
        isAuthenticating = false
        isConfirmingDeviceCredential = false
        hasAttemptedAuthentication = false
        authenticatedExitAt = null
    }

    fun onBackground(now: Long) {
        val wasUnlocked = isUnlocked
        lock()
        authenticatedExitAt = now.takeIf { wasUnlocked }
    }

    fun onForeground(now: Long) {
        val exitedAt = authenticatedExitAt ?: return
        authenticatedExitAt = null
        isUnlocked = now - exitedAt in 0L until REAUTHENTICATION_TIMEOUT_MS
    }

    fun deferActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        pendingActivityResults.add(ActivityResult(requestCode, resultCode, data))
    }

    fun takeActivityResults(): List<ActivityResult> {
        if (!isUnlocked) return emptyList()
        return pendingActivityResults.toList().also { pendingActivityResults.clear() }
    }
}
