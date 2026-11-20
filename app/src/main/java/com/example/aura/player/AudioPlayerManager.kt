package com.example.aura.player

/**
 * ARCHIVO: AudioPlayerManager.kt
 * FUNCIÓN: Núcleo de reproducción. Envuelve MediaPlayer, mantiene el estado observable
 *          (pista actual, play/pause, posición, cola, shuffle, repeat, sleep timer) y expone
 *          las acciones de control. Es un singleton compartido con el servicio de notificación.
 * NAVEGACIÓN: AuraViewModel/AuraApp/PlayerScreen -> AudioPlayerManager -> MusicPlaybackService.
 */

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import com.example.aura.model.Song
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.random.Random

enum class RepeatMode {
    OFF, ALL, ONE
}

// Devuelve null cuando ya no quedan pistas y repeat está en OFF: fin de cola.
internal fun pickShuffleIndex(
    queueSize: Int,
    currentIndex: Int,
    playedIndices: Set<Int>,
    repeatAll: Boolean,
    random: Random = Random.Default
): Int? {
    if (queueSize <= 0) return null
    var candidates = (0 until queueSize).filter { it != currentIndex && it !in playedIndices }
    if (candidates.isEmpty()) {
        if (!repeatAll) return null
        candidates = (0 until queueSize).filter { it != currentIndex }
        if (candidates.isEmpty()) return currentIndex
    }
    return candidates[random.nextInt(candidates.size)]
}

/**
 * AudioPlayerManager: gestor único de reproducción de la app.
 * Todos los estados son StateFlow para que Compose se actualice automáticamente.
 */
class AudioPlayerManager private constructor(private val context: Context) {

    private val scope = CoroutineScope(Dispatchers.Main)
    private var mediaPlayer: MediaPlayer? = null
    private var progressJob: Job? = null
    private var isPrepared = false
    private var playWhenReady = false
    private var isPlaybackServiceReady = false

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    private var audioFocusRequest: AudioFocusRequest? = null
    private var audioFocusChangeListener: AudioManager.OnAudioFocusChangeListener? = null
    private var audioFocusGeneration = 0L
    private var hasAudioFocus = false
    private var waitingForAudioFocus = false
    private var isNoisyReceiverRegistered = false

    private val _currentSong = MutableStateFlow<Song?>(null)
    val currentSong: StateFlow<Song?> = _currentSong.asStateFlow()

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    private val _currentPositionMs = MutableStateFlow(0L)
    val currentPositionMs: StateFlow<Long> = _currentPositionMs.asStateFlow()

    private val _durationMs = MutableStateFlow(0L)
    val durationMs: StateFlow<Long> = _durationMs.asStateFlow()

    private val _queue = MutableStateFlow<List<Song>>(emptyList())
    val queue: StateFlow<List<Song>> = _queue.asStateFlow()

    private val _currentIndex = MutableStateFlow(0)
    val currentIndex: StateFlow<Int> = _currentIndex.asStateFlow()

    private val _isShuffle = MutableStateFlow(false)
    val isShuffle: StateFlow<Boolean> = _isShuffle.asStateFlow()

    private val _repeatMode = MutableStateFlow(RepeatMode.ALL)
    val repeatMode: StateFlow<RepeatMode> = _repeatMode.asStateFlow()

    private var sleepTimerJob: Job? = null
    private val _sleepTimerRemainingMs = MutableStateFlow(0L)
    val sleepTimerRemainingMs: StateFlow<Long> = _sleepTimerRemainingMs.asStateFlow()

    // Índices ya reproducidos en el ciclo actual de shuffle, para no repetir hasta agotar la cola.
    private val shuffledPlayedIndices = mutableSetOf<Int>()

    private fun handleAudioFocusChange(focusChange: Int) {
        when (focusChange) {
            AudioManager.AUDIOFOCUS_LOSS -> {
                pause()
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                hasAudioFocus = false
                waitingForAudioFocus = playWhenReady
                pausePlayback()
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                try {
                    mediaPlayer?.setVolume(0.3f, 0.3f)
                } catch (_: Exception) {}
            }
            AudioManager.AUDIOFOCUS_GAIN -> {
                try {
                    mediaPlayer?.setVolume(1.0f, 1.0f)
                } catch (_: Exception) {}
                if (playWhenReady && waitingForAudioFocus) {
                    waitingForAudioFocus = false
                    hasAudioFocus = true
                    startPlaybackIfReady()
                }
            }
        }
    }

    private val noisyReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            if (intent?.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) {
                pause()
            }
        }
    }

    // Programa el apagado automático: al agotarse, detiene la reproducción y el servicio.
    fun setSleepTimer(minutes: Int) {
        cancelSleepTimer()
        if (minutes <= 0) return
        val totalMs = minutes * 60 * 1000L
        _sleepTimerRemainingMs.value = totalMs
        sleepTimerJob = scope.launch {
            var remaining = totalMs
            while (isActive && remaining > 0) {
                delay(1000L)
                remaining -= 1000L
                _sleepTimerRemainingMs.value = remaining.coerceAtLeast(0L)
            }
            if (isActive && remaining <= 0) {
                stopPlaybackAndService()
            }
        }
    }

    // Cancela el temporizador de apagado y reinicia la cuenta atrás.
    fun cancelSleepTimer() {
        sleepTimerJob?.cancel()
        sleepTimerJob = null
        _sleepTimerRemainingMs.value = 0L
    }

    init {
        initMediaPlayer()
    }

    // Crea/reinicia MediaPlayer y registra los listeners de preparación, fin y error.
    private fun initMediaPlayer() {
        releaseMediaPlayer()
        mediaPlayer = MediaPlayer().apply {
            setAudioAttributes(
                AudioAttributes.Builder()
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .build()
            )
            setOnCompletionListener { mp ->
                if (mp === mediaPlayer && playWhenReady) handleTrackCompletion()
            }
            setOnErrorListener { mp, what, extra ->
                if (mp === mediaPlayer) {
                    Log.e("AudioPlayerManager", "MediaPlayer error: what=$what extra=$extra")
                    pause()
                    releaseMediaPlayer()
                }
                true
            }
            setOnPreparedListener { mp ->
                if (mp !== mediaPlayer || isPrepared) return@setOnPreparedListener
                isPrepared = true
                _durationMs.value = mp.duration.toLong().coerceAtLeast(0L)
                startPlaybackIfReady()
            }
        }
    }

    // Reproduce una canción y, si se indica, reemplaza la cola actual.
    fun playSong(song: Song, newQueue: List<Song>? = null) {
        if (newQueue != null && newQueue.isNotEmpty()) {
            _queue.value = newQueue
            val idx = newQueue.indexOfFirst { it.id == song.id }
            _currentIndex.value = if (idx >= 0) idx else 0
        } else if (_queue.value.none { it.id == song.id }) {
            _queue.value = _queue.value + song
            _currentIndex.value = _queue.value.lastIndex
        } else {
            val idx = _queue.value.indexOfFirst { it.id == song.id }
            if (idx >= 0) _currentIndex.value = idx
        }

        playTrack(song)
        resetShuffleHistory()
    }

    // Carga la URI de la pista en MediaPlayer y prepara la reproducción asíncrona.
    private fun playTrack(song: Song) {
        pausePlayback()
        _currentSong.value = song
        _currentPositionMs.value = 0L
        _durationMs.value = song.durationMs
        playWhenReady = true
        registerNoisyReceiver()

        try {
            // Cada pista posee su player: un callback antiguo no puede preparar la pista nueva.
            initMediaPlayer()
            val uri = Uri.parse(song.uriString)
            mediaPlayer?.setDataSource(context, uri)
            startPlaybackService()
            mediaPlayer?.prepareAsync()
        } catch (e: Exception) {
            Log.e("AudioPlayerManager", "Failed to play ${song.title}", e)
            pause()
            releaseMediaPlayer()
        }
    }

    // Alterna entre reproducir y pausar; si no hay pista activa, arranca la primera de la cola.
    fun togglePlayPause() {
        if (playWhenReady) pause() else play()
    }

    fun play() {
        if (playWhenReady) return
        val song = _currentSong.value ?: _queue.value.firstOrNull() ?: return
        if (mediaPlayer == null || _currentSong.value == null) {
            playTrack(song)
            return
        }
        playWhenReady = true
        registerNoisyReceiver()
        startPlaybackService()
        startPlaybackIfReady()
    }

    fun pause() {
        playWhenReady = false
        pausePlayback()
        unregisterNoisyReceiver()
        abandonAudioFocus()
    }

    private fun startPlaybackIfReady() {
        if (!playWhenReady || !isPrepared || _isPlaying.value) return
        // Desde API35, pedir foco fuera de TOP exige un FGS ya activo, no solo solicitado.
        if (Build.VERSION.SDK_INT >= 35 && !isPlaybackServiceReady) return
        val mp = mediaPlayer ?: return
        try {
            if (!requestAudioFocus()) return
            mp.start()
            _isPlaying.value = true
            startProgressTracker()
        } catch (e: Exception) {
            Log.w("AudioPlayerManager", "Failed to start playback", e)
            pause()
            releaseMediaPlayer()
        }
    }

    private fun startPlaybackService() {
        try {
            com.example.aura.service.MusicPlaybackService.startService(context)
        } catch (e: Exception) {
            Log.w("AudioPlayerManager", "Notification service start: ${e.message}")
        }
    }

    internal fun onPlaybackServiceReady() {
        isPlaybackServiceReady = true
        startPlaybackIfReady()
    }

    internal fun onPlaybackServiceStopped() {
        isPlaybackServiceReady = false
    }

    // Pausa la reproducción sin liberar el reproductor (pérdida de foco, audio ruidoso, etc.).
    private fun pausePlayback() {
        try {
            if (isPrepared) mediaPlayer?.let { mp ->
                if (mp.isPlaying) {
                    mp.pause()
                }
                _currentPositionMs.value = mp.currentPosition.toLong().coerceAtLeast(0L)
            }
        } catch (_: Exception) {}
        _isPlaying.value = false
        stopProgressTracker()
    }

    fun stop() {
        pause()
        if (!isPrepared) releaseMediaPlayer()
    }

    // Fin de cola: además de pausar, quita la notificación en primer plano.
    private fun stopPlaybackAndService() {
        stop()
        try {
            com.example.aura.service.MusicPlaybackService.stopService(context)
        } catch (e: Exception) {
            Log.w("AudioPlayerManager", "Failed to stop playback service", e)
        }
    }

    // Salta a una posición concreta de la pista (ms).
    fun seekTo(positionMs: Long) {
        val mp = mediaPlayer ?: return
        if (!isPrepared) return
        val position = positionMs.coerceIn(0L, _durationMs.value.coerceAtLeast(0L))
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                mp.seekTo(position, MediaPlayer.SEEK_PREVIOUS_SYNC)
            } else {
                mp.seekTo(position.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
            }
            _currentPositionMs.value = position
        } catch (e: Exception) {
            Log.e("AudioPlayerManager", "Seek error", e)
        }
    }

    // Pasa a la siguiente pista (aleatoria si shuffle está activo; respeta repeat ALL).
    fun next() {
        val q = _queue.value
        if (q.isEmpty()) return

        if (_isShuffle.value && q.size > 1) {
            if (shuffledPlayedIndices.size >= q.size && _repeatMode.value != RepeatMode.OFF) {
                shuffledPlayedIndices.clear()
            }
            val nextIdx = pickShuffleIndex(
                queueSize = q.size,
                currentIndex = _currentIndex.value,
                playedIndices = shuffledPlayedIndices,
                repeatAll = _repeatMode.value != RepeatMode.OFF
            ) ?: run {
                stopPlaybackAndService()
                return
            }
            shuffledPlayedIndices.add(nextIdx)
            _currentIndex.value = nextIdx
            playTrack(q[nextIdx])
            return
        }

        val nextIdx = _currentIndex.value + 1
        if (nextIdx < q.size) {
            _currentIndex.value = nextIdx
            playTrack(q[nextIdx])
        } else if (_repeatMode.value == RepeatMode.ALL) {
            _currentIndex.value = 0
            playTrack(q[0])
        } else {
            stopPlaybackAndService()
        }
    }

    // Vuelve a la pista anterior; si han pasado >3 s, reinicia la actual.
    fun previous() {
        val q = _queue.value
        if (q.isEmpty()) return

        if (_currentPositionMs.value > 3000L) {
            seekTo(0L)
            return
        }

        val prevIdx = _currentIndex.value - 1
        if (prevIdx >= 0) {
            _currentIndex.value = prevIdx
            playTrack(q[prevIdx])
        } else if (_repeatMode.value == RepeatMode.ALL) {
            _currentIndex.value = q.lastIndex
            playTrack(q[q.lastIndex])
        } else {
            seekTo(0L)
        }
    }

    // Callback de fin de pista: repite una o avanza según el modo repeat.
    private fun handleTrackCompletion() {
        when (_repeatMode.value) {
            RepeatMode.ONE -> {
                _currentSong.value?.let { playTrack(it) }
            }
            RepeatMode.ALL, RepeatMode.OFF -> {
                next()
            }
        }
    }

    // Activa/desactiva la reproducción aleatoria.
    fun toggleShuffle() {
        _isShuffle.value = !_isShuffle.value
        resetShuffleHistory()
    }

    private fun resetShuffleHistory() {
        shuffledPlayedIndices.clear()
        shuffledPlayedIndices.add(_currentIndex.value)
    }

    // Ciclo del modo repeat: OFF -> ALL -> ONE -> OFF.
    fun toggleRepeat() {
        _repeatMode.value = when (_repeatMode.value) {
            RepeatMode.OFF -> RepeatMode.ALL
            RepeatMode.ALL -> RepeatMode.ONE
            RepeatMode.ONE -> RepeatMode.OFF
        }
    }

    // Añade una pista al final de la cola; si no hay nada sonando, la reproduce.
    fun addToQueue(song: Song) {
        _queue.value = _queue.value + song
        if (_currentSong.value == null) {
            playSong(song)
        }
    }

    // Refresca URI/metadatos sin interrumpir el descriptor que ya está reproduciéndose.
    fun updateQueuedSong(previousId: String, song: Song) {
        _queue.value = _queue.value.map { if (it.id == previousId) song else it }
        if (_currentSong.value?.id == previousId) _currentSong.value = song
    }

    // Elimina una pista de la cola y ajusta el índice actual si es necesario.
    fun removeFromQueue(index: Int) {
        val curList = _queue.value.toMutableList()
        if (index in curList.indices) {
            curList.removeAt(index)
            _queue.value = curList
            if (index < _currentIndex.value) {
                _currentIndex.value = (_currentIndex.value - 1).coerceAtLeast(0)
            } else if (index == _currentIndex.value && curList.isNotEmpty()) {
                val nextIdx = index.coerceAtMost(curList.lastIndex)
                _currentIndex.value = nextIdx
                playTrack(curList[nextIdx])
            } else if (curList.isEmpty()) {
                _currentSong.value = null
                stopPlaybackAndService()
            }
            resetShuffleHistory()
        }
    }

    // Reordena la cola moviendo una pista de una posición a otra.
    fun reorderQueue(from: Int, to: Int) {
        val curList = _queue.value.toMutableList()
        if (from in curList.indices && to in curList.indices) {
            val item = curList.removeAt(from)
            curList.add(to, item)
            _queue.value = curList
            val currentSongId = _currentSong.value?.id
            val newCurrentIndex = curList.indexOfFirst { it.id == currentSongId }
            if (newCurrentIndex >= 0) {
                _currentIndex.value = newCurrentIndex
            }
            resetShuffleHistory()
        }
    }

    private fun requestAudioFocus(): Boolean {
        if (hasAudioFocus) return true
        if (waitingForAudioFocus) return false
        val am = audioManager ?: run {
            hasAudioFocus = true
            return true
        }
        val generation = ++audioFocusGeneration
        val listener = AudioManager.OnAudioFocusChangeListener { change ->
            if (generation == audioFocusGeneration) handleAudioFocusChange(change)
        }
        audioFocusChangeListener = listener
        val result = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val audioAttributes = AudioAttributes.Builder()
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .build()
            val focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(audioAttributes)
                .setAcceptsDelayedFocusGain(true)
                .setOnAudioFocusChangeListener(listener)
                .build()
            audioFocusRequest = focusRequest
            am.requestAudioFocus(focusRequest)
        } else {
            @Suppress("DEPRECATION")
            am.requestAudioFocus(
                listener,
                AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN
            )
        }
        hasAudioFocus = result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        waitingForAudioFocus = result == AudioManager.AUDIOFOCUS_REQUEST_DELAYED
        if (!hasAudioFocus && !waitingForAudioFocus) pause()
        return hasAudioFocus
    }

    private fun abandonAudioFocus() {
        audioFocusGeneration++
        hasAudioFocus = false
        waitingForAudioFocus = false
        val request = audioFocusRequest
        val listener = audioFocusChangeListener
        audioFocusRequest = null
        audioFocusChangeListener = null
        val am = audioManager ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            request?.let { am.abandonAudioFocusRequest(it) }
        } else {
            @Suppress("DEPRECATION")
            listener?.let { am.abandonAudioFocus(it) }
        }
    }

    private fun registerNoisyReceiver() {
        if (!isNoisyReceiverRegistered) {
            try {
                ContextCompat.registerReceiver(
                    context,
                    noisyReceiver,
                    IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY),
                    ContextCompat.RECEIVER_NOT_EXPORTED
                )
                isNoisyReceiverRegistered = true
            } catch (e: Exception) {
                Log.w("AudioPlayerManager", "Failed to register noisy receiver", e)
            }
        }
    }

    private fun unregisterNoisyReceiver() {
        if (isNoisyReceiverRegistered) {
            try {
                context.unregisterReceiver(noisyReceiver)
            } catch (_: Exception) {}
            isNoisyReceiverRegistered = false
        }
    }

    // Bucle que publica la posición actual cada 250 ms mientras suena la pista.
    private fun startProgressTracker() {
        stopProgressTracker()
        progressJob = scope.launch {
            while (isActive && _isPlaying.value) {
                try {
                    val pos = mediaPlayer?.currentPosition?.toLong() ?: 0L
                    _currentPositionMs.value = pos
                    val dur = mediaPlayer?.duration?.toLong() ?: 0L
                    if (dur > 0) _durationMs.value = dur
                } catch (_: Exception) {}
                delay(250L)
            }
        }
    }

    // Detiene el bucle de seguimiento de progreso.
    private fun stopProgressTracker() {
        progressJob?.cancel()
        progressJob = null
    }

    // Libera MediaPlayer y todos los recursos asociados (llamado al cerrar la app/ViewModel).
    fun releasePlayer() {
        pause()
        cancelSleepTimer()
        releaseMediaPlayer()
    }

    private fun releaseMediaPlayer() {
        stopProgressTracker()
        val player = mediaPlayer
        mediaPlayer = null
        isPrepared = false
        try {
            player?.release()
        } catch (_: Exception) {}
    }

    companion object {
        @Volatile
        private var INSTANCE: AudioPlayerManager? = null

        // Singleton: una sola instancia de reproducción compartida por UI y servicio.
        fun getInstance(context: Context): AudioPlayerManager {
            return INSTANCE ?: synchronized(this) {
                val instance = AudioPlayerManager(context.applicationContext)
                INSTANCE = instance
                instance
            }
        }
    }
}
