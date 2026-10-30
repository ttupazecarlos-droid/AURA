package com.example.aura.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface SongDao {
    @Query("SELECT * FROM songs ORDER BY dateAdded DESC")
    fun getAllSongs(): Flow<List<SongEntity>>

    @Query("SELECT * FROM songs WHERE isFavorite = 1 ORDER BY title ASC")
    fun getFavoriteSongs(): Flow<List<SongEntity>>

    @Query("SELECT * FROM songs WHERE id = :id LIMIT 1")
    suspend fun getSongById(id: String): SongEntity?

    // IGNORE: re-escanear o reimportar no debe borrar isFavorite ni dateAdded de una pista existente.
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertSongs(songs: List<SongEntity>)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertSong(song: SongEntity)

    @Query("UPDATE songs SET isFavorite = :isFavorite WHERE id = :songId")
    suspend fun setFavorite(songId: String, isFavorite: Boolean)

    @Query("DELETE FROM songs WHERE id = :songId")
    suspend fun deleteSong(songId: String)

    @Query("DELETE FROM songs")
    suspend fun clearAll()
}

@Dao
interface PlaylistDao {
    @Query("SELECT * FROM playlists ORDER BY createdAt DESC")
    fun getAllPlaylists(): Flow<List<PlaylistEntity>>

    @Query("SELECT * FROM playlists WHERE id = :id LIMIT 1")
    suspend fun getPlaylistById(id: String): PlaylistEntity?

    @Query("SELECT * FROM playlist_songs")
    fun getAllPlaylistSongs(): Flow<List<PlaylistSongEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPlaylist(playlist: PlaylistEntity)

    @Query("DELETE FROM playlists WHERE id = :playlistId")
    suspend fun deletePlaylist(playlistId: String)

    @Query("SELECT songId FROM playlist_songs WHERE playlistId = :playlistId ORDER BY sortOrder ASC")
    fun getSongIdsForPlaylist(playlistId: String): Flow<List<String>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPlaylistSong(item: PlaylistSongEntity)

    @Query("DELETE FROM playlist_songs WHERE playlistId = :playlistId AND songId = :songId")
    suspend fun removeSongFromPlaylist(playlistId: String, songId: String)

    @Query("DELETE FROM playlist_songs WHERE playlistId = :playlistId")
    suspend fun clearPlaylistSongs(playlistId: String)
}
