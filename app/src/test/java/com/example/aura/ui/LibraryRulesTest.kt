package com.example.aura.ui

import com.example.aura.model.Song
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryRulesTest {
    @Test
    fun `artist selection does not match another artist title or album`() {
        val muse = Song(id = "muse", title = "Uprising", artist = "Muse")
        val tribute = Song(id = "other", title = "Muse tribute", artist = "Another Band", album = "Muse")
        val cover = Song(id = "cover", title = "Cover", artist = "Muse Tribute Band")
        val songs = listOf(muse, tribute, cover)
        assertEquals(listOf(muse), filterLibrarySongs(songs, "", "muse"))
        assertEquals(listOf(tribute, cover), filterLibrarySongs(songs, "tribute", null))
    }

    @Test
    fun `system playlists are automatic but custom lists are editable`() {
        assertTrue(isAutomaticPlaylist("playlist_favorites"))
        assertTrue(isAutomaticPlaylist("playlist_lossless"))
        assertFalse(isAutomaticPlaylist("playlist_custom"))
    }

    @Test
    fun `lrc expands every timestamp and sorts verses chronologically`() {
        val lines = parseLrcLyrics("[00:20.00][00:10.50]Coro\n[00:05]Intro")
        assertEquals(listOf(5_000L, 10_500L, 20_000L), lines.map { it.timeMs })
        assertEquals(listOf("Intro", "Coro", "Coro"), lines.map { it.text })
    }

    @Test
    fun `lrc ignores metadata and empty verses`() {
        val lines = parseLrcLyrics("[ar:Artista]\n[ti:Título]\n[00:01.123]Hola\n[00:02.00]")
        assertEquals(listOf(1_123L), lines.map { it.timeMs })
        assertEquals(listOf("Hola"), lines.map { it.text })
        assertTrue(parseLrcLyrics("").isEmpty())
    }
}
