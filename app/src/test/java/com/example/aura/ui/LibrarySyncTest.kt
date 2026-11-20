package com.example.aura.ui

import android.Manifest
import android.app.Application
import android.content.SharedPreferences
import android.content.pm.ProviderInfo
import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import android.net.Uri
import android.os.Looper
import android.provider.DocumentsContract
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import com.example.aura.data.local.AuraDatabase
import com.example.aura.data.local.PlaylistEntity
import com.example.aura.data.local.PlaylistSongEntity
import com.example.aura.data.local.SongEntity
import com.example.aura.data.storage.LocalFileManagerTest.ScanDocumentsProvider
import com.example.aura.data.storage.songIdForUri
import com.example.aura.model.Song
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver
import org.robolectric.shadows.ShadowMediaMetadataRetriever
import org.robolectric.shadows.ShadowMediaPlayer
import org.robolectric.shadows.util.DataSource
import org.robolectric.util.ReflectionHelpers
import java.time.Duration

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LibrarySyncTest {
    private lateinit var context: Application
    private lateinit var provider: ScanDocumentsProvider
    private lateinit var db: AuraDatabase
    private lateinit var model: AuraViewModel
    private val store = ViewModelStore()
    private val root: Uri get() = DocumentsContract.buildTreeDocumentUri(AUTHORITY, ROOT)
    private val child: Uri get() = DocumentsContract.buildTreeDocumentUri(AUTHORITY, CHILD)

    @Before
    fun setUp() = runBlocking {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        context = ApplicationProvider.getApplicationContext()
        AuraDatabase::class.java.getDeclaredField("INSTANCE").apply {
            isAccessible = true
            (get(null) as? AuraDatabase)?.close()
            set(null, null)
        }
        context.deleteDatabase("aura_music.db")
        context.getSharedPreferences("aura_settings", 0).edit().clear().commit()
        provider = ScanDocumentsProvider()
        provider.attachInfo(context, ProviderInfo().apply {
            authority = AUTHORITY
            exported = true
            grantUriPermissions = true
            readPermission = Manifest.permission.MANAGE_DOCUMENTS
            writePermission = Manifest.permission.MANAGE_DOCUMENTS
        })
        ShadowContentResolver.registerProviderInternal(AUTHORITY, provider)
        provider.add(ROOT, CHILD, "Album", DocumentsContract.Document.MIME_TYPE_DIR)
        provider.add(CHILD, TRACK, "Pista.flac", "audio/flac")
        listOf(root, child).forEach { tree ->
            val uri = DocumentsContract.buildDocumentUriUsingTree(tree, TRACK)
            ShadowMediaMetadataRetriever.addMetadata(
                DataSource.toDataSource(context, uri),
                MediaMetadataRetriever.METADATA_KEY_DURATION, "180000"
            )
        }
        db = AuraDatabase.getInstance(context)
        model = AuraViewModel(context)
        store.put("aura", model)
        withTimeout(10_000) { model.playlists.first {
            it.map { list -> list.id }.containsAll(listOf("playlist_favorites", "playlist_lossless"))
        } }
        db.playlistDao().insertPlaylist(PlaylistEntity("saved", "Guardadas", "", 0L, 0L))
    }

    @After
    fun tearDown() {
        store.clear()
        db.close()
        AuraDatabase::class.java.getDeclaredField("INSTANCE").apply {
            isAccessible = true
            set(null, null)
        }
        context.deleteDatabase("aura_music.db")
        Dispatchers.resetMain()
    }

    @Test
    fun `a blank playlist name is rejected without creating a row`() = runBlocking {
        val before = db.playlistDao().getAllPlaylists().first()
        model.createPlaylist("  ")
        awaitMessage("Escribe un nombre")
        assertEquals(before, db.playlistDao().getAllPlaylists().first())
    }

    @Test
    fun `adding through Favorites marks the flag rather than an ignored association`() = runBlocking {
        addFolder(root)
        val id = db.songDao().getAllSongsOnce().single().id
        model.clearStatusMessage()
        model.addSongToPlaylist("playlist_favorites", id)
        awaitMessage("Canción agregada")
        assertTrue(db.songDao().getSongById(id)!!.isFavorite)
        assertTrue(db.playlistDao().getSongIdsForPlaylist("playlist_favorites").first().isEmpty())
    }

    @Test
    fun `an incomplete rescan keeps favorites and playlist memberships`() = runBlocking {
        addFolder(root)
        val song = db.songDao().getAllSongsOnce().single()
        db.songDao().setFavorite(song.id, true)
        db.playlistDao().insertPlaylistSong(PlaylistSongEntity("saved", song.id, 3))
        provider.nullQueries += CHILD

        model.clearStatusMessage()
        model.refreshMusicFolders()
        awaitMessage("No se pudo leer")

        assertTrue(db.songDao().getSongById(song.id)!!.isFavorite)
        assertEquals(listOf(song.id), db.playlistDao().getSongIdsForPlaylist("saved").first())
    }

    @Test
    fun `a directory with missing MIME cannot erase its songs and playlist memberships`() = runBlocking {
        addFolder(root)
        val song = db.songDao().getAllSongsOnce().single()
        db.songDao().setFavorite(song.id, true)
        db.playlistDao().insertPlaylistSong(PlaylistSongEntity("saved", song.id, 3))
        provider.documents[CHILD] = provider.documents.getValue(CHILD).copy(mime = "")

        model.clearStatusMessage()
        model.refreshMusicFolders()
        awaitMessage("No se pudo leer")

        assertTrue(db.songDao().getSongById(song.id)!!.isFavorite)
        assertEquals(listOf(song.id), db.playlistDao().getSongIdsForPlaylist("saved").first())
    }

    @Test
    fun `legacy aliases merge without losing either playlist or favorite`() = runBlocking {
        val treeUri = DocumentsContract.buildDocumentUriUsingTree(root, TRACK)
        val directUri = DocumentsContract.buildDocumentUri(AUTHORITY, TRACK)
        val first = legacySong(treeUri, favorite = true, date = 7)
        val second = legacySong(directUri, favorite = false, date = 9)
        db.songDao().insertSongs(listOf(first, second))
        db.playlistDao().insertPlaylistSong(PlaylistSongEntity("saved", first.id, 3))
        db.playlistDao().insertPlaylistSong(PlaylistSongEntity("playlist_favorites", second.id, 4))

        addFolder(root)

        val stored = db.songDao().getAllSongsOnce().single()
        assertEquals(songIdForUri(treeUri), stored.id)
        assertTrue(stored.isFavorite)
        assertEquals(7L, stored.dateAdded)
        assertEquals(listOf(stored.id), db.playlistDao().getSongIdsForPlaylist("saved").first())
        assertEquals(listOf(stored.id), db.playlistDao().getSongIdsForPlaylist("playlist_favorites").first())
    }

    @Test
    fun `callbacks holding a merged alias still change favorite and playlist membership`() = runBlocking {
        val uri = DocumentsContract.buildDocumentUriUsingTree(root, TRACK)
        val alias = legacySong(uri, favorite = true, date = 7)
        db.songDao().insertSong(alias)
        db.playlistDao().insertPlaylistSong(PlaylistSongEntity("saved", alias.id, 3))
        addFolder(root)
        val canonical = songIdForUri(uri)

        model.toggleFavorite(alias.id)
        withTimeout(10_000) { model.songs.first { songs ->
            songs.any { it.id == canonical && !it.isFavorite }
        } }
        model.clearStatusMessage()
        model.removeSongFromPlaylist("saved", alias.id)
        awaitMessage("Canción removida")

        assertEquals(false, db.songDao().getSongById(canonical)!!.isFavorite)
        assertTrue(db.playlistDao().getSongIdsForPlaylist("saved").first().isEmpty())
    }

    @Test
    fun `metadata snapshots cannot undo an unfavorite during folder removal`() = runBlocking {
        addFolder(root)
        addFolder(child)
        val id = db.songDao().getAllSongsOnce().single().id
        db.songDao().setFavorite(id, true)
        provider.onChildrenQuery = { parent ->
            if (parent == ROOT) runBlocking { db.songDao().setFavorite(id, false) }
        }

        model.clearStatusMessage()
        model.removeMusicFolder(model.musicFolders.value.single { it.uri == child })
        awaitMessage("Carpeta quitada:")

        assertEquals(false, db.songDao().getSongById(id)!!.isFavorite)
    }

    @Test
    @Config(application = FailingPreferencesApplication::class)
    fun `failure saving folder selection rolls back song and playlist removal`() = runBlocking {
        addFolder(root)
        val song = db.songDao().getAllSongsOnce().single()
        db.songDao().setFavorite(song.id, true)
        db.playlistDao().insertPlaylistSong(PlaylistSongEntity("saved", song.id, 3))
        val preferences = context.getSharedPreferences("aura_settings", 0)
        val previousSelection = preferences.getString("music_folders_json", null)
        (context as FailingPreferencesApplication).failFolderCommit = true

        model.clearStatusMessage()
        model.removeMusicFolder(model.musicFolders.value.single())
        awaitMessage("No se pudo completar")

        assertTrue(db.songDao().getSongById(song.id)!!.isFavorite)
        assertEquals(listOf(song.id), db.playlistDao().getSongIdsForPlaylist("saved").first())
        assertEquals(listOf(root), model.musicFolders.value.map { it.uri })
        assertEquals(previousSelection, preferences.getString("music_folders_json", null))
    }

    @Test
    fun `a track moved outside the parent retains its data through its other authorized tree`() = runBlocking {
        addFolder(child)
        addFolder(root)
        val id = db.songDao().getAllSongsOnce().single().id
        db.songDao().setFavorite(id, true)
        db.playlistDao().insertPlaylistSong(PlaylistSongEntity("saved", id, 3))
        val before = db.songDao().getSongById(id)!!
        provider.documents[CHILD] = provider.documents.getValue(CHILD).copy(parent = null)

        model.clearStatusMessage()
        model.rescanMusicFolder(model.musicFolders.value.single { it.uri == root })
        awaitMessage("Biblioteca actualizada")

        val after = db.songDao().getAllSongsOnce().single()
        assertEquals(id, after.id)
        assertEquals(before.dateAdded, after.dateAdded)
        assertTrue(after.isFavorite)
        assertTrue(after.uriString.startsWith("$child/"))
        assertEquals(listOf(id), db.playlistDao().getSongIdsForPlaylist("saved").first())
    }

    @Test
    fun `removing an overlapping folder preserves current playback and refreshes its authorized URI`() = runBlocking {
        addFolder(root)
        addFolder(child)
        val song = db.songDao().getAllSongsOnce().single().toDomain()
        ShadowMediaPlayer.addMediaInfo(
            DataSource.toDataSource(context, Uri.parse(song.uriString)),
            ShadowMediaPlayer.MediaInfo(180_000, 100)
        )
        model.playerManager.playSong(song)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100))
        assertTrue(model.playerManager.isPlaying.value)
        model.playerManager.seekTo(12_000)
        val player = ReflectionHelpers.getField<MediaPlayer>(model.playerManager, "mediaPlayer")

        // Room may already have refreshed the URI while the playback queue still has the old tree.
        model.clearStatusMessage()
        model.rescanMusicFolder(model.musicFolders.value.single { it.uri == root })
        awaitMessage("Biblioteca actualizada")
        model.clearStatusMessage()
        model.removeMusicFolder(model.musicFolders.value.single { it.uri == child })
        awaitMessage("Carpeta quitada:")

        assertTrue(model.playerManager.isPlaying.value)
        assertSame(player, ReflectionHelpers.getField<MediaPlayer>(model.playerManager, "mediaPlayer"))
        assertEquals(12_000L, model.playerManager.currentPositionMs.value)
        assertEquals(song.id, model.playerManager.currentSong.value!!.id)
        assertTrue(model.playerManager.currentSong.value!!.uriString.startsWith("$root/"))
        assertEquals(listOf(model.playerManager.currentSong.value), model.playerManager.queue.value)
    }

    @Test
    fun `removing a nested folder retains its songs through the authorized parent`() = runBlocking {
        addFolder(root)
        val id = db.songDao().getAllSongsOnce().single().id
        db.songDao().setFavorite(id, true)
        db.playlistDao().insertPlaylistSong(PlaylistSongEntity("saved", id, 3))
        addFolder(child)
        assertEquals(1, db.songDao().getAllSongsOnce().size)

        model.clearStatusMessage()
        model.removeMusicFolder(model.musicFolders.value.single { it.uri == child })
        awaitMessage("Carpeta quitada:")

        val stored = db.songDao().getAllSongsOnce().single()
        assertEquals(id, stored.id)
        assertTrue(stored.isFavorite)
        assertTrue(stored.uriString.startsWith("$root/"))
        assertEquals(listOf(id), db.playlistDao().getSongIdsForPlaylist("saved").first())
        assertEquals(listOf(root), model.musicFolders.value.map { it.uri })
    }

    private suspend fun addFolder(uri: Uri) {
        model.clearStatusMessage()
        model.addMusicFolder(uri)
        withTimeout(10_000) { model.statusMessage.first { it != null } }
        assertTrue(model.statusMessage.value.orEmpty().let {
            it.contains("archivos nuevos") || it.contains("Biblioteca actualizada")
        })
    }

    private suspend fun awaitMessage(prefix: String) {
        withTimeout(10_000) { model.statusMessage.first { it?.startsWith(prefix) == true } }
    }

    private fun legacySong(uri: Uri, favorite: Boolean, date: Long) = SongEntity.fromDomain(
        Song(id = "saf_$uri", title = "Pista", artist = "Artista", durationMs = 180_000,
            uriString = uri.toString(), isFavorite = favorite, dateAdded = date)
    )

    private companion object {
        const val AUTHORITY = "aura.sync.documents"
        const val ROOT = "opaque-root"
        const val CHILD = "opaque-child"
        const val TRACK = "opaque-track"
    }
}

class FailingPreferencesApplication : Application() {
    var failFolderCommit = false

    override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
        val preferences = super.getSharedPreferences(name, mode)
        if (name != "aura_settings") return preferences
        return object : SharedPreferences by preferences {
            override fun edit(): SharedPreferences.Editor {
                val editor = preferences.edit()
                return object : SharedPreferences.Editor by editor {
                    override fun putString(key: String?, value: String?): SharedPreferences.Editor {
                        editor.putString(key, value)
                        return this
                    }

                    override fun commit(): Boolean {
                        if (!failFolderCommit) return editor.commit()
                        editor.apply()
                        return false
                    }
                }
            }
        }
    }
}
