package com.example.aura.service

import android.app.Notification
import android.content.Intent
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import com.example.aura.player.PlayerTestFixture
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertSame
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ServiceController
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowMediaSession
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@LooperMode(LooperMode.Mode.PAUSED)
class MusicPlaybackServiceTest {
    private lateinit var fixture: PlayerTestFixture
    private lateinit var controller: ServiceController<MusicPlaybackService>
    private lateinit var session: MediaSessionCompat
    private lateinit var callback: MediaSessionCompat.Callback

    @Before
    fun setUp() {
        fixture = PlayerTestFixture()
        fixture.installSingleton()
        controller = Robolectric.buildService(MusicPlaybackService::class.java)
        controller.create()
        session = ReflectionHelpers.getField(controller.get(), "mediaSession")
        val implementation = ReflectionHelpers.getField<Any>(session, "mImpl")
        // Robolectric no despacha los transport controls nativos; probamos el callback registrado.
        callback = ReflectionHelpers.getField(implementation, "mCallback")
        fixture.runCurrent()
    }

    @After
    fun tearDown() {
        try {
            controller.destroy()
        } finally {
            fixture.close()
        }
    }

    @Test
    fun `onPlay repetido mantiene la reproduccion activa`() {
        fixture.startPlaying()
        callback.onPause()
        assertFalse(fixture.manager.isPlaying.value)

        callback.onPlay()
        fixture.runCurrent()
        assertTrue(fixture.manager.isPlaying.value)
        callback.onPlay()
        fixture.runCurrent()

        assertTrue(fixture.manager.isPlaying.value)
        assertTrue(fixture.mediaShadow.isReallyPlaying)
    }

    @Test
    fun `onPause repetido mantiene la reproduccion pausada`() {
        fixture.startPlaying()

        callback.onPause()
        fixture.runCurrent()
        assertFalse(fixture.manager.isPlaying.value)
        callback.onPause()
        fixture.runCurrent()

        assertFalse(fixture.manager.isPlaying.value)
        assertFalse(fixture.mediaShadow.isReallyPlaying)
    }

    @Test
    fun `onStop durante prepareAsync cancela el arranque pendiente`() {
        fixture.manager.playSong(fixture.song())

        callback.onStop()
        fixture.finishPreparation()

        assertFalse(fixture.manager.isPlaying.value)
        assertFalse(fixture.mediaShadow.isReallyPlaying)
        assertTrue(shadowOf(controller.get()).isStoppedBySelf)
    }

    @Test
    fun `onStop con foco diferido impide arrancar tras gain`() {
        fixture.audioShadow.setNextFocusRequestResponse(AudioManager.AUDIOFOCUS_REQUEST_DELAYED)
        fixture.manager.playSong(fixture.song())
        fixture.finishPreparation()

        callback.onStop()
        fixture.changeFocus(AudioManager.AUDIOFOCUS_GAIN)

        assertFalse(fixture.manager.isPlaying.value)
        assertFalse(fixture.mediaShadow.isReallyPlaying)
        assertTrue(shadowOf(controller.get()).isStoppedBySelf)
    }

    @Test
    fun `ACTION_STOP durante prepareAsync cancela el arranque pendiente`() {
        fixture.manager.playSong(fixture.song())

        controller.get().onStartCommand(
            Intent(fixture.context, MusicPlaybackService::class.java)
                .setAction(MusicPlaybackService.ACTION_STOP),
            0,
            1
        )
        fixture.finishPreparation()

        assertFalse(fixture.manager.isPlaying.value)
        assertFalse(fixture.mediaShadow.isReallyPlaying)
        assertTrue(shadowOf(controller.get()).isStoppedBySelf)
    }

    @Test
    fun `la sesion anuncia ACTION_SEEK_TO`() {
        fixture.startPlaying()

        val state = requireNotNull(session.controller.playbackState)

        assertTrue(state.actions and PlaybackStateCompat.ACTION_SEEK_TO != 0L)
    }

    @Test
    fun `onSeekTo mueve la pista y publica la nueva posicion`() {
        fixture.startPlaying()

        callback.onSeekTo(12_000L)
        fixture.runCurrent()

        assertEquals(12_000L, fixture.manager.currentPositionMs.value)
        assertEquals(12_000, fixture.mediaPlayer.currentPosition)
        assertEquals(12_000L, requireNotNull(session.controller.playbackState).position)
    }

    @Test
    fun `un seek local actualiza PlaybackState sin cambiar pista ni estado play`() {
        fixture.startPlaying()
        val song = fixture.manager.currentSong.value

        fixture.manager.seekTo(15_000L)
        fixture.runCurrent()

        val state = requireNotNull(session.controller.playbackState)
        assertEquals(song, fixture.manager.currentSong.value)
        assertTrue(fixture.manager.isPlaying.value)
        assertEquals(PlaybackStateCompat.STATE_PLAYING, state.state)
        assertEquals(15_000L, state.position)
    }

    @Test
    fun `un seek en pausa actualiza PlaybackState sin reanudar`() {
        fixture.startPlaying()
        callback.onPause()
        fixture.runCurrent()
        assertFalse(fixture.manager.isPlaying.value)

        fixture.manager.seekTo(20_000L)
        fixture.runCurrent()

        val state = requireNotNull(session.controller.playbackState)
        assertEquals(PlaybackStateCompat.STATE_PAUSED, state.state)
        assertEquals(20_000L, state.position)
        assertFalse(fixture.manager.isPlaying.value)
    }

    @Test
    fun `PlaybackState pausado publica velocidad cero`() {
        fixture.startPlaying()

        callback.onPause()
        fixture.runCurrent()

        val state = requireNotNull(session.controller.playbackState)
        assertEquals(PlaybackStateCompat.STATE_PAUSED, state.state)
        assertEquals(0.0f, state.playbackSpeed, 0.0f)
    }

    @Test
    fun `la notificacion usa qualityBadge incluso con metadatos desconocidos`() {
        val song = fixture.song().copy(sampleRate = 0, bitDepth = 0, bitrateKbps = 0)

        fixture.manager.playSong(song)
        fixture.finishPreparation()

        val notification = requireNotNull(shadowOf(controller.get()).lastForegroundNotification)
        assertEquals(song.qualityBadge, notification.extras.getCharSequence(Notification.EXTRA_SUB_TEXT).toString())
    }

    @Test
    fun `el progreso sincroniza PlaybackState sin reconstruir notificacion`() {
        fixture.startPlaying()
        val notification = shadowOf(controller.get()).lastForegroundNotification

        fixture.advanceProgressTick()
        fixture.advanceProgressTick()

        assertTrue(fixture.manager.currentPositionMs.value > 0)
        assertEquals(
            fixture.manager.currentPositionMs.value,
            requireNotNull(session.controller.playbackState).position
        )
        assertSame(notification, shadowOf(controller.get()).lastForegroundNotification)
    }

    @Test
    @Config(shadows = [MetadataRecordingSessionShadow::class])
    fun `la sesion sincroniza metadata y duracion preparada al actualizar la pista`() {
        val original = fixture.song().copy(durationMs = 30_000L)
        fixture.manager.playSong(original)
        fixture.finishPreparation()
        val sessionShadow = Shadow.extract<MetadataRecordingSessionShadow>(session.mediaSession)
        val metadata = requireNotNull(sessionShadow.recordedMetadata)
        assertEquals(original.id, metadata.getString(MediaMetadataCompat.METADATA_KEY_MEDIA_ID))
        assertEquals(original.title, metadata.getString(MediaMetadataCompat.METADATA_KEY_TITLE))
        assertEquals(original.artist, metadata.getString(MediaMetadataCompat.METADATA_KEY_ARTIST))
        assertEquals(original.album, metadata.getString(MediaMetadataCompat.METADATA_KEY_ALBUM))
        assertEquals(60_000L, metadata.getLong(MediaMetadataCompat.METADATA_KEY_DURATION))
        val updated = original.copy(id = "new-id", title = "Updated")

        fixture.manager.updateQueuedSong(original.id, updated)
        fixture.runCurrent()

        val refreshed = requireNotNull(sessionShadow.recordedMetadata)
        assertEquals(updated.id, refreshed.getString(MediaMetadataCompat.METADATA_KEY_MEDIA_ID))
        assertEquals(updated.title, refreshed.getString(MediaMetadataCompat.METADATA_KEY_TITLE))
        assertEquals(60_000L, refreshed.getLong(MediaMetadataCompat.METADATA_KEY_DURATION))
    }

    @Implements(MediaSession::class)
    class MetadataRecordingSessionShadow : ShadowMediaSession() {
        var recordedMetadata: MediaMetadata? = null
            private set

        @Implementation
        protected fun setMetadata(metadata: MediaMetadata?) {
            recordedMetadata = metadata
        }
    }
}
