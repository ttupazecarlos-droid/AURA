package com.example.aura.player

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

class AudioPlayerManager private constructor(private val context: Context) {

    private val scope = CoroutineScope(Dispatchers.Main)
    private var mediaPlayer: MediaPlayer? = null
    private var progressJob: Job? = null

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    private var audioFocusRequest: AudioFocusRequest? = null
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

    private val audioFocusChangeListener = AudioManager.OnAudioFocusChangeListener { focusChange ->
        when (focusChange) {
            AudioManager.AUDIOFOCUS_LOSS -> {
                pausePlayback()
                abandonAudioFocus()
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
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
            }
        }
    }

    private val noisyReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            if (intent?.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) {
                pausePlayback()
            }
        }
    }

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

    fun cancelSleepTimer() {
        sleepTimerJob?.cancel()
        sleepTimerJob = null
        _sleepTimerRemainingMs.value = 0L
    }

    init {
        initMediaPlayer()
    }

    private fun initMediaPlayer() {
        releasePlayer()
        mediaPlayer = MediaPlayer().apply {
            setAudioAttributes(
                AudioAttributes.Builder()
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .build()
            )
            setOnCompletionListener {
                handleTrackCompletion()
            }
            setOnErrorListener { _, what, extra ->
                Log.e("AudioPlayerManager", "MediaPlayer error: what=$what extra=$extra")
                pausePlayback()
                true
            }
            setOnPreparedListener { mp ->
                _durationMs.value = mp.duration.toLong().coerceAtLeast(0L)
                if (requestAudioFocus()) {
                    mp.start()
                    _isPlaying.value = true
                    startProgressTracker()
                    registerNoisyReceiver()
                    try {
                        com.example.aura.service.MusicPlaybackService.startService(context)
                    } catch (e: Exception) {
                        Log.w("AudioPlayerManager", "Notification service start: ${e.message}")
                    }
                }
            }
        }
    }

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

    private fun playTrack(song: Song) {
        _currentSong.value = song
        _currentPositionMs.value = 0L
        _durationMs.value = song.durationMs

        try {
            if (mediaPlayer == null) {
                initMediaPlayer()
            } else {
                mediaPlayer?.reset()
            }

            val uri = Uri.parse(song.uriString)
            mediaPlayer?.setDataSource(context, uri)
            mediaPlayer?.prepareAsync()
        } catch (e: Exception) {
            Log.e("AudioPlayerManager", "Failed to play ${song.title}", e)
            _isPlaying.value = false
        }
    }

    fun togglePlayPause() {
        if (_currentSong.value == null && _queue.value.isNotEmpty()) {
            playTrack(_queue.value.first())
            return
        }
        val mp = mediaPlayer ?: run {
            initMediaPlayer()
            _currentSong.value?.let { playTrack(it) }
            return
        }

        try {
            if (mp.isPlaying) {
                pausePlayback()
                abandonAudioFocus()
            } else {
                if (requestAudioFocus()) {
                    mp.start()
                    _isPlaying.value = true
                    startProgressTracker()
                    registerNoisyReceiver()
                    try {
                        com.example.aura.service.MusicPlaybackService.startService(context)
                    } catch (_: Exception) {}
                }
            }
        } catch (e: IllegalStateException) {
            // Tras un error de decodificación el player queda en estado inválido y `isPlaying`/`start` lanzan.
            Log.w("AudioPlayerManager", "Invalid player state, recreating", e)
            initMediaPlayer()
            _isPlaying.value = false
            _currentSong.value?.let { playTrack(it) }
        }
    }

    private fun pausePlayback() {
        try {
            mediaPlayer?.let { mp ->
                if (mp.isPlaying) {
                    mp.pause()
                }
            }
        } catch (_: Exception) {}
        _isPlaying.value = false
        stopProgressTracker()
        unregisterNoisyReceiver()
    }

    fun stop() {
        pausePlayback()
        abandonAudioFocus()
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

    fun seekTo(positionMs: Long) {
        val mp = mediaPlayer ?: return
        try {
            mp.seekTo(positionMs.toInt())
            _currentPositionMs.value = positionMs
        } catch (e: Exception) {
            Log.e("AudioPlayerManager", "Seek error", e)
        }
    }

    fun next() {
        val q = _queue.value
        if (q.isEmpty()) return

        if (_isShuffle.value && q.size > 1) {
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

    fun toggleShuffle() {
        _isShuffle.value = !_isShuffle.value
        resetShuffleHistory()
    }

    private fun resetShuffleHistory() {
        shuffledPlayedIndices.clear()
        shuffledPlayedIndices.add(_currentIndex.value)
    }

    fun toggleRepeat() {
        _repeatMode.value = when (_repeatMode.value) {
            RepeatMode.OFF -> RepeatMode.ALL
            RepeatMode.ALL -> RepeatMode.ONE
            RepeatMode.ONE -> RepeatMode.OFF
        }
    }

    fun addToQueue(song: Song) {
        _queue.value = _queue.value + song
        if (_currentSong.value == null) {
            playSong(song)
        }
    }

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
        val am = audioManager ?: return true
        val result = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val audioAttributes = AudioAttributes.Builder()
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .build()
            val focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(audioAttributes)
                .setAcceptsDelayedFocusGain(true)
                .setOnAudioFocusChangeListener(audioFocusChangeListener)
                .build()
            audioFocusRequest = focusRequest
            am.requestAudioFocus(focusRequest)
        } else {
            @Suppress("DEPRECATION")
            am.requestAudioFocus(
                audioFocusChangeListener,
                AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN
            )
        }
        return result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
    }

    private fun abandonAudioFocus() {
        val am = audioManager ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioFocusRequest?.let { am.abandonAudioFocusRequest(it) }
        } else {
            @Suppress("DEPRECATION")
            am.abandonAudioFocus(audioFocusChangeListener)
        }
    }

    private fun registerNoisyReceiver() {
        if (!isNoisyReceiverRegistered) {
            try {
                context.registerReceiver(
                    noisyReceiver,
                    IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY)
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

    private fun stopProgressTracker() {
        progressJob?.cancel()
        progressJob = null
    }

    fun releasePlayer() {
        stopProgressTracker()
        unregisterNoisyReceiver()
        abandonAudioFocus()
        cancelSleepTimer()
        try {
            mediaPlayer?.release()
            mediaPlayer = null
        } catch (_: Exception) {}
    }

    companion object {
        @Volatile
        private var INSTANCE: AudioPlayerManager? = null

        fun getInstance(context: Context): AudioPlayerManager {
            return INSTANCE ?: synchronized(this) {
                val instance = AudioPlayerManager(context.applicationContext)
                INSTANCE = instance
                instance
            }
        }
    }
}
