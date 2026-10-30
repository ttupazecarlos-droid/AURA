package com.example

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.aura.data.storage.takePersistableReadPermission
import com.example.aura.ui.AuraApp
import com.example.aura.ui.AuraViewModel
import com.example.ui.theme.AuraDarkBackground
import com.example.ui.theme.MyApplicationTheme

class MainActivity : ComponentActivity() {

    private var viewModelInstance: AuraViewModel? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            MyApplicationTheme {
                val viewModel: AuraViewModel = viewModel()
                viewModelInstance = viewModel

                // Permissions launcher for audio access & notifications
                val permissionsToRequest = buildList {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        add(Manifest.permission.READ_MEDIA_AUDIO)
                        add(Manifest.permission.POST_NOTIFICATIONS)
                    } else {
                        add(Manifest.permission.READ_EXTERNAL_STORAGE)
                    }
                }

                val permissionLauncher = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.RequestMultiplePermissions()
                ) { permissions ->
                    val audioGranted = permissions[Manifest.permission.READ_MEDIA_AUDIO] == true ||
                            permissions[Manifest.permission.READ_EXTERNAL_STORAGE] == true
                    if (audioGranted) {
                        viewModel.scanDeviceStorage()
                    }
                }

                LaunchedEffect(Unit) {
                    val missing = permissionsToRequest.filter { perm ->
                        ContextCompat.checkSelfPermission(this@MainActivity, perm) != PackageManager.PERMISSION_GRANTED
                    }

                    if (missing.isNotEmpty()) {
                        permissionLauncher.launch(missing.toTypedArray())
                    } else {
                        viewModel.scanDeviceStorage()
                    }

                    // Check if launched with an audio file intent
                    handleIncomingIntent(intent, viewModel)
                }

                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = AuraDarkBackground
                ) {
                    AuraApp(
                        viewModel = viewModel,
                        onRequestAudioPermission = {
                            val missing = permissionsToRequest.filter { perm ->
                                ContextCompat.checkSelfPermission(
                                    this@MainActivity,
                                    perm
                                ) != PackageManager.PERMISSION_GRANTED
                            }
                            if (missing.isNotEmpty()) {
                                permissionLauncher.launch(missing.toTypedArray())
                            } else {
                                viewModel.scanDeviceStorage()
                            }
                        }
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        viewModelInstance?.let { handleIncomingIntent(intent, it) }
    }

    private fun handleIncomingIntent(intent: Intent?, viewModel: AuraViewModel) {
        if (intent == null) return
        val action = intent.action
        val data: Uri? = intent.data

        if ((Intent.ACTION_VIEW == action || Intent.ACTION_SEND == action) && data != null) {
            takePersistableReadPermission(data)
            viewModel.importSongFromUri(data)
        }
    }

    override fun onDestroy() {
        if (isFinishing) {
            viewModelInstance?.playerManager?.stop()
            com.example.aura.service.MusicPlaybackService.stopService(this)
        }
        super.onDestroy()
    }
}
