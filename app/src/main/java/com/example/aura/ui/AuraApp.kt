package com.example.aura.ui

/**
 * ARCHIVO: AuraApp.kt
 * FUNCIÓN: Contenedor principal de la interfaz Compose. Recoge todo el estado del ViewModel y del
 *          reproductor, dibuja la barra inferior (mini player + navegación) y decide qué pantalla
 *          mostrar según AuraScreen. También aloja los diálogos y el gestor de archivos (sheet).
 * NAVEGACIÓN: MainActivity -> AuraApp -> carpeta ui/screens y carpeta ui/components.
 */

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.aura.ui.components.CreatePlaylistDialog
import com.example.aura.ui.components.FileManagerSheet
import com.example.aura.ui.components.MiniPlayerBar
import com.example.aura.ui.components.SleepTimerDialog
import com.example.aura.ui.components.SongInfoDialog
import com.example.aura.ui.screens.ArtistScreen
import com.example.aura.ui.screens.HomeScreen
import com.example.aura.ui.screens.LibraryScreen
import com.example.aura.ui.screens.LyricsScreen
import com.example.aura.ui.screens.PlayerScreen
import com.example.aura.ui.screens.PlaylistScreen
import com.example.aura.ui.screens.QueueScreen
import com.example.aura.ui.screens.SearchScreen
import com.example.aura.ui.screens.SettingsScreen
import com.example.ui.theme.AuraDarkBackground
import com.example.ui.theme.AuraDarkSurface
import com.example.ui.theme.AuraPrimaryPurple
import com.example.ui.theme.AuraTextSecondary

private val mainTabs = listOf(
    AuraScreen.HOME,
    AuraScreen.SEARCH,
    AuraScreen.LIBRARY,
    AuraScreen.SETTINGS
)

private fun screenTitle(screen: AuraScreen): String = when (screen) {
    AuraScreen.HOME -> "Inicio"
    AuraScreen.SEARCH -> "Buscar"
    AuraScreen.LIBRARY -> "Biblioteca"
    AuraScreen.SETTINGS -> "Ajustes"
    AuraScreen.PLAYLIST_DETAIL -> "Lista de reproducción"
    AuraScreen.ARTIST_DETAIL -> "Artista"
    AuraScreen.PLAYER -> "Reproductor"
    AuraScreen.LYRICS -> "Letras"
    AuraScreen.QUEUE -> "Cola de reproducción"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
/**
 * AuraApp: raíz de la UI. Observa el estado (ViewModel + AudioPlayerManager), gestiona los
 * diálogos y hace la transición animada entre pantallas.
 */
fun AuraApp(
    viewModel: AuraViewModel
) {
    // Estado de navegación, biblioteca, búsqueda, archivos y ajustes (StateFlow -> Compose).
    val currentScreen by viewModel.currentScreen.collectAsState()
    val songs by viewModel.songs.collectAsState()
    val playlists by viewModel.playlists.collectAsState()
    val selectedPlaylist by viewModel.selectedPlaylist.collectAsState()
    val selectedArtist by viewModel.selectedArtist.collectAsState()
    val searchQuery by viewModel.searchQuery.collectAsState()
    val selectedGenre by viewModel.selectedGenre.collectAsState()
    val searchResults by viewModel.searchResults.collectAsState()
    val parsedLyrics by viewModel.parsedLyrics.collectAsState()
    val isScanning by viewModel.isScanning.collectAsState()
    val statusMessage by viewModel.statusMessage.collectAsState()
    val musicFolders by viewModel.musicFolders.collectAsState()
    val notificationsEnabled by viewModel.notificationsEnabled.collectAsState()

    // Player state
    // Estado en vivo del reproductor compartido (pista, cola, progreso, shuffle, sleep timer).
    val playerManager = viewModel.playerManager
    val currentSong by playerManager.currentSong.collectAsState()
    val isPlaying by playerManager.isPlaying.collectAsState()
    val currentPosMs by playerManager.currentPositionMs.collectAsState()
    val durationMs by playerManager.durationMs.collectAsState()
    val queue by playerManager.queue.collectAsState()
    val currentIndex by playerManager.currentIndex.collectAsState()
    val isShuffle by playerManager.isShuffle.collectAsState()
    val repeatMode by playerManager.repeatMode.collectAsState()
    val sleepTimerRemainingMs by playerManager.sleepTimerRemainingMs.collectAsState()

    // Estado local de la UI: snackbar, sheet del gestor de archivos y visibilidad de diálogos.
    val snackbarHostState = remember { SnackbarHostState() }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var showFileManagerSheet by remember { mutableStateOf(false) }
    var showCreatePlaylistDialog by remember { mutableStateOf(false) }
    var showSleepTimerDialog by remember { mutableStateOf(false) }
    var showSongInfoDialog by remember { mutableStateOf(false) }

    // Muestra en un Snackbar el mensaje de estado del ViewModel (escaneos, importaciones, etc.).
    LaunchedEffect(statusMessage) {
        statusMessage?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearStatusMessage()
        }
    }

    // Al abrir el gestor se buscan archivos nuevos en las carpetas ya autorizadas.
    LaunchedEffect(showFileManagerSheet) {
        if (showFileManagerSheet) {
            viewModel.refreshMusicFolders()
        }
    }

    // Android Hardware/System Back Button Handling
    // Botón atrás del sistema: delega en la pila de navegación del ViewModel.
    BackHandler(enabled = currentScreen != AuraScreen.HOME) {
        viewModel.navigateBack()
    }

    val progress = if (durationMs > 0) (currentPosMs.toFloat() / durationMs.toFloat()) else 0f

    // Dialogs
    // Diálogo: crear una nueva lista de reproducción.
    if (showCreatePlaylistDialog) {
        CreatePlaylistDialog(
            onDismiss = { showCreatePlaylistDialog = false },
            onConfirm = { name, desc, color ->
                viewModel.createPlaylist(name, desc, color)
                showCreatePlaylistDialog = false
            }
        )
    }

    // Diálogo: temporizador de apagado (sleep timer).
    if (showSleepTimerDialog) {
        SleepTimerDialog(
            remainingMs = sleepTimerRemainingMs,
            onSetTimer = { mins -> playerManager.setSleepTimer(mins) },
            onCancelTimer = { playerManager.cancelSleepTimer() },
            onDismiss = { showSleepTimerDialog = false }
        )
    }

    // Diálogo: información técnica de la pista actual.
    if (showSongInfoDialog && currentSong != null) {
        SongInfoDialog(
            song = currentSong!!,
            onDismiss = { showSongInfoDialog = false }
        )
    }

    // Hoja inferior: gestor de archivos (escanear, importar archivos/carpetas, explorar).
    if (showFileManagerSheet) {
        FileManagerSheet(
            sheetState = sheetState,
            isScanning = isScanning,
            statusMessage = statusMessage,
            musicFolders = musicFolders,
            onDismiss = { showFileManagerSheet = false },
            onAddMusicFolder = { treeUri -> viewModel.addMusicFolder(treeUri) },
            onRemoveMusicFolder = { folder -> viewModel.removeMusicFolder(folder) },
            onRescanMusicFolder = { folder -> viewModel.rescanMusicFolder(folder) },
            onRescanMusicFolders = { viewModel.refreshMusicFolders() },
            onImportUri = { uri -> viewModel.importSongFromUri(uri) }
        )
    }

    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .background(AuraDarkBackground),
        containerColor = AuraDarkBackground,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        bottomBar = {
            // El mini player acompaña a todas las pantallas salvo el reproductor y las letras.
            val showMiniPlayer = currentScreen != AuraScreen.PLAYER &&
                    currentScreen != AuraScreen.LYRICS

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
            ) {
                if (showMiniPlayer) {
                    MiniPlayerBar(
                        currentSong = currentSong,
                        isPlaying = isPlaying,
                        progress = progress,
                        onTogglePlayPause = { playerManager.togglePlayPause() },
                        onNext = { playerManager.next() },
                        onClick = { viewModel.navigateTo(AuraScreen.PLAYER) }
                    )
                }

                if (currentScreen in mainTabs) {
                    NavigationBar(
                        containerColor = AuraDarkSurface,
                        tonalElevation = 6.dp,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp))
                    ) {
                        mainTabs.forEach { tab ->
                            val label = screenTitle(tab)
                            NavigationBarItem(
                                selected = currentScreen == tab,
                                onClick = { viewModel.selectTab(tab) },
                                icon = {
                                    Icon(
                                        imageVector = when (tab) {
                                            AuraScreen.HOME -> Icons.Default.Home
                                            AuraScreen.SEARCH -> Icons.Default.Search
                                            AuraScreen.LIBRARY -> Icons.Default.LibraryMusic
                                            else -> Icons.Default.Person
                                        },
                                        // La etiqueta ya nombra el destino.
                                        contentDescription = null
                                    )
                                },
                                label = { Text(label, fontSize = 11.sp) },
                                colors = NavigationBarItemDefaults.colors(
                                    selectedIconColor = Color.White,
                                    selectedTextColor = Color.White,
                                    indicatorColor = AuraPrimaryPurple,
                                    unselectedIconColor = AuraTextSecondary,
                                    unselectedTextColor = AuraTextSecondary
                                )
                            )
                        }
                    }
                }
            }
        }
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .semantics { paneTitle = screenTitle(currentScreen) }
        ) {
            AnimatedContent(
                targetState = currentScreen,
                transitionSpec = { fadeIn() togetherWith fadeOut() },
                label = "ScreenTransition"
            ) { screen ->
                // Router principal: cada valor de AuraScreen renderiza su pantalla correspondiente.
                when (screen) {
                    AuraScreen.HOME -> {
                        HomeScreen(
                            songs = songs,
                            playlists = playlists,
                            musicFolderCount = musicFolders.size,
                            onSongClick = { song -> playerManager.playSong(song, songs) },
                            onPlaylistClick = { pl -> viewModel.openPlaylist(pl) },
                            onOpenFileExplorer = { showFileManagerSheet = true }
                        )
                    }

                    AuraScreen.SEARCH -> {
                        SearchScreen(
                            query = searchQuery,
                            selectedArtist = selectedGenre,
                            songs = songs,
                            results = searchResults,
                            onQueryChange = { viewModel.setSearchQuery(it) },
                            onSelectArtist = { viewModel.selectGenre(it) },
                            onSongClick = { song -> playerManager.playSong(song, searchResults) }
                        )
                    }

                    AuraScreen.LIBRARY -> {
                        LibraryScreen(
                            songs = songs,
                            playlists = playlists,
                            onSongClick = { song -> playerManager.playSong(song, songs) },
                            onPlaylistClick = { pl -> viewModel.openPlaylist(pl) },
                            onToggleFavorite = { id -> viewModel.toggleFavorite(id) },
                            onAddSongToPlaylist = { plId, sId -> viewModel.addSongToPlaylist(plId, sId) },
                            onCreatePlaylistClick = { showCreatePlaylistDialog = true },
                            onOpenFileExplorer = { showFileManagerSheet = true }
                        )
                    }

                    AuraScreen.PLAYLIST_DETAIL -> {
                        val playlist = selectedPlaylist
                        if (playlist == null) {
                            LaunchedEffect(Unit) { viewModel.navigateBack() }
                        } else {
                            val playlistSongs = when (playlist.id) {
                                "playlist_favorites" -> songs.filter { it.isFavorite }
                                "playlist_lossless" -> songs.filter { it.isLossless }
                                else -> playlist.songIds.mapNotNull { id -> songs.find { it.id == id } }
                            }
                            val isEditable = playlist.isCustom &&
                                    playlist.id != "playlist_lossless" &&
                                    playlist.id != "playlist_favorites"
                            PlaylistScreen(
                                playlist = playlist,
                                songs = playlistSongs,
                                onBack = { viewModel.navigateBack() },
                                onPlayAll = {
                                    if (playlistSongs.isNotEmpty()) {
                                        playerManager.playSong(playlistSongs.first(), playlistSongs)
                                    }
                                },
                                onSongClick = { song ->
                                    playerManager.playSong(song, playlistSongs)
                                },
                                onToggleFavorite = { id -> viewModel.toggleFavorite(id) },
                                onRemoveSong = if (isEditable) {
                                    { songId -> viewModel.removeSongFromPlaylist(playlist.id, songId) }
                                } else null,
                                onDeletePlaylist = if (isEditable) {
                                    { viewModel.deletePlaylist(playlist.id) }
                                } else null
                            )
                        }
                    }

                    AuraScreen.ARTIST_DETAIL -> {
                        val artist = selectedArtist
                        if (artist == null) {
                            LaunchedEffect(Unit) { viewModel.navigateBack() }
                        } else {
                            val artistSongs = songs.filter { it.artist.equals(artist.name, ignoreCase = true) }
                            ArtistScreen(
                                artist = artist,
                                songs = artistSongs,
                                onBack = { viewModel.navigateBack() },
                                onFollowClick = { viewModel.toggleFollowArtist() },
                                onSongClick = { song -> playerManager.playSong(song, artistSongs) }
                            )
                        }
                    }

                    AuraScreen.PLAYER -> {
                        PlayerScreen(
                            currentSong = currentSong,
                            isPlaying = isPlaying,
                            currentPositionMs = currentPosMs,
                            durationMs = durationMs,
                            isShuffle = isShuffle,
                            repeatMode = repeatMode,
                            sleepTimerRemainingMs = sleepTimerRemainingMs,
                            hasLyrics = parsedLyrics.isNotEmpty(),
                            onBack = { viewModel.navigateBack() },
                            onTogglePlayPause = { playerManager.togglePlayPause() },
                            onSeekTo = { playerManager.seekTo(it) },
                            onNext = { playerManager.next() },
                            onPrevious = { playerManager.previous() },
                            onToggleShuffle = { playerManager.toggleShuffle() },
                            onToggleRepeat = { playerManager.toggleRepeat() },
                            onOpenLyrics = { viewModel.navigateTo(AuraScreen.LYRICS) },
                            onOpenQueue = { viewModel.navigateTo(AuraScreen.QUEUE) },
                            onOpenSleepTimer = { showSleepTimerDialog = true },
                            onOpenSongInfo = { showSongInfoDialog = true },
                            onOpenArtist = { name -> viewModel.openArtist(name) }
                        )
                    }

                    AuraScreen.LYRICS -> {
                        LyricsScreen(
                            currentSong = currentSong,
                            lyrics = parsedLyrics,
                            currentPositionMs = currentPosMs,
                            onBack = { viewModel.navigateBack() },
                            onSeekTo = { playerManager.seekTo(it) }
                        )
                    }

                    AuraScreen.QUEUE -> {
                        QueueScreen(
                            currentSong = currentSong,
                            queue = queue,
                            currentIndex = currentIndex,
                            isShuffle = isShuffle,
                            onBack = { viewModel.navigateBack() },
                            onSongClick = { song -> playerManager.playSong(song, queue) },
                            onRemoveFromQueue = { idx -> playerManager.removeFromQueue(idx) },
                            onBackToLibrary = { viewModel.selectTab(AuraScreen.LIBRARY) }
                        )
                    }

                    AuraScreen.SETTINGS -> {
                        SettingsScreen(
                            notificationsEnabled = notificationsEnabled,
                            onToggleNotifications = { viewModel.toggleNotifications(it) },
                            onOpenFileExplorer = { showFileManagerSheet = true }
                        )
                    }
                }
            }
        }
    }
}
