package com.example

/**
 * ARCHIVO: MainActivity.kt
 * FUNCIÓN: Punto de entrada de la aplicación. Bloquea el acceso con biometría, solicita el
 *          permiso de notificaciones, monta la interfaz Compose (AuraApp) y gestiona los intents
 *          con los que se abre un archivo de audio desde otros gestores de archivos.
 * NAVEGACIÓN: MainActivity -> AuraApp -> AuraViewModel -> AudioPlayerManager / LocalFileManager.
 *
 * La música local ya no se detecta con MediaStore: AURA solo lee las carpetas SAF que el usuario
 * autoriza desde el gestor de archivos.
 */

import android.Manifest
import android.app.KeyguardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import android.view.WindowManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.aura.auth.AppLock
import com.example.aura.data.storage.takePersistableReadPermission
import com.example.aura.ui.AuraApp
import com.example.aura.ui.AuraViewModel
import com.example.ui.theme.AuraDarkBackground
import com.example.ui.theme.MyApplicationTheme

private const val DEVICE_CREDENTIAL_REQUEST = 1001

class MainActivity : FragmentActivity() {

    private val appLock by lazy { ViewModelProvider(this)[AppLock::class.java] }
    private lateinit var biometricPrompt: BiometricPrompt

    // Referencia al ViewModel para usarla fuera de Compose (onNewIntent / onDestroy).
    private var viewModelInstance: AuraViewModel? = null

    /**
     * onCreate: inicializa la actividad, activa edge-to-edge, pide el permiso de notificaciones
     * (Android 13+) y renderiza AuraApp dentro del tema de la app.
     */
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Evita que la vista reciente muestre la biblioteca después de bloquearla.
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        enableEdgeToEdge()
        biometricPrompt = BiometricPrompt(this, ContextCompat.getMainExecutor(this),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    appLock.authenticated()
                    processPendingActions()
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    if (appLock.isConfirmingDeviceCredential) return
                    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R &&
                        errorCode == BiometricPrompt.ERROR_NEGATIVE_BUTTON && appLock.isAuthenticating
                    ) {
                        confirmDeviceCredential()
                    } else {
                        appLock.authenticationError(errString.toString())
                    }
                }

                override fun onAuthenticationFailed() {
                    // Android mantiene el diálogo abierto y permite otro intento.
                }
            })

        setContent {
            MyApplicationTheme {
                if (!appLock.isUnlocked) {
                    Dialog(
                        onDismissRequest = { finish() },
                        properties = DialogProperties(
                            dismissOnClickOutside = false,
                            usePlatformDefaultWidth = false
                        )
                    ) {
                        Surface(modifier = Modifier.fillMaxSize(), color = AuraDarkBackground) {
                            Column(
                                modifier = Modifier.safeDrawingPadding()
                                    .verticalScroll(rememberScrollState()).padding(24.dp),
                                verticalArrangement = Arrangement.spacedBy(16.dp)
                            ) {
                                Text("AURA bloqueada", style = MaterialTheme.typography.headlineLarge)
                                Text(appLock.message)
                                Button(onClick = ::authenticate, enabled = !appLock.isAuthenticating) {
                                    Text("Desbloquear AURA")
                                }
                                TextButton(onClick = ::openSecuritySettings, enabled = !appLock.isAuthenticating) {
                                    Text("Abrir ajustes de seguridad")
                                }
                            }
                        }
                    }
                }
                // Mantiene los selectores SAF y su estado al volver del segundo plano.
                if (appLock.hasUnlockedOnce) {
                    val viewModel: AuraViewModel = viewModel()
                    viewModelInstance = viewModel

                    // Solo se piden notificaciones: las carpetas de música usan permisos SAF.
                    val permissionsToRequest = buildList {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            add(Manifest.permission.POST_NOTIFICATIONS)
                        }
                    }

                    val permissionLauncher = rememberLauncherForActivityResult(
                        contract = ActivityResultContracts.RequestMultiplePermissions()
                    ) { }

                    // Procesa aperturas pendientes solo después de desbloquear.
                    LaunchedEffect(appLock.isUnlocked) {
                        if (!appLock.isUnlocked) return@LaunchedEffect
                        val missing = permissionsToRequest.filter { permission ->
                            ContextCompat.checkSelfPermission(
                                this@MainActivity,
                                permission
                            ) != PackageManager.PERMISSION_GRANTED
                        }
                        if (missing.isNotEmpty()) {
                            permissionLauncher.launch(missing.toTypedArray())
                        }

                        // Comprueba si la app se abrió con un archivo de audio (ACTION_VIEW / ACTION_SEND).
                        handleIncomingIntent(intent, viewModel)
                    }

                    // Contenedor raíz de la interfaz Compose.
                    Surface(
                        modifier = Modifier.fillMaxSize(),
                        color = AuraDarkBackground
                    ) {
                        AuraApp(viewModel = viewModel)
                    }
                }
            }
        }
    }

    override fun onStart() {
        // Evalúa la sesión antes de que se entreguen resultados de actividades externas.
        if (!getSystemService(KeyguardManager::class.java).isDeviceSecure) appLock.lock()
        appLock.onForeground(SystemClock.elapsedRealtime())
        super.onStart()
    }

    override fun onResume() {
        super.onResume()
        processPendingActions()
        if (!appLock.hasAttemptedAuthentication) authenticate()
    }

    private fun processPendingActions() {
        appLock.takeActivityResults().forEach { pending ->
            deliverActivityResult(pending.requestCode, pending.resultCode, pending.data)
        }
        viewModelInstance?.let { handleIncomingIntent(intent, it) }
    }

    // Los selectores externos no deben importar archivos antes de volver a autenticarse.
    @Deprecated("Callback de compatibilidad usado por ActivityResultRegistry")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode == DEVICE_CREDENTIAL_REQUEST) {
            appLock.deviceCredentialResult(resultCode == RESULT_OK)
            processPendingActions()
            return
        }
        if (appLock.isUnlocked) {
            deliverActivityResult(requestCode, resultCode, data)
        } else {
            appLock.deferActivityResult(requestCode, resultCode, data)
        }
    }

    private fun deliverActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
    }

    override fun onStop() {
        // El PIN nativo puede cubrir la actividad: no cancelar esa autenticación pendiente.
        val systemAuthenticationInProgress = appLock.isAuthenticating &&
            (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R || appLock.isConfirmingDeviceCredential)
        if (!isChangingConfigurations && !systemAuthenticationInProgress) {
            appLock.onBackground(SystemClock.elapsedRealtime())
            biometricPrompt.cancelAuthentication()
        }
        super.onStop()
    }

    private fun authenticate() {
        if (!appLock.beginAuthentication()) return
        if (!getSystemService(KeyguardManager::class.java).isDeviceSecure) {
            appLock.authenticationError("Configura un PIN, patrón o contraseña en los ajustes de seguridad del teléfono.")
            return
        }

        val authenticators = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            BIOMETRIC_STRONG or DEVICE_CREDENTIAL
        } else {
            BIOMETRIC_STRONG
        }
        if (BiometricManager.from(this).canAuthenticate(authenticators) == BiometricManager.BIOMETRIC_SUCCESS) {
            val promptInfo = BiometricPrompt.PromptInfo.Builder()
                .setTitle("Desbloquear AURA")
                .setSubtitle("Usa la huella o el bloqueo de seguridad del teléfono")
                .setAllowedAuthenticators(authenticators)
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
                promptInfo.setNegativeButtonText("Usar PIN, patrón o contraseña")
            }
            biometricPrompt.authenticate(promptInfo.build())
        } else {
            confirmDeviceCredential()
        }
    }

    private fun confirmDeviceCredential() {
        if (!appLock.beginDeviceCredential()) return
        // Android 7-10 no admite STRONG | DEVICE_CREDENTIAL en BiometricPrompt.
        val credentialIntent = getSystemService(KeyguardManager::class.java)
            .createConfirmDeviceCredentialIntent("Desbloquear AURA", "Confirma el bloqueo de seguridad del teléfono")
        if (credentialIntent == null) {
            appLock.authenticationError("Configura un PIN, patrón o contraseña en los ajustes de seguridad del teléfono.")
            return
        }
        try {
            startActivityForResult(credentialIntent, DEVICE_CREDENTIAL_REQUEST)
        } catch (_: android.content.ActivityNotFoundException) {
            appLock.authenticationError("No se pudo abrir la verificación de seguridad del teléfono.")
        }
    }

    private fun openSecuritySettings() {
        try {
            startActivity(Intent(Settings.ACTION_SECURITY_SETTINGS))
        } catch (_: android.content.ActivityNotFoundException) {
            appLock.authenticationError("Abre los ajustes del teléfono y configura un método de bloqueo en Seguridad.")
        }
    }

    /**
     * onNewIntent: la actividad usa launchMode="singleTop"; aquí se recibe un nuevo archivo
     * abierto mientras la app ya estaba en ejecución.
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (appLock.isUnlocked) {
            viewModelInstance?.let { handleIncomingIntent(intent, it) }
        }
    }

    /**
     * handleIncomingIntent: si el intent trae una URI de audio (VIEW o SEND), importa y
     * reproduce esa pista a través del ViewModel.
     */
    private fun handleIncomingIntent(intent: Intent?, viewModel: AuraViewModel) {
        if (!appLock.isUnlocked || intent == null) return
        val action = intent.action
        val data: Uri? = intent.data

        if ((Intent.ACTION_VIEW == action || Intent.ACTION_SEND == action) && data != null) {
            takePersistableReadPermission(data)
            viewModel.importSongFromUri(data)
            // No volver a importar ni reproducir el mismo archivo al desbloquear otra vez.
            setIntent(Intent(this, MainActivity::class.java))
        }
    }

    /**
     * onDestroy: si la actividad se cierra definitivamente, detiene el reproductor y el
     * servicio de reproducción en primer plano.
     */
    override fun onDestroy() {
        if (isFinishing) {
            viewModelInstance?.playerManager?.stop()
            com.example.aura.service.MusicPlaybackService.stopService(this)
        }
        super.onDestroy()
    }
}
