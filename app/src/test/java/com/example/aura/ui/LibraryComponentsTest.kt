package com.example.aura.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.example.aura.model.Playlist
import com.example.aura.model.Song
import com.example.aura.ui.screens.LibraryScreen
import com.example.aura.ui.screens.QueueScreen
import com.example.ui.theme.MyApplicationTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LibraryComponentsTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `shuffle queue can remove an earlier track using its actual index`() {
        val songs = listOf(
            Song("a", "Primera", "Artista"),
            Song("b", "Segunda", "Artista"),
            Song("c", "Actual", "Artista")
        )
        var removed = -1
        compose.setContent {
            MyApplicationTheme {
                QueueScreen(
                    currentSong = songs.last(), queue = songs, currentIndex = 2, isShuffle = true,
                    onBack = {}, onSongClick = {}, onRemoveFromQueue = { removed = it },
                    onBackToLibrary = {}
                )
            }
        }
        compose.onNodeWithText("Primera").assertIsDisplayed()
        compose.onNodeWithContentDescription("Quitar Primera de la cola").performClick()
        assertEquals(0, removed)
    }

    @Test
    fun `add to playlist menu only offers editable lists`() {
        compose.setContent {
            MyApplicationTheme {
                LibraryScreen(
                    songs = listOf(Song("a", "Pista", "Artista")),
                    playlists = listOf(
                        Playlist("playlist_favorites", "Mis Favoritas"),
                        Playlist("playlist_lossless", "Lossless"),
                        Playlist("custom", "Personal")
                    ),
                    onSongClick = {}, onPlaylistClick = {}, onToggleFavorite = {},
                    onAddSongToPlaylist = { _, _ -> }, onCreatePlaylistClick = {}, onOpenFileExplorer = {}
                )
            }
        }
        compose.onNodeWithContentDescription("Opciones de Pista").performClick()
        compose.onNodeWithText("Añadir a \"Personal\"").assertIsDisplayed()
        compose.onNodeWithText("Añadir a \"Mis Favoritas\"").assertDoesNotExist()
        compose.onNodeWithText("Añadir a \"Lossless\"").assertDoesNotExist()
    }
}
