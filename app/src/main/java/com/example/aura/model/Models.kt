package com.example.aura.model

/**
 * ARCHIVO: Models.kt
 * FUNCIÓN: Modelos de dominio de la aplicación (Song, Playlist, Artist, LyricLine).
 *          Se usan en toda la UI y se convierten a/desde entidades Room en data/local/Entities.kt.
 * NAVEGACIÓN: UI -> AuraViewModel -> Models; persistencia -> data/local/Entities.kt.
 */

import android.net.Uri

/**
 * Song: pista de audio de la biblioteca local.
 * Incluye metadatos de calidad (formato, sampleRate, bitDepth) y datos de presentación.
 */
data class Song(
    val id: String,
    val title: String,
    val artist: String,
    val album: String = "Álbum Desconocido",
    val durationMs: Long = 0L,
    val uriString: String = "",
    val filePath: String? = null,
    val format: String = "Desconocido",
    val sampleRate: Int = 0,
    val bitDepth: Int = 0,
    val bitrateKbps: Int = 0,
    val isLossless: Boolean = false,
    val isFavorite: Boolean = false,
    val lyrics: String = "",
    val coverColorHex: Long = 0xFF8A5CF6,
    val dateAdded: Long = System.currentTimeMillis(),
    val sourceModifiedAt: Long = 0,
    val fileSizeBytes: Long = 0
) {
    // Duración lista para mostrar en formato m:ss.
    val durationFormatted: String
        get() {
            val totalSeconds = (durationMs / 1000).coerceAtLeast(0)
            val minutes = totalSeconds / 60
            val seconds = totalSeconds % 60
            return "%d:%02d".format(minutes, seconds)
        }

    // Etiqueta de calidad mostrada en el reproductor (Lossless / Hi-Res / bitrate).
    val qualityBadge: String
        get() {
            if (!isLossless) {
                return if (bitrateKbps > 0) "$format • $bitrateKbps kbps" else format
            }
            val quality = if (sampleRate >= 88200 || bitDepth >= 24) "HI-RES LOSSLESS" else "LOSSLESS"
            val specs = listOfNotNull(
                bitDepth.takeIf { it > 0 }?.let { "${it}b" },
                sampleRate.takeIf { it > 0 }?.let {
                    if (it >= 1000) "${java.math.BigDecimal.valueOf(it.toLong(), 3).stripTrailingZeros().toPlainString()}kHz"
                    else "${it}Hz"
                }
            ).joinToString("/")
            return "$quality • $format" + if (specs.isEmpty()) "" else " $specs"
        }
}

/**
 * Playlist: lista de reproducción (personalizada o del sistema: favoritas / lossless).
 * songIds contiene las pistas asociadas; la UI resuelve los objetos Song desde el ViewModel.
 */
data class Playlist(
    val id: String,
    val name: String,
    val description: String = "",
    val coverColorHex: Long = 0xFF9155FD,
    val songIds: List<String> = emptyList(),
    val isCustom: Boolean = true,
    val createdAt: Long = System.currentTimeMillis()
)

/**
 * Artist: artista derivado de las pistas locales (no se persiste).
 * Se construye en AuraViewModel.openArtist() para la pantalla de detalle.
 */
data class Artist(
    val id: String,
    val name: String,
    val bio: String = "",
    val monthlyListeners: String = "1.2M oyentes",
    val isFollowing: Boolean = false,
    val popularSongIds: List<String> = emptyList()
)

/**
 * LyricLine: una línea de letra con su marca de tiempo (ms) para el resaltado sincronizado.
 */
data class LyricLine(
    val timeMs: Long,
    val text: String
)
