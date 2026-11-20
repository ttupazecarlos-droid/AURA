package com.example.aura.data.local

import android.content.Context
import android.database.sqlite.SQLiteConstraintException
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import java.util.Collections
import java.util.concurrent.Executor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AuraDatabaseTest {
    private lateinit var db: AuraDatabase
    private lateinit var songs: SongDao
    private lateinit var playlists: PlaylistDao
    private val deletes = Collections.synchronizedList(mutableListOf<Pair<String, Int>>())

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<Context>(),
            AuraDatabase::class.java
        )
            .allowMainThreadQueries()
            .setQueryCallback({ sql, args ->
                if (sql.trimStart().startsWith("DELETE FROM", ignoreCase = true)) {
                    deletes.add(sql to args.size)
                }
            }, Executor { it.run() })
            .build()
        songs = db.songDao()
        playlists = db.playlistDao()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `los metadatos del proveedor sobreviven a Room y a ambas conversiones`() = runBlocking {
        val original = testSong().copy(
            sourceModifiedAt = 1_700_000_000_000L,
            fileSizeBytes = 4_500_000_000L
        )
        songs.insertSong(original)

        val stored = songs.getSongById(original.id)!!
        assertEquals(original, stored)
        assertEquals(original, SongEntity.fromDomain(stored.toDomain()))
        assertEquals(0L, testSong().sourceModifiedAt)
        assertEquals(0L, testSong().fileSizeBytes)
    }

    @Test
    fun `la lectura puntual devuelve las mismas canciones y orden que el Flow`() = runBlocking {
        songs.insertSongs(listOf(testSong("old", dateAdded = 1L), testSong("new", dateAdded = 2L)))

        assertEquals(listOf("new", "old"), songs.getAllSongsOnce().map { it.id })
        assertEquals(songs.getAllSongs().first(), songs.getAllSongsOnce())
    }

    @Test
    fun `actualizar una cancion o lista no elimina sus asociaciones`() = runBlocking {
        val original = testSong()
        val playlist = testPlaylist()
        val link = PlaylistSongEntity(playlist.id, original.id, 7)
        songs.insertSong(original)
        playlists.insertPlaylist(playlist)
        playlists.insertPlaylistSong(link)

        val updated = original.copy(title = "Metadatos nuevos", uriString = "content://new/document/1")
        songs.updateSong(updated)
        playlists.insertPlaylist(playlist.copy(name = "Nombre nuevo"))

        assertEquals(updated, songs.getSongById(original.id))
        assertEquals("Nombre nuevo", playlists.getPlaylistById(playlist.id)!!.name)
        assertEquals(listOf(link), playlists.getAllPlaylistSongs().first())
    }

    @Test
    fun `storeSong actualiza metadatos pero conserva favorito y fecha mas antigua`() = runBlocking {
        val original = testSong(isFavorite = true, dateAdded = 10L)
        songs.insertSong(original)
        playlists.insertPlaylist(testPlaylist())
        val link = PlaylistSongEntity("playlist_1", original.id, 4)
        playlists.insertPlaylistSong(link)
        val scanned = original.copy(
            title = "Título corregido",
            uriString = "content://provider/tree/new/document/song_1",
            isFavorite = false,
            dateAdded = 999L,
            sourceModifiedAt = 555L,
            fileSizeBytes = 666L
        )

        val result = db.storeSong(scanned)

        assertEquals(scanned.copy(isFavorite = true, dateAdded = 10L), result)
        assertEquals(result, songs.getSongById(original.id))
        assertEquals(listOf(link), playlists.getAllPlaylistSongs().first())
    }

    @Test
    fun `storeSong conserva tambien el favorito entrante y una fecha entrante anterior`() = runBlocking {
        songs.insertSong(testSong(isFavorite = false, dateAdded = 500L))
        val incoming = testSong(isFavorite = true, dateAdded = 0L)

        assertEquals(incoming, db.storeSong(incoming))
        assertEquals(incoming, songs.getSongById(incoming.id))
    }

    @Test
    fun `fusionar aliases conserva todas las listas favoritos fechas y canciones ajenas`() = runBlocking {
        val canonical = testSong("canonical", dateAdded = 300L)
        val favoriteAlias = testSong("legacy_a", isFavorite = true, dateAdded = 100L)
        val oldestAlias = testSong("legacy_b", dateAdded = 0L)
        val unrelated = testSong("unrelated", isFavorite = true, dateAdded = 50L)
        songs.insertSongs(listOf(canonical, favoriteAlias, oldestAlias, unrelated))
        listOf("shared", "only_a", "only_b", "untouched").forEach {
            playlists.insertPlaylist(testPlaylist(it))
        }
        val canonicalLink = PlaylistSongEntity("shared", canonical.id, 9)
        val unrelatedLink = PlaylistSongEntity("untouched", unrelated.id, 8)
        playlists.insertPlaylistSong(canonicalLink)
        playlists.insertPlaylistSong(PlaylistSongEntity("shared", favoriteAlias.id, 3))
        playlists.insertPlaylistSong(PlaylistSongEntity("only_a", favoriteAlias.id, 2))
        playlists.insertPlaylistSong(PlaylistSongEntity("only_b", oldestAlias.id, 5))
        playlists.insertPlaylistSong(unrelatedLink)
        val incoming = canonical.copy(
            title = "Título actual",
            uriString = "content://provider/document/canonical",
            dateAdded = 999L,
            sourceModifiedAt = 777L,
            fileSizeBytes = 888L
        )

        val stored = db.storeSong(
            incoming,
            aliasIds = listOf(favoriteAlias.id, oldestAlias.id, "missing", canonical.id, favoriteAlias.id)
        )

        assertEquals(incoming.copy(isFavorite = true, dateAdded = 0L), stored)
        assertEquals(stored, songs.getSongById(canonical.id))
        assertNull(songs.getSongById(favoriteAlias.id))
        assertNull(songs.getSongById(oldestAlias.id))
        assertEquals(unrelated, songs.getSongById(unrelated.id))
        val links = playlists.getAllPlaylistSongs().first()
        assertEquals(4, links.size)
        assertEquals(setOf("shared", "only_a", "only_b"), links.filter {
            it.songId == canonical.id
        }.map { it.playlistId }.toSet())
        assertEquals(2, links.single { it.playlistId == "only_a" }.sortOrder)
        assertEquals(5, links.single { it.playlistId == "only_b" }.sortOrder)
        assertTrue(links.contains(unrelatedLink))
        assertEquals(4, playlists.getAllPlaylists().first().size)
    }

    @Test
    fun `fusionar un alias crea primero el padre canonico sin perder su unica lista`() = runBlocking {
        val alias = testSong("legacy", isFavorite = true, dateAdded = 12L)
        songs.insertSong(alias)
        playlists.insertPlaylist(testPlaylist())
        playlists.insertPlaylistSong(PlaylistSongEntity("playlist_1", alias.id, 6))
        val incoming = testSong("canonical", dateAdded = 40L)

        val stored = db.storeSong(incoming, listOf(alias.id))

        assertEquals(incoming.copy(isFavorite = true, dateAdded = 12L), stored)
        assertEquals(listOf(stored), songs.getAllSongsOnce())
        assertEquals(
            listOf(PlaylistSongEntity("playlist_1", incoming.id, 6)),
            playlists.getAllPlaylistSongs().first()
        )
    }

    @Test
    fun `un fallo al borrar alias revierte cancion canonica metadatos y todas las listas`() = runBlocking {
        val canonical = testSong("canonical", dateAdded = 200L)
        val alias = testSong("legacy", isFavorite = true, dateAdded = 1L)
        songs.insertSongs(listOf(canonical, alias))
        playlists.insertPlaylist(testPlaylist())
        val link = PlaylistSongEntity("playlist_1", alias.id, 7)
        playlists.insertPlaylistSong(link)
        db.openHelper.writableDatabase.execSQL(
            """CREATE TRIGGER fail_alias_delete BEFORE DELETE ON songs
               WHEN OLD.id = 'legacy' BEGIN SELECT RAISE(ABORT, 'injected failure'); END"""
        )

        expectConstraintFailure {
            db.storeSong(canonical.copy(title = "No debe persistir"), listOf(alias.id))
        }

        assertEquals(canonical, songs.getSongById(canonical.id))
        assertEquals(alias, songs.getSongById(alias.id))
        assertEquals(listOf(link), playlists.getAllPlaylistSongs().first())
    }

    @Test
    fun `los padres inexistentes se rechazan tanto al insertar como al agregar a lista`() = runBlocking {
        songs.insertSong(testSong())
        playlists.insertPlaylist(testPlaylist())

        expectConstraintFailure {
            playlists.insertPlaylistSong(PlaylistSongEntity("missing", "song_1", 0))
        }
        expectConstraintFailure {
            playlists.insertPlaylistSong(PlaylistSongEntity("playlist_1", "missing", 0))
        }
        expectConstraintFailure { playlists.addSongToPlaylist("missing", "song_1") }
        expectConstraintFailure { playlists.addSongToPlaylist("playlist_1", "missing") }
        assertTrue(playlists.getAllPlaylistSongs().first().isEmpty())
    }

    @Test
    fun `borrar cualquiera de los padres hace cascade solo sobre sus propias relaciones`() = runBlocking {
        songs.insertSongs(listOf(testSong("deleted"), testSong("kept")))
        playlists.insertPlaylist(testPlaylist("deleted_list"))
        playlists.insertPlaylist(testPlaylist("kept_list"))
        playlists.insertPlaylistSong(PlaylistSongEntity("deleted_list", "deleted", 0))
        playlists.insertPlaylistSong(PlaylistSongEntity("deleted_list", "kept", 1))
        val keptLink = PlaylistSongEntity("kept_list", "kept", 0)
        playlists.insertPlaylistSong(keptLink)

        songs.deleteSong("deleted")
        assertEquals(2, playlists.getAllPlaylistSongs().first().size)
        playlists.deletePlaylist("deleted_list")

        assertEquals(listOf(keptLink), playlists.getAllPlaylistSongs().first())
        assertEquals(testSong("kept"), songs.getSongById("kept"))
        assertNull(playlists.getPlaylistById("deleted_list"))
    }

    @Test
    fun `borrar 1001 canciones usa bloques de 900 y limpia relaciones sin borrar listas`() = runBlocking {
        val removed = (0..1000).map { testSong("removed_$it") }
        val kept = testSong("kept", isFavorite = true)
        songs.insertSongs(removed + kept)
        playlists.insertPlaylist(testPlaylist())
        (removed + kept).forEachIndexed { index, song ->
            playlists.insertPlaylistSong(PlaylistSongEntity("playlist_1", song.id, index))
        }

        songs.deleteSongsByIds(removed.map { it.id })

        assertEquals(listOf(kept), songs.getAllSongsOnce())
        assertEquals(
            listOf(PlaylistSongEntity("playlist_1", kept.id, 1001)),
            playlists.getAllPlaylistSongs().first()
        )
        assertEquals(testPlaylist(), playlists.getPlaylistById("playlist_1"))
        assertEquals(listOf(101, 900), deletes.filter { it.first.contains("songs WHERE") }
            .map { it.second }.sorted())
    }

    @Test
    fun `limpiar relaciones de 1001 canciones usa bloques de 900 sin borrar las canciones`() = runBlocking {
        val originals = (0..1000).map { testSong("song_$it") }
        songs.insertSongs(originals)
        playlists.insertPlaylist(testPlaylist())
        originals.forEachIndexed { index, song ->
            playlists.insertPlaylistSong(PlaylistSongEntity("playlist_1", song.id, index))
        }

        playlists.deletePlaylistSongsForSongIds(originals.map { it.id })

        assertTrue(playlists.getAllPlaylistSongs().first().isEmpty())
        assertEquals(originals.toSet(), songs.getAllSongsOnce().toSet())
        assertEquals(listOf(101, 900), deletes.filter { it.first.contains("playlist_songs WHERE") }
            .map { it.second }.sorted())
    }

    @Test
    fun `un fallo en el segundo bloque revierte tambien el primer bloque y su cascade`() = runBlocking {
        val originals = (0..1000).map { testSong("song_$it") }
        songs.insertSongs(originals)
        playlists.insertPlaylist(testPlaylist())
        val links = originals.mapIndexed { index, song ->
            PlaylistSongEntity("playlist_1", song.id, index)
        }
        links.forEach { playlists.insertPlaylistSong(it) }
        db.openHelper.writableDatabase.execSQL(
            """CREATE TRIGGER fail_last_song BEFORE DELETE ON songs
               WHEN OLD.id = 'song_1000' BEGIN SELECT RAISE(ABORT, 'injected failure'); END"""
        )

        expectConstraintFailure { songs.deleteSongsByIds(originals.map { it.id }) }

        assertEquals(originals.toSet(), songs.getAllSongsOnce().toSet())
        assertEquals(links.toSet(), playlists.getAllPlaylistSongs().first().toSet())
    }

    @Test
    fun `un fallo al limpiar el segundo bloque de relaciones revierte el primero`() = runBlocking {
        val originals = (0..1000).map { testSong("song_$it") }
        songs.insertSongs(originals)
        playlists.insertPlaylist(testPlaylist())
        val links = originals.mapIndexed { index, song ->
            PlaylistSongEntity("playlist_1", song.id, index)
        }
        links.forEach { playlists.insertPlaylistSong(it) }
        db.openHelper.writableDatabase.execSQL(
            """CREATE TRIGGER fail_last_link BEFORE DELETE ON playlist_songs
               WHEN OLD.songId = 'song_1000' BEGIN SELECT RAISE(ABORT, 'injected failure'); END"""
        )

        expectConstraintFailure {
            playlists.deletePlaylistSongsForSongIds(originals.map { it.id })
        }

        assertEquals(links.toSet(), playlists.getAllPlaylistSongs().first().toSet())
        assertEquals(originals.toSet(), songs.getAllSongsOnce().toSet())
    }

    @Test
    fun `borrados vacios no cambian datos ni generan SQL de borrado`() = runBlocking {
        songs.insertSong(testSong())

        songs.deleteSongsByIds(emptyList())
        playlists.deletePlaylistSongsForSongIds(emptyList())

        assertEquals(listOf(testSong()), songs.getAllSongsOnce())
        assertTrue(deletes.isEmpty())
    }

    @Test
    fun `alternar favorito concurrentemente no pierde cambios y no afecta otras pistas`() = runBlocking {
        songs.insertSongs(listOf(testSong("toggled"), testSong("unrelated")))

        coroutineScope {
            repeat(101) {
                launch(Dispatchers.IO) { songs.toggleFavorite("toggled") }
            }
        }

        assertTrue(songs.getSongById("toggled")!!.isFavorite)
        assertFalse(songs.getSongById("unrelated")!!.isFavorite)
        songs.toggleFavorite("toggled")
        assertFalse(songs.getSongById("toggled")!!.isFavorite)
        songs.toggleFavorite("missing")
        assertNull(songs.getSongById("missing"))
    }

    @Test
    fun `agregar canciones asigna orden creciente por lista y no por tiempo epoch`() = runBlocking {
        songs.insertSongs(listOf(testSong("a"), testSong("b"), testSong("c")))
        playlists.insertPlaylist(testPlaylist("first"))
        playlists.insertPlaylist(testPlaylist("second"))
        playlists.insertPlaylistSong(PlaylistSongEntity("first", "a", 40))

        playlists.addSongToPlaylist("first", "b")
        playlists.addSongToPlaylist("first", "c")
        playlists.addSongToPlaylist("second", "c")

        val links = playlists.getAllPlaylistSongs().first()
        assertEquals(41, links.single { it.playlistId == "first" && it.songId == "b" }.sortOrder)
        assertEquals(42, links.single { it.playlistId == "first" && it.songId == "c" }.sortOrder)
        assertEquals(0, links.single { it.playlistId == "second" }.sortOrder)
        assertEquals(listOf("a", "b", "c"), playlists.getSongIdsForPlaylist("first").first())
    }

    private suspend fun expectConstraintFailure(block: suspend () -> Unit) {
        try {
            block()
        } catch (_: SQLiteConstraintException) {
            return
        }
        throw AssertionError("Se esperaba un rechazo de la restricción SQLite")
    }
}
