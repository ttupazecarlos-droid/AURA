package com.example.aura.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.os.Build
import android.os.IBinder
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.media.app.NotificationCompat.MediaStyle
import com.example.MainActivity
import com.example.aura.model.Song
import com.example.aura.player.AudioPlayerManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class MusicPlaybackService : Service() {

    private val serviceScope = CoroutineScope(Dispatchers.Main + Job())
    private var mediaSession: MediaSessionCompat? = null
    private lateinit var playerManager: AudioPlayerManager
    private var observerJob: Job? = null

    companion object {
        const val CHANNEL_ID = "aura_musica_primer_plano"
        const val NOTIFICATION_ID = 1001

        // Controlado desde Ajustes; se aplica a la visibilidad en pantalla de bloqueo.
        var showOnLockscreen: Boolean = true

        const val ACTION_PLAY_PAUSE = "com.example.aura.ACTION_PLAY_PAUSE"
        const val ACTION_NEXT = "com.example.aura.ACTION_NEXT"
        const val ACTION_PREVIOUS = "com.example.aura.ACTION_PREVIOUS"
        const val ACTION_STOP = "com.example.aura.ACTION_STOP"
        const val ACTION_SYNC = "com.example.aura.ACTION_SYNC"

        fun startService(context: Context) {
            val intent = Intent(context, MusicPlaybackService::class.java).apply {
                action = ACTION_SYNC
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ContextCompat.startForegroundService(context, intent)
            } else {
                context.startService(intent)
            }
        }

        fun stopService(context: Context) {
            // Detiene el servicio; no lo arranca si no estaba corriendo (ACTION_STOP lo resucitaba).
            context.stopService(Intent(context, MusicPlaybackService::class.java))
        }
    }

    override fun onCreate() {
        super.onCreate()
        playerManager = AudioPlayerManager.getInstance(this)
        createNotificationChannel()
        setupMediaSession()
        observePlayerState()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PLAY_PAUSE -> {
                playerManager.togglePlayPause()
            }
            ACTION_NEXT -> {
                playerManager.next()
            }
            ACTION_PREVIOUS -> {
                playerManager.previous()
            }
            ACTION_STOP -> {
                playerManager.stop()
                stopForegroundService()
                return START_NOT_STICKY
            }
            ACTION_SYNC -> {
                updateNotification(playerManager.currentSong.value, playerManager.isPlaying.value)
            }
        }
        return START_NOT_STICKY
    }

    private fun setupMediaSession() {
        mediaSession = MediaSessionCompat(this, "AuraMediaSession").apply {
            isActive = true
            setCallback(object : MediaSessionCompat.Callback() {
                override fun onPlay() {
                    playerManager.togglePlayPause()
                }

                override fun onPause() {
                    playerManager.togglePlayPause()
                }

                override fun onSkipToNext() {
                    playerManager.next()
                }

                override fun onSkipToPrevious() {
                    playerManager.previous()
                }

                override fun onStop() {
                    if (playerManager.isPlaying.value) {
                        playerManager.togglePlayPause()
                    }
                    stopForegroundService()
                }
            })
        }
    }

    private fun observePlayerState() {
        observerJob?.cancel()
        observerJob = serviceScope.launch {
            launch {
                playerManager.currentSong.collectLatest { song ->
                    updateNotification(song, playerManager.isPlaying.value)
                }
            }
            launch {
                playerManager.isPlaying.collectLatest { isPlaying ->
                    updateNotification(playerManager.currentSong.value, isPlaying)
                }
            }
        }
    }

    private fun updateNotification(song: Song?, isPlaying: Boolean) {
        if (song == null) {
            return
        }

        updatePlaybackState(isPlaying)

        val notification = buildMediaNotification(song, isPlaying)

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (e: Exception) {
            // Fallback for notification posting
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.notify(NOTIFICATION_ID, notification)
        }
    }

    private fun updatePlaybackState(isPlaying: Boolean) {
        val state = if (isPlaying) {
            PlaybackStateCompat.STATE_PLAYING
        } else {
            PlaybackStateCompat.STATE_PAUSED
        }

        val playbackState = PlaybackStateCompat.Builder()
            .setActions(
                PlaybackStateCompat.ACTION_PLAY or
                        PlaybackStateCompat.ACTION_PAUSE or
                        PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
                        PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or
                        PlaybackStateCompat.ACTION_STOP
            )
            .setState(state, playerManager.currentPositionMs.value, 1.0f)
            .build()

        mediaSession?.setPlaybackState(playbackState)
    }

    private fun buildMediaNotification(song: Song, isPlaying: Boolean): Notification {
        val contentIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val contentPendingIntent = PendingIntent.getActivity(
            this,
            0,
            contentIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Previous Action
        val prevIntent = Intent(this, MusicPlaybackService::class.java).apply { action = ACTION_PREVIOUS }
        val prevPendingIntent = PendingIntent.getService(
            this, 1, prevIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Play/Pause Action
        val playPauseIntent = Intent(this, MusicPlaybackService::class.java).apply { action = ACTION_PLAY_PAUSE }
        val playPausePendingIntent = PendingIntent.getService(
            this, 2, playPauseIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Next Action
        val nextIntent = Intent(this, MusicPlaybackService::class.java).apply { action = ACTION_NEXT }
        val nextPendingIntent = PendingIntent.getService(
            this, 3, nextIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Stop/Close Action
        val stopIntent = Intent(this, MusicPlaybackService::class.java).apply { action = ACTION_STOP }
        val stopPendingIntent = PendingIntent.getService(
            this, 4, stopIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val playPauseIcon = if (isPlaying) {
            android.R.drawable.ic_media_pause
        } else {
            android.R.drawable.ic_media_play
        }
        val playPauseTitle = if (isPlaying) "Pausar" else "Reproducir"

        val qualityLabel = if (song.isLossless) {
            "Sin Pérdida • ${song.format} ${song.bitDepth}b/${song.sampleRate / 1000}kHz"
        } else {
            "Audio Local • ${song.format}"
        }

        val largeIconBitmap = createColoredCoverBitmap(song.coverColorHex)

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setLargeIcon(largeIconBitmap)
            .setContentTitle(song.title)
            .setContentText(song.artist)
            .setSubText(qualityLabel)
            .setContentIntent(contentPendingIntent)
            .setVisibility(
                if (showOnLockscreen) NotificationCompat.VISIBILITY_PUBLIC
                else NotificationCompat.VISIBILITY_SECRET
            )
            .setOngoing(isPlaying)
            .setShowWhen(false)
            .addAction(android.R.drawable.ic_media_previous, "Anterior", prevPendingIntent)
            .addAction(playPauseIcon, playPauseTitle, playPausePendingIntent)
            .addAction(android.R.drawable.ic_media_next, "Siguiente", nextPendingIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Cerrar", stopPendingIntent)
            .setStyle(
                MediaStyle()
                    .setMediaSession(mediaSession?.sessionToken)
                    .setShowActionsInCompactView(0, 1, 2)
                    .setShowCancelButton(true)
                    .setCancelButtonIntent(stopPendingIntent)
            )

        return builder.build()
    }

    private fun createColoredCoverBitmap(colorHex: Long): Bitmap {
        val size = 128
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint().apply {
            isAntiAlias = true
            color = colorHex.toInt()
        }
        canvas.drawRoundRect(0f, 0f, size.toFloat(), size.toFloat(), 24f, 24f, paint)

        // Draw simple musical note symbol in white
        val textPaint = Paint().apply {
            isAntiAlias = true
            color = android.graphics.Color.WHITE
            textSize = 64f
            textAlign = Paint.Align.CENTER
        }
        canvas.drawText("♫", size / 2f, size / 2f + 22f, textPaint)

        return bitmap
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Reproducción de Música AURA",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Controles de reproducción multimedia en primer plano y pantalla de bloqueo"
                setShowBadge(false)
                lockscreenVisibility = if (showOnLockscreen) Notification.VISIBILITY_PUBLIC
                else Notification.VISIBILITY_SECRET
            }

            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    private fun stopForegroundService() {
        observerJob?.cancel()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        stopSelf()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        // Cuando el usuario cierra la aplicación por completo (deslizada de la lista de tareas recientes):
        // Detener la reproducción de inmediato y cancelar el servicio en primer plano.
        playerManager.stop()
        stopForegroundService()
    }

    override fun onDestroy() {
        observerJob?.cancel()
        serviceScope.cancel()
        playerManager.stop()
        mediaSession?.isActive = false
        mediaSession?.release()
        mediaSession = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
