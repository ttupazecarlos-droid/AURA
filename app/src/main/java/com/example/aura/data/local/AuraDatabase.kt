package com.example.aura.data.local

/**
 * ARCHIVO: AuraDatabase.kt
 * FUNCIÓN: Configuración de Room: entidades, versión, DAO y singleton de la base de datos "aura_music.db".
 * NAVEGACIÓN: AuraViewModel pide la instancia con AuraDatabase.getInstance(application).
 */

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.room.withTransaction
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        SongEntity::class,
        PlaylistEntity::class,
        PlaylistSongEntity::class
    ],
    version = 2,
    exportSchema = true
)
abstract class AuraDatabase : RoomDatabase() {
    // DAO de canciones (tabla songs).
    abstract fun songDao(): SongDao
    // DAO de listas y su relación con canciones.
    abstract fun playlistDao(): PlaylistDao

    suspend fun storeSong(song: SongEntity, aliasIds: List<String> = emptyList()): SongEntity =
        withTransaction {
            val songs = songDao()
            val playlists = playlistDao()
            // La identidad SAF y la autorización de estos alias pertenecen al caller.
            val existing = (aliasIds + song.id).distinct().mapNotNull { songs.getSongById(it) }
            val stored = song.copy(
                isFavorite = song.isFavorite || existing.any { it.isFavorite },
                dateAdded = minOf(song.dateAdded, existing.minOfOrNull { it.dateAdded } ?: song.dateAdded)
            )
            songs.updateSong(stored)
            val aliases = existing.filter { it.id != song.id }
            aliases.forEach { playlists.copySongToPlaylists(it.id, song.id) }
            songs.deleteSongsByIds(aliases.map { it.id })
            stored
        }

    companion object {
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE songs ADD COLUMN sourceModifiedAt INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE songs ADD COLUMN fileSizeBytes INTEGER NOT NULL DEFAULT 0")
                db.execSQL("CREATE INDEX index_songs_dateAdded ON songs (dateAdded)")
                db.execSQL("CREATE INDEX index_songs_uriString ON songs (uriString)")
                db.execSQL("""
                    CREATE TABLE playlist_songs_new (
                        playlistId TEXT NOT NULL,
                        songId TEXT NOT NULL,
                        sortOrder INTEGER NOT NULL,
                        PRIMARY KEY(playlistId, songId),
                        FOREIGN KEY(playlistId) REFERENCES playlists(id) ON UPDATE NO ACTION ON DELETE CASCADE,
                        FOREIGN KEY(songId) REFERENCES songs(id) ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                """.trimIndent())
                // Room ya ejecuta la migración en transacción; solo se excluyen padres inexistentes.
                db.execSQL("""
                    INSERT INTO playlist_songs_new (playlistId, songId, sortOrder)
                    SELECT relation.playlistId, relation.songId, relation.sortOrder
                    FROM playlist_songs AS relation
                    INNER JOIN playlists ON playlists.id = relation.playlistId
                    INNER JOIN songs ON songs.id = relation.songId
                """.trimIndent())
                db.execSQL("DROP TABLE playlist_songs")
                db.execSQL("ALTER TABLE playlist_songs_new RENAME TO playlist_songs")
                db.execSQL("CREATE INDEX index_playlist_songs_songId ON playlist_songs (songId)")
            }
        }

        @Volatile
        private var INSTANCE: AuraDatabase? = null

        // Devuelve (o crea) la única instancia de la base de datos de la app.
        fun getInstance(context: Context): AuraDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AuraDatabase::class.java,
                    "aura_music.db"
                ).addMigrations(MIGRATION_1_2).build().also { INSTANCE = it }
            }
        }
    }
}
