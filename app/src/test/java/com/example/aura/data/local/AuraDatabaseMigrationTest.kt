package com.example.aura.data.local

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AuraDatabaseMigrationTest {
    private lateinit var context: Context
    private var db: AuraDatabase? = null

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        resetSingleton()
        context.deleteDatabase(DATABASE_NAME)
    }

    @After
    fun tearDown() {
        db?.close()
        resetSingleton()
        context.deleteDatabase(DATABASE_NAME)
    }

    @Test
    fun `abrir v1 con el builder real migra a v2 conservando todos los datos validos`() =
        runBlocking(Dispatchers.IO) {
            val originals = listOf(
                testSong("favorite", isFavorite = true, dateAdded = 0L),
                testSong("normal", dateAdded = 123L).copy(filePath = "/Music/pista.flac"),
                testSong("duplicate_uri", isFavorite = true, dateAdded = 99L).copy(
                    uriString = testSong("favorite").uriString
                )
            )
            createLegacyDatabase(originals)

            val migrated = AuraDatabase.getInstance(context).also { db = it }
            assertEquals(2, migrated.openHelper.writableDatabase.version)
            assertEquals(originals.toSet(), migrated.songDao().getAllSongs().first().toSet())
            assertEquals(
                setOf(testPlaylist("populated"), testPlaylist("empty")),
                migrated.playlistDao().getAllPlaylists().first().toSet()
            )
            assertEquals(
                setOf(
                    PlaylistSongEntity("populated", "favorite", -7),
                    PlaylistSongEntity("populated", "normal", 42),
                    PlaylistSongEntity("populated", "duplicate_uri", 42)
                ),
                migrated.playlistDao().getAllPlaylistSongs().first().toSet()
            )
            migrated.openHelper.writableDatabase.query(
                "SELECT sourceModifiedAt, fileSizeBytes FROM songs"
            ).use { cursor ->
                var count = 0
                while (cursor.moveToNext()) {
                    assertEquals(0L, cursor.getLong(0))
                    assertEquals(0L, cursor.getLong(1))
                    count++
                }
                assertEquals(originals.size, count)
            }
            migrated.openHelper.writableDatabase.query("PRAGMA foreign_key_check").use {
                assertFalse(it.moveToFirst())
            }
            migrated.openHelper.writableDatabase.query("PRAGMA integrity_check").use {
                assertTrue(it.moveToFirst())
                assertEquals("ok", it.getString(0))
            }

            migrated.close()
            resetSingleton()
            val reopened = AuraDatabase.getInstance(context).also { db = it }
            assertEquals(originals.toSet(), reopened.songDao().getAllSongs().first().toSet())
            assertEquals(3, reopened.playlistDao().getAllPlaylistSongs().first().size)
        }

    @Test
    fun `la migracion instala indices utilizables y claves foraneas cascade`() =
        runBlocking(Dispatchers.IO) {
            createLegacyDatabase(listOf(
                testSong("favorite", isFavorite = true),
                testSong("normal"),
                testSong("duplicate_uri")
            ))
            val migrated = AuraDatabase.getInstance(context).also { db = it }
            val sqlite = migrated.openHelper.writableDatabase
            sqlite.query("EXPLAIN QUERY PLAN SELECT * FROM songs ORDER BY dateAdded DESC").use {
                assertTrue(it.moveToFirst())
                assertTrue(it.getString(it.getColumnIndexOrThrow("detail"))
                    .contains("USING INDEX", ignoreCase = true))
            }
            sqlite.query(
                "EXPLAIN QUERY PLAN SELECT * FROM songs WHERE uriString = 'content://provider/document/favorite'"
            ).use {
                assertTrue(it.moveToFirst())
                assertTrue(it.getString(it.getColumnIndexOrThrow("detail"))
                    .contains("USING INDEX", ignoreCase = true))
            }
            sqlite.query(
                "EXPLAIN QUERY PLAN SELECT * FROM playlist_songs WHERE songId = 'favorite'"
            ).use {
                assertTrue(it.moveToFirst())
                assertTrue(it.getString(it.getColumnIndexOrThrow("detail"))
                    .contains("USING INDEX", ignoreCase = true))
            }
            val foreignKeys = mutableSetOf<Triple<String, String, String>>()
            sqlite.query("PRAGMA foreign_key_list(playlist_songs)").use {
                while (it.moveToNext()) {
                    foreignKeys.add(Triple(
                        it.getString(it.getColumnIndexOrThrow("from")),
                        it.getString(it.getColumnIndexOrThrow("table")),
                        it.getString(it.getColumnIndexOrThrow("on_delete"))
                    ))
                }
            }
            assertEquals(
                setOf(Triple("songId", "songs", "CASCADE"), Triple("playlistId", "playlists", "CASCADE")),
                foreignKeys
            )
        }

    @Test
    fun `una version sin ruta de migracion falla cerrada sin destruir canciones ni listas`() =
        runBlocking(Dispatchers.IO) {
            val original = testSong("favorite", isFavorite = true, dateAdded = 7L)
            createLegacyDatabase(listOf(original), version = 3)
            val future = AuraDatabase.getInstance(context).also { db = it }
            var rejected = false
            try {
                future.openHelper.writableDatabase
            } catch (_: IllegalStateException) {
                rejected = true
            }
            assertTrue("No se permite fallback destructivo ni downgrade sin migración", rejected)
            future.close()
            SQLiteDatabase.openDatabase(
                context.getDatabasePath(DATABASE_NAME).path,
                null,
                SQLiteDatabase.OPEN_READONLY
            ).use { sqlite ->
                assertEquals(3, sqlite.version)
                sqlite.rawQuery("SELECT isFavorite, dateAdded FROM songs WHERE id = 'favorite'", null).use {
                    assertTrue(it.moveToFirst())
                    assertEquals(1, it.getInt(0))
                    assertEquals(7L, it.getLong(1))
                }
                sqlite.rawQuery("SELECT COUNT(*) FROM playlists", null).use {
                    assertTrue(it.moveToFirst())
                    assertEquals(2, it.getInt(0))
                }
            }
        }

    private fun createLegacyDatabase(songs: List<SongEntity>, version: Int = 1) {
        object : SQLiteOpenHelper(context, DATABASE_NAME, null, version) {
            override fun onCreate(db: SQLiteDatabase) {
                // Literales del AuraDatabase_Impl v1; nunca generar el pasado con las entidades actuales.
                db.execSQL("CREATE TABLE IF NOT EXISTS `songs` (`id` TEXT NOT NULL, `title` TEXT NOT NULL, `artist` TEXT NOT NULL, `album` TEXT NOT NULL, `durationMs` INTEGER NOT NULL, `uriString` TEXT NOT NULL, `filePath` TEXT, `format` TEXT NOT NULL, `sampleRate` INTEGER NOT NULL, `bitDepth` INTEGER NOT NULL, `bitrateKbps` INTEGER NOT NULL, `isLossless` INTEGER NOT NULL, `isFavorite` INTEGER NOT NULL, `lyrics` TEXT NOT NULL, `coverColorHex` INTEGER NOT NULL, `dateAdded` INTEGER NOT NULL, PRIMARY KEY(`id`))")
                db.execSQL("CREATE TABLE IF NOT EXISTS `playlists` (`id` TEXT NOT NULL, `name` TEXT NOT NULL, `description` TEXT NOT NULL, `coverColorHex` INTEGER NOT NULL, `createdAt` INTEGER NOT NULL, PRIMARY KEY(`id`))")
                db.execSQL("CREATE TABLE IF NOT EXISTS `playlist_songs` (`playlistId` TEXT NOT NULL, `songId` TEXT NOT NULL, `sortOrder` INTEGER NOT NULL, PRIMARY KEY(`playlistId`, `songId`))")
                db.execSQL("CREATE TABLE IF NOT EXISTS room_master_table (id INTEGER PRIMARY KEY,identity_hash TEXT)")
                db.execSQL("INSERT OR REPLACE INTO room_master_table (id,identity_hash) VALUES(42, '5b676fdc985f527a393620d1d7ed702f')")
            }

            override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
                error("La fixture solo crea bases antiguas")
            }
        }.use { helper ->
            val sqlite = helper.writableDatabase
            songs.forEach { song ->
                sqlite.execSQL(
                    "INSERT INTO songs VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    arrayOf<Any?>(
                        song.id, song.title, song.artist, song.album, song.durationMs, song.uriString,
                        song.filePath, song.format, song.sampleRate, song.bitDepth, song.bitrateKbps,
                        if (song.isLossless) 1 else 0, if (song.isFavorite) 1 else 0, song.lyrics,
                        song.coverColorHex, song.dateAdded
                    )
                )
            }
            listOf("populated", "empty").forEach { id ->
                val playlist = testPlaylist(id)
                sqlite.execSQL(
                    "INSERT INTO playlists VALUES (?, ?, ?, ?, ?)",
                    arrayOf<Any?>(playlist.id, playlist.name, playlist.description, playlist.coverColorHex, playlist.createdAt)
                )
            }
            sqlite.execSQL("INSERT INTO playlist_songs VALUES ('populated', 'favorite', -7)")
            sqlite.execSQL("INSERT INTO playlist_songs VALUES ('populated', 'normal', 42)")
            sqlite.execSQL("INSERT INTO playlist_songs VALUES ('populated', 'duplicate_uri', 42)")
            sqlite.execSQL("INSERT INTO playlist_songs VALUES ('populated', 'missing_song', 99)")
            sqlite.execSQL("INSERT INTO playlist_songs VALUES ('missing_playlist', 'favorite', 100)")
            sqlite.execSQL("INSERT INTO playlist_songs VALUES ('missing_playlist', 'missing_song', 101)")
        }
    }

    private fun resetSingleton() {
        AuraDatabase::class.java.getDeclaredField("INSTANCE").apply {
            isAccessible = true
            (get(null) as? AuraDatabase)?.close()
            set(null, null)
        }
    }

    private companion object {
        const val DATABASE_NAME = "aura_music.db"
    }
}
