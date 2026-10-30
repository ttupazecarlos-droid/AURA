package com.example.aura.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.aura.data.local.AuraDatabase
import com.example.aura.data.local.PlaylistEntity
import com.example.aura.data.local.PlaylistSongEntity
import com.example.aura.data.local.SongEntity
import com.example.aura.data.storage.FileItem
import com.example.aura.data.storage.LocalFileManager
import com.example.aura.model.Artist
import com.example.aura.model.LyricLine
import com.example.aura.model.Playlist
import com.example.aura.model.Song
import com.example.aura.player.AudioPlayerManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

enum class AuraScreen {
    HOME,
    SEARCH,
    LIBRARY,
    PLAYLIST_DETAIL,
    ARTIST_DETAIL,
    PLAYER,
    LYRICS,
    QUEUE,
    SETTINGS
}

class AuraViewModel(application: Application) : AndroidViewModel(application) {

    private val db = AuraDatabase.getInstance(application)
    private val songDao = db.songDao()
    private val playlistDao = db.playlistDao()
    val fileManager = LocalFileManager(application)
    val playerManager = AudioPlayerManager.getInstance(application)

    // Current screen navigation
    private val _currentScreen = MutableStateFlow(AuraScreen.HOME)
    val currentScreen: StateFlow<AuraScreen> = _currentScreen.asStateFlow()

    // Navigation back stack for sub-screens
    private val screenStack = mutableListOf<AuraScreen>()

    // Songs from DB
    private val _songs = MutableStateFlow<List<Song>>(emptyList())
    val songs: StateFlow<List<Song>> = _songs.asStateFlow()

    // Playlists
    private val _playlists = MutableStateFlow<List<Playlist>>(emptyList())
    val playlists: StateFlow<List<Playlist>> = _playlists.asStateFlow()

    // Selected playlist detail
    private val _selectedPlaylist = MutableStateFlow<Playlist?>(null)
    val selectedPlaylist: StateFlow<Playlist?> = _selectedPlaylist.asStateFlow()

    // Selected artist detail
    private val _selectedArtist = MutableStateFlow<Artist?>(null)
    val selectedArtist: StateFlow<Artist?> = _selectedArtist.asStateFlow()

    // Search query & active genre
    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _selectedGenre = MutableStateFlow<String?>(null)
    val selectedGenre: StateFlow<String?> = _selectedGenre.asStateFlow()

    // File manager / explorer state
    private val _currentDirectoryPath = MutableStateFlow<String?>(null)
    val currentDirectoryPath: StateFlow<String?> = _currentDirectoryPath.asStateFlow()

    private val _directoryFiles = MutableStateFlow<List<FileItem>>(emptyList())
    val directoryFiles: StateFlow<List<FileItem>> = _directoryFiles.asStateFlow()

    private val _isScanning = MutableStateFlow(false)
    val isScanning: StateFlow<Boolean> = _isScanning.asStateFlow()

    private val _statusMessage = MutableStateFlow<String?>(null)
    val statusMessage: StateFlow<String?> = _statusMessage.asStateFlow()

    // Settings toggles
    private val settingsPrefs = application.getSharedPreferences("aura_settings", 0)
    private val _notificationsEnabled = MutableStateFlow(
        settingsPrefs.getBoolean("lockscreen_controls", true)
    )
    val notificationsEnabled: StateFlow<Boolean> = _notificationsEnabled.asStateFlow()

    // Filtered songs for Search screen
    val searchResults: StateFlow<List<Song>> = combine(_songs, _searchQuery, _selectedGenre) { allSongs, query, genre ->
        if (query.isBlank() && genre == null) {
            allSongs
        } else {
            allSongs.filter { song ->
                val matchesQuery = query.isBlank() ||
                        song.title.contains(query, ignoreCase = true) ||
                        song.artist.contains(query, ignoreCase = true) ||
                        song.album.contains(query, ignoreCase = true) ||
                        song.format.contains(query, ignoreCase = true)

                val matchesGenre = genre == null ||
                        song.album.contains(genre, ignoreCase = true) ||
                        song.title.contains(genre, ignoreCase = true) ||
                        song.artist.contains(genre, ignoreCase = true)

                matchesQuery && matchesGenre
            }
        }
    }.stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    // Parsed lyrics for current song
    val parsedLyrics: StateFlow<List<LyricLine>> = combine(playerManager.currentSong) { (song) ->
        val rawLyrics = song?.lyrics ?: ""
        parseLrcLyrics(rawLyrics)
    }.stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    init {
        com.example.aura.service.MusicPlaybackService.showOnLockscreen = _notificationsEnabled.value
        loadInitialData()
        loadDirectory(null)
    }

    private fun loadInitialData() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                if (playlistDao.getPlaylistById("playlist_favorites") == null) {
                    playlistDao.insertPlaylist(
                        PlaylistEntity(
                            id = "playlist_favorites",
                            name = "Mis Favoritas",
                            description = "Tus temas preferidos siempre a mano",
                            coverColorHex = 0xFFE85D9E,
                            createdAt = System.currentTimeMillis() - 1000
                        )
                    )
                }
                if (playlistDao.getPlaylistById("playlist_lossless") == null) {
                    playlistDao.insertPlaylist(
                        PlaylistEntity(
                            id = "playlist_lossless",
                            name = "Audio Sin Pérdida (Hi-Res)",
                            description = "Colección de pistas FLAC y WAV de alta fidelidad",
                            coverColorHex = 0xFF9155FD,
                            createdAt = System.currentTimeMillis()
                        )
                    )
                }
            } catch (e: Exception) {
                android.util.Log.e("AuraViewModel", "Error creating default playlists", e)
            }
        }

        viewModelScope.launch(Dispatchers.IO) {
            // Load songs from DB
            songDao.getAllSongs().collect { entities ->
                _songs.value = entities.map { it.toDomain() }
            }
        }

        viewModelScope.launch(Dispatchers.IO) {
            combine(
                playlistDao.getAllPlaylists(),
                playlistDao.getAllPlaylistSongs()
            ) { playlistEntities, playlistSongs ->
                val songsMap = playlistSongs.groupBy({ it.playlistId }, { it.songId })
                playlistEntities.map { p ->
                    Playlist(
                        id = p.id,
                        name = p.name,
                        description = p.description,
                        coverColorHex = p.coverColorHex,
                        songIds = songsMap[p.id] ?: emptyList(),
                        createdAt = p.createdAt
                    )
                }
            }.collect { list ->
                _playlists.value = list
                // Mantiene el detalle abierto al día al añadir o quitar canciones.
                _selectedPlaylist.value = _selectedPlaylist.value?.let { selected ->
                    list.find { it.id == selected.id }
                }
            }
        }
    }

    // Navigation
    fun navigateTo(screen: AuraScreen) {
        if (_currentScreen.value != screen) {
            screenStack.add(_currentScreen.value)
            _currentScreen.value = screen
        }
    }

    // Las pestañas son raíz: cambiar de pestaña no debe apilar historial.
    fun selectTab(screen: AuraScreen) {
        screenStack.clear()
        _currentScreen.value = screen
    }

    fun navigateBack(): Boolean {
        return if (screenStack.isNotEmpty()) {
            _currentScreen.value = screenStack.removeAt(screenStack.lastIndex)
            true
        } else if (_currentScreen.value != AuraScreen.HOME) {
            _currentScreen.value = AuraScreen.HOME
            true
        } else {
            false
        }
    }

    fun openPlaylist(playlist: Playlist) {
        _selectedPlaylist.value = playlist
        navigateTo(AuraScreen.PLAYLIST_DETAIL)
    }

    fun openArtist(artistName: String) {
        val artistSongs = _songs.value.filter { it.artist.equals(artistName, ignoreCase = true) }
        val artist = Artist(
            id = "artist_${artistName.hashCode()}",
            name = artistName,
            bio = "Artista en tu biblioteca local de AURA",
            monthlyListeners = "${(100..999).random()}K oyentes locales",
            isFollowing = false,
            popularSongIds = artistSongs.map { it.id }
        )
        _selectedArtist.value = artist
        navigateTo(AuraScreen.ARTIST_DETAIL)
    }

    fun toggleFollowArtist() {
        val current = _selectedArtist.value ?: return
        _selectedArtist.value = current.copy(isFollowing = !current.isFollowing)
    }

    // Search
    fun setSearchQuery(query: String) {
        _searchQuery.value = query
    }

    fun selectGenre(genre: String?) {
        _selectedGenre.value = if (_selectedGenre.value == genre) null else genre
    }

    // Song favorite toggle (Namida style: direct song property flag)
    fun toggleFavorite(songId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val song = _songs.value.find { it.id == songId } ?: return@launch
            val newFav = !song.isFavorite
            songDao.setFavorite(songId, newFav)
        }
    }

    // Playlists creation & management
    fun createPlaylist(name: String, description: String = "", colorHex: Long = 0xFF9155FD) {
        viewModelScope.launch(Dispatchers.IO) {
            val id = "playlist_${UUID.randomUUID()}"
            val entity = PlaylistEntity(
                id = id,
                name = name,
                description = description,
                coverColorHex = colorHex,
                createdAt = System.currentTimeMillis()
            )
            playlistDao.insertPlaylist(entity)
            _statusMessage.value = "Lista creada: $name"
        }
    }

    fun deletePlaylist(playlistId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            playlistDao.deletePlaylist(playlistId)
            playlistDao.clearPlaylistSongs(playlistId)
            if (_selectedPlaylist.value?.id == playlistId) {
                withContext(Dispatchers.Main) { navigateBack() }
            }
            _statusMessage.value = "Lista eliminada"
        }
    }

    fun addSongToPlaylist(playlistId: String, songId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            playlistDao.insertPlaylistSong(
                PlaylistSongEntity(
                    playlistId = playlistId,
                    songId = songId,
                    sortOrder = System.currentTimeMillis().toInt()
                )
            )
            _statusMessage.value = "Canción agregada a la lista"
        }
    }

    fun removeSongFromPlaylist(playlistId: String, songId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            playlistDao.removeSongFromPlaylist(playlistId, songId)
            _statusMessage.value = "Canción removida de la lista"
        }
    }

    // Local Storage Scanner & File Manager
    fun scanDeviceStorage() {
        viewModelScope.launch(Dispatchers.IO) {
            _isScanning.value = true
            _statusMessage.value = "Escaneando almacenamiento local..."
            try {
                val scanned = fileManager.scanMediaStore()
                if (scanned.isNotEmpty()) {
                    songDao.insertSongs(scanned.map { SongEntity.fromDomain(it) })
                    _statusMessage.value = "Se encontraron ${scanned.size} archivos de música"
                } else {
                    _statusMessage.value = "No se encontraron más pistas en MediaStore"
                }
            } catch (e: Exception) {
                _statusMessage.value = "Error al escanear: ${e.message}"
            } finally {
                _isScanning.value = false
            }
        }
    }

    fun importSongFromUri(uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            _isScanning.value = true
            try {
                val song = fileManager.parseSongFromUri(uri)
                if (song != null) {
                    songDao.insertSong(SongEntity.fromDomain(song))
                    _statusMessage.value = "Pista importada: ${song.title}"
                    // No cortar lo que ya suena al abrir un archivo desde el gestor.
                    if (playerManager.currentSong.value == null) {
                        // MediaPlayer debe crearse/prepararse en el hilo principal.
                        withContext(Dispatchers.Main) {
                            playerManager.playSong(song, _songs.value + song)
                        }
                    }
                } else {
                    _statusMessage.value = "No se pudo leer el archivo de audio"
                }
            } catch (e: Exception) {
                _statusMessage.value = "Error importando: ${e.message}"
            } finally {
                _isScanning.value = false
            }
        }
    }

    fun importFolderFromTreeUri(treeUri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            _isScanning.value = true
            _statusMessage.value = "Escaneando carpeta seleccionada..."
            try {
                val songs = fileManager.scanFolderFromTree(treeUri)
                if (songs.isNotEmpty()) {
                    songDao.insertSongs(songs.map { SongEntity.fromDomain(it) })
                    _statusMessage.value = "Se importaron ${songs.size} canciones de la carpeta"
                } else {
                    _statusMessage.value = "No se encontraron audios en la carpeta"
                }
            } catch (e: Exception) {
                _statusMessage.value = "Error escaneando carpeta: ${e.message}"
            } finally {
                _isScanning.value = false
            }
        }
    }

    fun loadDirectory(path: String?) {
        viewModelScope.launch(Dispatchers.IO) {
            _currentDirectoryPath.value = path
            val items = fileManager.listDirectory(path)
            _directoryFiles.value = items
        }
    }

    fun clearStatusMessage() {
        _statusMessage.value = null
    }

    fun toggleNotifications(enabled: Boolean) {
        _notificationsEnabled.value = enabled
        settingsPrefs.edit().putBoolean("lockscreen_controls", enabled).apply()
        com.example.aura.service.MusicPlaybackService.showOnLockscreen = enabled
    }

    // Lyrics parser
    private fun parseLrcLyrics(lrc: String): List<LyricLine> {
        if (lrc.isBlank()) {
            return emptyList()
        }

        val lines = mutableListOf<LyricLine>()
        val regex = Regex("""\[(\d{2}):(\d{2})\.?(\d{0,3})\](.*)""")

        for (rawLine in lrc.lines()) {
            val match = regex.find(rawLine.trim())
            if (match != null) {
                val min = match.groupValues[1].toLongOrNull() ?: 0L
                val sec = match.groupValues[2].toLongOrNull() ?: 0L
                val msStr = match.groupValues[3].padEnd(3, '0')
                val ms = msStr.toLongOrNull() ?: 0L
                val totalMs = min * 60000L + sec * 1000L + ms
                val text = match.groupValues[4].trim()
                if (text.isNotEmpty()) {
                    lines.add(LyricLine(totalMs, text))
                }
            } else if (rawLine.isNotBlank()) {
                lines.add(LyricLine(lines.size * 4000L, rawLine.trim()))
            }
        }

        return lines
    }

    override fun onCleared() {
        super.onCleared()
        playerManager.stop()
        playerManager.releasePlayer()
        com.example.aura.service.MusicPlaybackService.stopService(getApplication())
    }
}
