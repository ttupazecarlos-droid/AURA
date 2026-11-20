package com.example.aura.data.local

/**
 * ARCHIVO: Daos.kt
 * FUNCIÓN: Interfaces DAO de Room con las consultas de canciones y listas de reproducción.
 *          SongDao -> tabla songs; PlaylistDao -> tablas playlists / playlist_songs.
 * NAVEGACIÓN: AuraDatabase crea los DAO y AuraViewModel los consume en sus corrutinas.
 */

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/**
 * SongDao: acceso a la tabla "songs".
 * Expone Flow para observación reactiva e inserciones/borrados suspensos.
 */
@Dao
interface SongDao {
    // Todas las canciones, las más recientes primero.
    @Query("SELECT * FROM songs ORDER BY dateAdded DESC")
    fun getAllSongs(): Flow<List<SongEntity>>

    @Query("SELECT * FROM songs ORDER BY dateAdded DESC")
    suspend fun getAllSongsOnce(): List<SongEntity>

    // Solo las canciones marcadas como favoritas, orden alfabético.
    @Query("SELECT * FROM songs WHERE isFavorite = 1 ORDER BY title ASC")
    fun getFavoriteSongs(): Flow<List<SongEntity>>

    // Busca una canción concreta por su id.
    @Query("SELECT * FROM songs WHERE id = :id LIMIT 1")
    suspend fun getSongById(id: String): SongEntity?

    // IGNORE: re-escanear o reimportar no debe borrar isFavorite ni dateAdded de una pista existente.
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertSongs(songs: List<SongEntity>)

    // IGNORE: igual para una sola canción.
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertSong(song: SongEntity)

    // Upsert actualiza sin el DELETE implícito de REPLACE y su cascada.
    @Upsert
    suspend fun updateSong(song: SongEntity)

    // Activa/desactiva el favorito de una canción.
    @Query("UPDATE songs SET isFavorite = :isFavorite WHERE id = :songId")
    suspend fun setFavorite(songId: String, isFavorite: Boolean)

    @Query("UPDATE songs SET isFavorite = NOT isFavorite WHERE id = :songId")
    suspend fun toggleFavorite(songId: String)

    // Elimina una canción por id.
    @Query("DELETE FROM songs WHERE id = :songId")
    suspend fun deleteSong(songId: String)

    // Canciones que pertenecen a una carpeta SAF (el URI del documento empieza por el tree URI).
    @Query("SELECT * FROM songs WHERE substr(uriString, 1, length(:prefix)) = :prefix")
    suspend fun getSongsByUriPrefix(prefix: String): List<SongEntity>

    // Borra varias canciones por id (archivos eliminados de una carpeta).
    @Transaction
    suspend fun deleteSongsByIds(songIds: List<String>) {
        songIds.chunked(900).forEach { deleteSongsByIdsBatch(it) }
    }

    @Query("DELETE FROM songs WHERE id IN (:songIds)")
    suspend fun deleteSongsByIdsBatch(songIds: List<String>)

    // Elimina las detecciones antiguas de MediaStore (escaneo global de todo el celular).
    @Query("DELETE FROM songs WHERE id GLOB 'media_*'")
    suspend fun deleteMediaStoreSongs()

    // Elimina audios cortos (menos de 1 minuto / 60,000 ms), notas de voz de WhatsApp y tonos.
    @Query("DELETE FROM songs WHERE durationMs < :minDurationMs OR title LIKE 'PTT-%' OR title LIKE 'AUD-%' OR filePath LIKE '%WhatsApp%' OR filePath LIKE '%Ringtones%' OR filePath LIKE '%Notifications%'")
    suspend fun deleteShortAudioAndVoiceNotes(minDurationMs: Long = 60000L)

    // Vacía la tabla de canciones (reinicio de biblioteca).
    @Query("DELETE FROM songs")
    suspend fun clearAll()
}

/**
 * PlaylistDao: acceso a listas de reproducción y su relación con canciones.
 */
@Dao
interface PlaylistDao {
    // Todas las listas, las más recientes primero.
    @Query("SELECT * FROM playlists ORDER BY createdAt DESC")
    fun getAllPlaylists(): Flow<List<PlaylistEntity>>

    // Busca una lista concreta por su id.
    @Query("SELECT * FROM playlists WHERE id = :id LIMIT 1")
    suspend fun getPlaylistById(id: String): PlaylistEntity?

    // Todas las relaciones lista-canción (se agrupan en el ViewModel).
    @Query("SELECT * FROM playlist_songs")
    fun getAllPlaylistSongs(): Flow<List<PlaylistSongEntity>>

    // Crea o actualiza una lista.
    @Upsert
    suspend fun insertPlaylist(playlist: PlaylistEntity)

    // Elimina la lista y sus relaciones por cascada.
    @Query("DELETE FROM playlists WHERE id = :playlistId")
    suspend fun deletePlaylist(playlistId: String)

    // Ids de canciones de una lista, respetando sortOrder.
    @Query("SELECT songId FROM playlist_songs WHERE playlistId = :playlistId ORDER BY sortOrder ASC")
    fun getSongIdsForPlaylist(playlistId: String): Flow<List<String>>

    // Añade o actualiza una canción dentro de una lista.
    @Upsert
    suspend fun insertPlaylistSong(item: PlaylistSongEntity)

    @Query("SELECT MAX(sortOrder) FROM playlist_songs WHERE playlistId = :playlistId")
    suspend fun getMaxSortOrder(playlistId: String): Int?

    @Transaction
    suspend fun addSongToPlaylist(playlistId: String, songId: String) {
        val nextOrder = Math.addExact(getMaxSortOrder(playlistId) ?: -1, 1)
        insertPlaylistSong(PlaylistSongEntity(playlistId, songId, nextOrder))
    }

    // Si ya existe la pista canónica en la lista, conserva su orden manual.
    @Query("""
        INSERT OR IGNORE INTO playlist_songs (playlistId, songId, sortOrder)
        SELECT playlistId, :canonicalId, sortOrder FROM playlist_songs WHERE songId = :aliasId
    """)
    suspend fun copySongToPlaylists(aliasId: String, canonicalId: String)

    // Quita una canción concreta de una lista.
    @Query("DELETE FROM playlist_songs WHERE playlistId = :playlistId AND songId = :songId")
    suspend fun removeSongFromPlaylist(playlistId: String, songId: String)

    // Vacía todas las canciones de una lista.
    @Query("DELETE FROM playlist_songs WHERE playlistId = :playlistId")
    suspend fun clearPlaylistSongs(playlistId: String)

    // Limpia relaciones huérfanas cuando se eliminan canciones de una carpeta.
    @Transaction
    suspend fun deletePlaylistSongsForSongIds(songIds: List<String>) {
        songIds.chunked(900).forEach { deletePlaylistSongsForSongIdsBatch(it) }
    }

    @Query("DELETE FROM playlist_songs WHERE songId IN (:songIds)")
    suspend fun deletePlaylistSongsForSongIdsBatch(songIds: List<String>)
}
