package com.example.aura.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SongMetadataTest {
    @Test
    fun `new songs do not invent audio quality or source fingerprints`() {
        val song = Song(id = "unknown", title = "Unknown", artist = "Unknown")

        assertEquals(0, song.sampleRate)
        assertEquals(0, song.bitDepth)
        assertEquals(0, song.bitrateKbps)
        assertFalse(song.isLossless)
        assertFalse(song.format.uppercase() in setOf("FLAC", "MP3", "WAV", "ALAC", "AAC"))
        assertEquals(0L, song.sourceModifiedAt)
        assertEquals(0L, song.fileSizeBytes)
    }

    @Test
    fun `unknown lossless specs are omitted rather than shown as zero quality`() {
        val song = Song(
            id = "flac", title = "Unknown specs", artist = "Artist", format = "FLAC",
            isLossless = true, sampleRate = 0, bitDepth = 0, bitrateKbps = 0
        )

        assertTrue(song.qualityBadge.contains("LOSSLESS"))
        assertTrue(song.qualityBadge.contains("FLAC"))
        assertFalse(song.qualityBadge.contains("HI-RES"))
        assertFalse(song.qualityBadge.contains("0b"))
        assertFalse(song.qualityBadge.contains("0kHz"))
    }

    @Test
    fun `unknown lossy bitrate is not displayed as zero kbps`() {
        val song = Song(
            id = "mp3", title = "Unknown bitrate", artist = "Artist", format = "MP3",
            isLossless = false, bitrateKbps = 0
        )

        assertTrue(song.qualityBadge.contains("MP3"))
        assertFalse(song.qualityBadge.contains("0 kbps"))
        assertFalse(song.qualityBadge.contains("LOSSLESS"))
    }

    @Test
    fun `partially known specs never fill the missing component with zero`() {
        val song = Song(
            id = "partial", title = "Partial specs", artist = "Artist", format = "FLAC",
            isLossless = true, sampleRate = 96_000, bitDepth = 0
        )

        assertFalse(song.qualityBadge.contains("0b"))
        assertFalse(song.copy(sampleRate = 0, bitDepth = 24).qualityBadge.contains("0kHz"))
    }

    @Test
    fun `known quality badges preserve fractional sample rates`() {
        val hiRes = Song(
            id = "hires", title = "Known", artist = "Artist", format = "FLAC",
            isLossless = true, sampleRate = 96_000, bitDepth = 24
        )
        val cd = hiRes.copy(sampleRate = 44_100, bitDepth = 16)
        val mp3 = hiRes.copy(format = "MP3", isLossless = false, bitrateKbps = 320)

        assertEquals("HI-RES LOSSLESS • FLAC 24b/96kHz", hiRes.qualityBadge)
        assertEquals("LOSSLESS • FLAC 16b/44.1kHz", cd.qualityBadge)
        assertEquals("MP3 • 320 kbps", mp3.qualityBadge)
    }
}
