package com.example.aura.data.local

/**
 * ARCHIVO: Entities.kt
 * FUNCIÓN: Entidades de Room que representan la base de datos local (canciones, listas y su relación).
 *          Incluye conversores manuales entre la entidad y el modelo de dominio Song.
 * NAVEGACIÓN: Daos.kt -> AuraDatabase.kt -> AuraViewModel (lee y convierte a modelos de /model).
 */

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.example.aura.model.Song

/**
 * SongEntity: tabla "songs". Contiene todos los metadatos de una pista persistida.
 * toDomain()/fromDomain() traducen entre Room y el modelo usado por la UI.
 */
@Entity(
    tableName = "songs",
    indices = [Index(value = ["dateAdded"]), Index(value = ["uriString"])]
)
data class SongEntity(
    @PrimaryKey val id: String,
    val title: String,
    val artist: String,
    val album: String,
    val durationMs: Long,
    val uriString: String,
    val filePath: String?,
    val format: String,
    val sampleRate: Int,
    val bitDepth: Int,
    val bitrateKbps: Int,
    val isLossless: Boolean,
    val isFavorite: Boolean,
    val lyrics: String,
    val coverColorHex: Long,
    val dateAdded: Long,
    @ColumnInfo(defaultValue = "0") val sourceModifiedAt: Long = 0,
    @ColumnInfo(defaultValue = "0") val fileSizeBytes: Long = 0
) {
    // Convierte la fila de Room en el modelo de dominio Song.
    fun toDomain(): Song = Song(
        id = id,
        title = title,
        artist = artist,
        album = album,
        durationMs = durationMs,
        uriString = uriString,
        filePath = filePath,
        format = format,
        sampleRate = sampleRate,
        bitDepth = bitDepth,
        bitrateKbps = bitrateKbps,
        isLossless = isLossless,
        isFavorite = isFavorite,
        lyrics = lyrics,
        coverColorHex = coverColorHex,
        dateAdded = dateAdded,
        sourceModifiedAt = sourceModifiedAt,
        fileSizeBytes = fileSizeBytes
    )

    companion object {
        // Convierte un Song de dominio en su entidad persistible.
        fun fromDomain(song: Song): SongEntity = SongEntity(
            id = song.id,
            title = song.title,
            artist = song.artist,
            album = song.album,
            durationMs = song.durationMs,
            uriString = song.uriString,
            filePath = song.filePath,
            format = song.format,
            sampleRate = song.sampleRate,
            bitDepth = song.bitDepth,
            bitrateKbps = song.bitrateKbps,
            isLossless = song.isLossless,
            isFavorite = song.isFavorite,
            lyrics = song.lyrics,
            coverColorHex = song.coverColorHex,
            dateAdded = song.dateAdded,
            sourceModifiedAt = song.sourceModifiedAt,
            fileSizeBytes = song.fileSizeBytes
        )
    }
}

/**
 * PlaylistEntity: tabla "playlists". Metadatos de cada lista de reproducción.
 */
@Entity(tableName = "playlists")
data class PlaylistEntity(
    @PrimaryKey val id: String,
    val name: String,
    val description: String,
    val coverColorHex: Long,
    val createdAt: Long
)

/**
 * PlaylistSongEntity: tabla intermedia "playlist_songs" (relación N:M lista-canción).
 * sortOrder conserva el orden manual de las pistas dentro de la lista.
 */
@Entity(
    tableName = "playlist_songs",
    primaryKeys = ["playlistId", "songId"],
    foreignKeys = [
        ForeignKey(
            entity = PlaylistEntity::class,
            parentColumns = ["id"],
            childColumns = ["playlistId"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = SongEntity::class,
            parentColumns = ["id"],
            childColumns = ["songId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index(value = ["songId"])]
)
data class PlaylistSongEntity(
    val playlistId: String,
    val songId: String,
    val sortOrder: Int
)
