package com.example.aura.player

import android.app.Application
import android.media.AudioManager
import android.media.MediaPlayer
import android.net.Uri
import com.example.aura.model.Song
import com.example.aura.service.MusicPlaybackService
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@LooperMode(LooperMode.Mode.PAUSED)
class AudioPlayerManagerTest {
    private lateinit var fixture: PlayerTestFixture

    @Before
    fun setUp() {
        fixture = PlayerTestFixture()
    }

    @After
    fun tearDown() {
        fixture.close()
    }

    @Test
    fun `stop durante prepareAsync impide arrancar al llegar onPrepared`() {
        fixture.manager.playSong(fixture.song())

        fixture.manager.stop()
        fixture.finishPreparation()

        assertFalse(fixture.manager.isPlaying.value)
        assertFalse(fixture.mediaShadow.isReallyPlaying)
        assertNull("Stop no debe solicitar foco al terminar la preparación", fixture.audioShadow.lastAudioFocusRequest)
    }

    @Test
    fun `pause durante prepareAsync cancela el arranque pendiente`() {
        fixture.manager.playSong(fixture.song())

        fixture.command("pause")
        fixture.finishPreparation()

        assertFalse(fixture.manager.isPlaying.value)
        assertFalse(fixture.mediaShadow.isReallyPlaying)
    }

    @Test
    fun `toggle durante prepareAsync cancela la intencion de reproducir`() {
        fixture.manager.playSong(fixture.song())

        fixture.manager.togglePlayPause()
        fixture.finishPreparation()

        assertFalse(fixture.manager.isPlaying.value)
        assertFalse(fixture.mediaShadow.isReallyPlaying)
    }

    @Test
    fun `play repetido no pausa ni reinicia la posicion`() {
        fixture.startPlaying()
        fixture.manager.seekTo(5_000L)
        fixture.runCurrent()

        fixture.command("play")
        fixture.command("play")

        assertTrue(fixture.manager.isPlaying.value)
        assertTrue(fixture.mediaShadow.isReallyPlaying)
        assertEquals(5_000L, fixture.manager.currentPositionMs.value)
    }

    @Test
    fun `pause repetido no reanuda la reproduccion`() {
        fixture.startPlaying()

        fixture.command("pause")
        fixture.command("pause")

        assertFalse(fixture.manager.isPlaying.value)
        assertFalse(fixture.mediaShadow.isReallyPlaying)
    }

    @Test
    fun `play durante prepareAsync espera la preparacion sin reiniciarla`() {
        fixture.manager.playSong(fixture.song())

        fixture.command("play")
        fixture.command("play")
        assertFalse(fixture.manager.isPlaying.value)
        fixture.finishPreparation()

        assertTrue(fixture.manager.isPlaying.value)
        assertTrue(fixture.mediaShadow.isReallyPlaying)
    }

    @Test
    fun `el foco diferido arranca al concederse si el usuario aun quiere reproducir`() {
        fixture.audioShadow.setNextFocusRequestResponse(AudioManager.AUDIOFOCUS_REQUEST_DELAYED)
        fixture.manager.playSong(fixture.song())
        fixture.finishPreparation()
        assertFalse(fixture.manager.isPlaying.value)

        fixture.audioShadow.setNextFocusRequestResponse(AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
        fixture.changeFocus(AudioManager.AUDIOFOCUS_GAIN)

        assertTrue(fixture.manager.isPlaying.value)
        assertTrue(fixture.mediaShadow.isReallyPlaying)
    }

    @Test
    fun `stop cancela el arranque con foco diferido aunque llegue gain`() {
        fixture.audioShadow.setNextFocusRequestResponse(AudioManager.AUDIOFOCUS_REQUEST_DELAYED)
        fixture.manager.playSong(fixture.song())
        fixture.finishPreparation()

        fixture.manager.stop()
        fixture.changeFocus(AudioManager.AUDIOFOCUS_GAIN)

        assertFalse(fixture.manager.isPlaying.value)
        assertFalse(fixture.mediaShadow.isReallyPlaying)
    }

    @Test
    fun `pause cancela el arranque con foco diferido aunque llegue gain`() {
        fixture.audioShadow.setNextFocusRequestResponse(AudioManager.AUDIOFOCUS_REQUEST_DELAYED)
        fixture.manager.playSong(fixture.song())
        fixture.finishPreparation()

        fixture.command("pause")
        fixture.changeFocus(AudioManager.AUDIOFOCUS_GAIN)

        assertFalse(fixture.manager.isPlaying.value)
        assertFalse(fixture.mediaShadow.isReallyPlaying)
    }

    @Test
    fun `la perdida transitoria pausa y gain reanuda la pista interrumpida`() {
        fixture.startPlaying()
        fixture.changeFocus(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)
        assertFalse(fixture.manager.isPlaying.value)

        fixture.changeFocus(AudioManager.AUDIOFOCUS_GAIN)

        assertTrue(fixture.manager.isPlaying.value)
        assertTrue(fixture.mediaShadow.isReallyPlaying)
    }

    @Test
    fun `una pausa del usuario durante perdida transitoria impide reanudar con gain`() {
        fixture.startPlaying()
        fixture.changeFocus(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)

        fixture.command("pause")
        fixture.changeFocus(AudioManager.AUDIOFOCUS_GAIN)

        assertFalse(fixture.manager.isPlaying.value)
        assertFalse(fixture.mediaShadow.isReallyPlaying)
    }

    @Test
    fun `stop durante perdida transitoria impide reanudar con gain`() {
        fixture.startPlaying()
        fixture.changeFocus(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)

        fixture.manager.stop()
        fixture.changeFocus(AudioManager.AUDIOFOCUS_GAIN)

        assertFalse(fixture.manager.isPlaying.value)
        assertFalse(fixture.mediaShadow.isReallyPlaying)
    }

    @Test
    fun `una pista pausada por el usuario no reanuda tras perdida transitoria y gain`() {
        fixture.startPlaying()
        fixture.command("pause")

        fixture.changeFocus(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)
        fixture.changeFocus(AudioManager.AUDIOFOCUS_GAIN)

        assertFalse(fixture.manager.isPlaying.value)
        assertFalse(fixture.mediaShadow.isReallyPlaying)
    }

    @Test
    fun `la perdida permanente de foco cancela la reanudacion automatica`() {
        fixture.startPlaying()

        fixture.changeFocus(AudioManager.AUDIOFOCUS_LOSS)
        fixture.changeFocus(AudioManager.AUDIOFOCUS_GAIN)

        assertFalse(fixture.manager.isPlaying.value)
        assertFalse(fixture.mediaShadow.isReallyPlaying)
    }

    @Test
    fun `desconectar auriculares pausa y gain no vuelve a reproducir`() {
        fixture.startPlaying()

        fixture.disconnectHeadphones()
        assertFalse(fixture.manager.isPlaying.value)
        fixture.changeFocus(AudioManager.AUDIOFOCUS_GAIN)

        assertFalse(fixture.manager.isPlaying.value)
        assertFalse(fixture.mediaShadow.isReallyPlaying)
    }

    @Test
    fun `desconectar auriculares durante prepareAsync cancela el arranque pendiente`() {
        fixture.manager.playSong(fixture.song())

        fixture.disconnectHeadphones()
        fixture.finishPreparation()

        assertFalse(fixture.manager.isPlaying.value)
        assertFalse(fixture.mediaShadow.isReallyPlaying)
    }

    @Test
    fun `desconectar auriculares con foco diferido cancela el arranque pendiente`() {
        fixture.audioShadow.setNextFocusRequestResponse(AudioManager.AUDIOFOCUS_REQUEST_DELAYED)
        fixture.manager.playSong(fixture.song())
        fixture.finishPreparation()

        fixture.disconnectHeadphones()
        fixture.changeFocus(AudioManager.AUDIOFOCUS_GAIN)

        assertFalse(fixture.manager.isPlaying.value)
        assertFalse(fixture.mediaShadow.isReallyPlaying)
    }

    @Test
    fun `desconectar auriculares durante perdida transitoria cancela la reanudacion`() {
        fixture.startPlaying()
        fixture.changeFocus(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)

        fixture.disconnectHeadphones()
        fixture.changeFocus(AudioManager.AUDIOFOCUS_GAIN)

        assertFalse(fixture.manager.isPlaying.value)
        assertFalse(fixture.mediaShadow.isReallyPlaying)
    }

    @Test
    fun `shuffle ALL limpia el historial al empezar cada ciclo`() {
        val queue = (0..3).map { fixture.song("song-$it") }
        fixture.manager.playSong(queue.first(), queue)
        fixture.manager.toggleShuffle()
        assertEquals(RepeatMode.ALL, fixture.manager.repeatMode.value)
        val firstCycle = mutableSetOf(fixture.manager.currentIndex.value)
        repeat(queue.size - 1) {
            fixture.manager.next()
            assertTrue(firstCycle.add(fixture.manager.currentIndex.value))
        }
        assertEquals(queue.indices.toSet(), firstCycle)

        repeat(2) {
            val previousIndex = fixture.manager.currentIndex.value
            fixture.manager.next()
            assertTrue(previousIndex != fixture.manager.currentIndex.value)
            // Sin inspeccionar el límite de ciclo, el Random.Default haría esta regresión probabilística.
            val history = ReflectionHelpers.getField<Set<Int>>(
                fixture.manager, "shuffledPlayedIndices"
            )
            assertEquals(setOf(fixture.manager.currentIndex.value), history.toSet())
            val cycle = mutableSetOf(fixture.manager.currentIndex.value)
            repeat(queue.size - 1) {
                fixture.manager.next()
                assertTrue("No debe repetir una pista dentro del nuevo ciclo", cycle.add(fixture.manager.currentIndex.value))
            }
            assertEquals(queue.indices.toSet(), cycle)
        }
    }

    @Test
    fun `onPrepared tardio de la pista anterior no arranca ni altera la nueva`() {
        val first = fixture.song("first")
        val second = fixture.song("second")
        fixture.manager.playSong(first)
        val oldPlayer = fixture.mediaPlayer
        val prepared = ReflectionHelpers.getField<MediaPlayer.OnPreparedListener>(
            fixture.mediaShadow, "preparedListener"
        )

        fixture.manager.playSong(second)
        prepared.onPrepared(oldPlayer)

        assertFalse(fixture.manager.isPlaying.value)
        assertEquals(second, fixture.manager.currentSong.value)
        assertNull(fixture.audioShadow.lastAudioFocusRequest)
        fixture.finishPreparation()
        assertTrue(fixture.manager.isPlaying.value)
        assertEquals(second, fixture.manager.currentSong.value)
    }

    @Test
    fun `stop y nueva pista invalidan tambien el onPrepared anterior`() {
        fixture.manager.playSong(fixture.song("old"))
        val oldPlayer = fixture.mediaPlayer
        val prepared = ReflectionHelpers.getField<MediaPlayer.OnPreparedListener>(
            fixture.mediaShadow, "preparedListener"
        )
        fixture.manager.stop()
        fixture.manager.playSong(fixture.song("new"))
        fixture.manager.pause()

        prepared.onPrepared(oldPlayer)
        fixture.finishPreparation()

        assertFalse(fixture.manager.isPlaying.value)
        assertFalse(fixture.mediaShadow.isReallyPlaying)
        assertEquals("new", fixture.manager.currentSong.value?.id)
    }

    @Test
    fun `onError seguido de onPrepared tardio no reproduce tras pausa`() {
        fixture.manager.playSong(fixture.song())
        val player = fixture.mediaPlayer
        val prepared = ReflectionHelpers.getField<MediaPlayer.OnPreparedListener>(
            fixture.mediaShadow, "preparedListener"
        )
        val error = ReflectionHelpers.getField<MediaPlayer.OnErrorListener>(
            fixture.mediaShadow, "errorListener"
        )
        fixture.command("pause")

        error.onError(player, MediaPlayer.MEDIA_ERROR_UNKNOWN, 0)
        prepared.onPrepared(player)
        fixture.finishPreparation()

        assertFalse(fixture.manager.isPlaying.value)
        assertFalse(fixture.mediaShadow.isReallyPlaying)
    }

    @Test
    fun `el error de un player anterior no pausa la pista nueva`() {
        fixture.startPlaying()
        val oldPlayer = fixture.mediaPlayer
        val error = ReflectionHelpers.getField<MediaPlayer.OnErrorListener>(
            fixture.mediaShadow, "errorListener"
        )
        fixture.manager.playSong(fixture.song("new"))
        fixture.finishPreparation()

        error.onError(oldPlayer, MediaPlayer.MEDIA_ERROR_UNKNOWN, 0)

        assertTrue(fixture.manager.isPlaying.value)
        assertTrue(fixture.mediaShadow.isReallyPlaying)
        assertEquals("new", fixture.manager.currentSong.value?.id)
    }

    @Test
    fun `gain de una solicitud cancelada no concede el foco pendiente nuevo`() {
        fixture.audioShadow.setNextFocusRequestResponse(AudioManager.AUDIOFOCUS_REQUEST_DELAYED)
        fixture.manager.playSong(fixture.song("old"))
        fixture.finishPreparation()
        val oldListener = fixture.audioShadow.lastAudioFocusRequest.listener
        fixture.manager.stop()
        fixture.manager.playSong(fixture.song("new"))
        fixture.finishPreparation()

        oldListener.onAudioFocusChange(AudioManager.AUDIOFOCUS_GAIN)
        fixture.runCurrent()

        assertFalse(fixture.manager.isPlaying.value)
        fixture.changeFocus(AudioManager.AUDIOFOCUS_GAIN)
        assertTrue(fixture.manager.isPlaying.value)
    }

    @Test
    fun `updateQueuedSong conserva player posicion indice e historial y reemplaza duplicados`() {
        val original = fixture.song("old-id")
        val other = fixture.song("other")
        fixture.manager.playSong(original, listOf(other, original, original))
        fixture.manager.toggleShuffle()
        fixture.finishPreparation()
        fixture.manager.seekTo(5_000L)
        fixture.runCurrent()
        val player = fixture.mediaPlayer
        val index = fixture.manager.currentIndex.value
        val history = ReflectionHelpers.getField<Set<Int>>(
            fixture.manager, "shuffledPlayedIndices"
        ).toSet()
        val updated = original.copy(
            id = "canonical-id", title = "Updated", uriString = "content://retained-tree/song"
        )

        AudioPlayerManager::class.java.getMethod(
            "updateQueuedSong", String::class.java, Song::class.java
        ).invoke(fixture.manager, original.id, updated)

        assertEquals(listOf(other, updated, updated), fixture.manager.queue.value)
        assertEquals(updated, fixture.manager.currentSong.value)
        assertSame(player, fixture.mediaPlayer)
        assertEquals(Uri.parse(original.uriString), fixture.mediaShadow.sourceUri)
        assertTrue(fixture.manager.isPlaying.value)
        assertEquals(5_000L, fixture.manager.currentPositionMs.value)
        assertEquals(5_000, player.currentPosition)
        assertEquals(index, fixture.manager.currentIndex.value)
        assertEquals(history, ReflectionHelpers.getField<Set<Int>>(fixture.manager, "shuffledPlayedIndices"))
    }

    @Test
    @Config(sdk = [35])
    fun `API35 solicita servicio al preparar y espera foreground antes de pedir foco`() {
        fixture.installSingleton()
        fixture.manager.playSong(fixture.song())
        val intent = shadowOf(fixture.context as Application).nextStartedService
        assertNotNull("Se debe arrancar el servicio antes de terminar prepareAsync", intent)
        assertEquals(MusicPlaybackService.ACTION_SYNC, intent.action)
        fixture.finishPreparation()
        assertNull("Sin FGS activo no se debe pedir foco en API35", fixture.audioShadow.lastAudioFocusRequest)
        assertFalse(fixture.manager.isPlaying.value)

        val service = Robolectric.buildService(MusicPlaybackService::class.java).create()
        try {
            fixture.runCurrent()
            assertNotNull(shadowOf(service.get()).lastForegroundNotification)
            assertNotNull(fixture.audioShadow.lastAudioFocusRequest)
            assertTrue(fixture.manager.isPlaying.value)
        } finally {
            service.destroy()
        }
    }

    @Test
    @Config(sdk = [35])
    fun `stop antes de que el FGS este listo cancela el arranque en API35`() {
        fixture.installSingleton()
        fixture.manager.playSong(fixture.song())
        fixture.finishPreparation()
        fixture.manager.stop()

        val service = Robolectric.buildService(MusicPlaybackService::class.java).create()
        try {
            fixture.runCurrent()
            assertFalse(fixture.manager.isPlaying.value)
            assertNull(fixture.audioShadow.lastAudioFocusRequest)
        } finally {
            service.destroy()
        }
    }
}
