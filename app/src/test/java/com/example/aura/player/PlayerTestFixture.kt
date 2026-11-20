package com.example.aura.player

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.media.MediaPlayer
import android.net.Uri
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.example.aura.model.Song
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowMediaPlayer
import org.robolectric.shadows.util.DataSource
import org.robolectric.util.ReflectionHelpers
import java.time.Duration

@OptIn(ExperimentalCoroutinesApi::class)
internal class PlayerTestFixture : AutoCloseable {
    val context = ApplicationProvider.getApplicationContext<Context>()
    private val dispatcher = StandardTestDispatcher()
    private val previousSingleton =
        ReflectionHelpers.getStaticField<AudioPlayerManager?>(AudioPlayerManager::class.java, "INSTANCE")
    private var installedSingleton = false
    lateinit var mediaPlayer: MediaPlayer
        private set
    val mediaShadow get() = shadowOf(mediaPlayer)
    val audioShadow = shadowOf(context.getSystemService(Context.AUDIO_SERVICE) as AudioManager)
    val manager: AudioPlayerManager

    init {
        Dispatchers.setMain(dispatcher)
        ShadowMediaPlayer.resetStaticState()
        ShadowMediaPlayer.setCreateListener { player, shadow ->
            mediaPlayer = player
            shadow.setInvalidStateBehavior(ShadowMediaPlayer.InvalidStateBehavior.EMULATE)
        }
        manager = AudioPlayerManager::class.java.getDeclaredConstructor(Context::class.java)
            .apply { isAccessible = true }
            .newInstance(context)
    }

    fun installSingleton() {
        ReflectionHelpers.setStaticField(AudioPlayerManager::class.java, "INSTANCE", manager)
        installedSingleton = true
    }

    fun song(id: String = "song"): Song {
        val song = Song(
            id = id,
            title = id,
            artist = "Artist",
            durationMs = 60_000L,
            uriString = "content://aura.test/audio/$id"
        )
        ShadowMediaPlayer.addMediaInfo(
            DataSource.toDataSource(context, Uri.parse(song.uriString)),
            ShadowMediaPlayer.MediaInfo(60_000, 100)
        )
        return song
    }

    fun finishPreparation() {
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100))
        runCurrent()
    }

    fun startPlaying() {
        manager.playSong(song())
        finishPreparation()
        assertTrue("La pista debe estar sonando antes de probar la interrupción", manager.isPlaying.value)
        assertEquals(ShadowMediaPlayer.State.STARTED, mediaShadow.state)
    }

    fun runCurrent() {
        shadowOf(Looper.getMainLooper()).idle()
        dispatcher.scheduler.runCurrent()
    }

    fun advanceProgressTick() {
        dispatcher.scheduler.advanceTimeBy(250)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(250))
        runCurrent()
    }

    fun changeFocus(change: Int) {
        val request = audioShadow.lastAudioFocusRequest
        assertNotNull("La reproducción debe solicitar foco de audio", request)
        request.listener.onAudioFocusChange(change)
        runCurrent()
    }

    fun disconnectHeadphones() {
        context.sendBroadcast(Intent(AudioManager.ACTION_AUDIO_BECOMING_NOISY))
        runCurrent()
    }

    fun command(name: String) {
        // Permite compilar la fase RED antes de añadir play()/pause() a la API pública.
        val method = AudioPlayerManager::class.java.methods
            .firstOrNull { it.name == name && it.parameterCount == 0 }
        assertNotNull("Falta el control público idempotente AudioPlayerManager.$name()", method)
        method!!.invoke(manager)
        runCurrent()
    }

    override fun close() {
        try {
            manager.stop()
            manager.releasePlayer()
            runCurrent()
        } finally {
            if (installedSingleton) {
                ReflectionHelpers.setStaticField(
                    AudioPlayerManager::class.java, "INSTANCE", previousSingleton
                )
            }
            audioShadow.setNextFocusRequestResponse(AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
            ShadowMediaPlayer.resetStaticState()
            Dispatchers.resetMain()
        }
    }
}
