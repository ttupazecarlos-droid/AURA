package com.example.aura.ui.screens

/**
 * ARCHIVO: LibraryScreen.kt
 * FUNCIÓN: Pantalla de biblioteca con pestañas: canciones, listas y carpetas. Permite reproducir,
 *          marcar favoritos, añadir a listas, crear listas y abrir el gestor de archivos.
 * NAVEGACIÓN: AuraApp (AuraScreen.LIBRARY) -> LibraryScreen -> AuraViewModel / playerManager.
 */

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.aura.model.Playlist
import com.example.aura.model.Song
import com.example.aura.ui.isAutomaticPlaylist
import com.example.ui.theme.AuraAccentPink
import com.example.ui.theme.AuraBadge
import com.example.ui.theme.AuraCardBorder
import com.example.ui.theme.AuraDarkBackground
import com.example.ui.theme.AuraDarkSurfaceVariant
import com.example.ui.theme.AuraPrimaryPurple
import com.example.ui.theme.AuraPrimaryPurpleLight
import com.example.ui.theme.AuraScreenTitle
import com.example.ui.theme.AuraTextMuted
import com.example.ui.theme.AuraTextSecondary

@Composable
/**
 * LibraryScreen: biblioteca local organizada en tres pestañas (Canciones / Listas / Carpetas).
 */
fun LibraryScreen(
    songs: List<Song>,
    playlists: List<Playlist>,
    onSongClick: (Song) -> Unit,
    onPlaylistClick: (Playlist) -> Unit,
    onToggleFavorite: (String) -> Unit,
    onAddSongToPlaylist: (playlistId: String, songId: String) -> Unit,
    onCreatePlaylistClick: () -> Unit,
    onOpenFileExplorer: () -> Unit
) {
    var selectedTab by remember { mutableIntStateOf(0) }
    var showPlusMenu by remember { mutableStateOf(false) }
    val editablePlaylists = playlists.filterNot { isAutomaticPlaylist(it.id) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(AuraDarkBackground)
            .padding(horizontal = 20.dp)
            .testTag("library_screen")
    ) {
        Spacer(modifier = Modifier.height(16.dp))

        // Top Bar: "BIBLIOTECA" title and "+" button
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Spacer(modifier = Modifier.width(36.dp)) // for balance

            Text(
                text = "BIBLIOTECA",
                style = AuraScreenTitle,
                color = Color.White,
                textAlign = TextAlign.Center,
                modifier = Modifier.semantics { heading() }
            )

            Box {
                IconButton(
                    onClick = { showPlusMenu = true },
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(AuraPrimaryPurple)
                        .testTag("library_add_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.Add,
                        contentDescription = "Agregar",
                        tint = Color.White,
                        modifier = Modifier.size(20.dp)
                    )
                }

                DropdownMenu(
                    expanded = showPlusMenu,
                    onDismissRequest = { showPlusMenu = false },
                    modifier = Modifier.background(AuraDarkSurfaceVariant)
                ) {
                    DropdownMenuItem(
                        text = { Text("Nueva Lista de Reproducción", color = Color.White) },
                        leadingIcon = {
                            Icon(Icons.AutoMirrored.Filled.QueueMusic, contentDescription = null, tint = AuraPrimaryPurpleLight)
                        },
                        onClick = {
                            showPlusMenu = false
                            onCreatePlaylistClick()
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("Escanear Almacenamiento Local", color = Color.White) },
                        leadingIcon = {
                            Icon(Icons.Default.FolderOpen, contentDescription = null, tint = AuraPrimaryPurpleLight)
                        },
                        onClick = {
                            showPlusMenu = false
                            onOpenFileExplorer()
                        }
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Tabs: Canciones, Listas, Explorar
        TabRow(
            selectedTabIndex = selectedTab,
            containerColor = Color.Transparent,
            contentColor = AuraPrimaryPurple,
            indicator = { tabPositions ->
                TabRowDefaults.SecondaryIndicator(
                    Modifier.tabIndicatorOffset(tabPositions[selectedTab]),
                    color = AuraPrimaryPurple
                )
            },
            divider = { HorizontalDivider(color = AuraCardBorder) }
        ) {
            Tab(
                selected = selectedTab == 0,
                onClick = { selectedTab = 0 },
                text = { Text("Canciones (${songs.size})", fontWeight = FontWeight.SemiBold, fontSize = 13.sp) }
            )
            Tab(
                selected = selectedTab == 1,
                onClick = { selectedTab = 1 },
                text = { Text("Listas (${playlists.size})", fontWeight = FontWeight.SemiBold, fontSize = 13.sp) }
            )
            Tab(
                selected = selectedTab == 2,
                onClick = { selectedTab = 2 },
                text = { Text("Explorar", fontWeight = FontWeight.SemiBold, fontSize = 13.sp) }
            )
        }

        Spacer(modifier = Modifier.height(14.dp))

        when (selectedTab) {
            0 -> {
                if (songs.isEmpty()) {
                    EmptyTabState(
                        title = "Aún no hay canciones",
                        message = "Escanea tu teléfono o importa una carpeta para empezar.",
                        actionLabel = "Escanear música",
                        onAction = onOpenFileExplorer
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        contentPadding = PaddingValues(bottom = 16.dp)
                    ) {
                        items(songs, key = { it.id }) { song ->
                            SongListItem(
                                song = song,
                                playlists = editablePlaylists,
                                onClick = { onSongClick(song) },
                                onToggleFavorite = { onToggleFavorite(song.id) },
                                onAddToPlaylist = { pId -> onAddSongToPlaylist(pId, song.id) }
                            )
                        }
                    }
                }
            }
            1 -> {
                if (playlists.isEmpty()) {
                    EmptyTabState(
                        title = "Sin listas todavía",
                        message = "Crea una lista para agrupar tus pistas favoritas.",
                        actionLabel = "Nueva lista",
                        onAction = onCreatePlaylistClick
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        contentPadding = PaddingValues(bottom = 16.dp)
                    ) {
                        items(playlists, key = { it.id }) { playlist ->
                            Surface(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(14.dp))
                                    .clickable(
                                        role = Role.Button,
                                        onClickLabel = "Abrir ${playlist.name}"
                                    ) { onPlaylistClick(playlist) },
                                color = AuraDarkSurfaceVariant
                            ) {
                                Row(
                                    modifier = Modifier.padding(12.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(48.dp)
                                            .clip(RoundedCornerShape(12.dp))
                                            .background(Color(playlist.coverColorHex)),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(
                                            imageVector = Icons.AutoMirrored.Filled.QueueMusic,
                                            contentDescription = null,
                                            tint = Color.White,
                                            modifier = Modifier.size(26.dp)
                                        )
                                    }
                                    Spacer(modifier = Modifier.width(14.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = playlist.name,
                                            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                                            color = Color.White
                                        )
                                        Text(
                                            text = playlist.description.ifEmpty { "Lista personalizada" },
                                            style = MaterialTheme.typography.bodySmall,
                                            color = AuraTextSecondary
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
            2 -> {
                // Direct Folder Explorer shortcut
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(vertical = 20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Box(
                        modifier = Modifier
                            .size(72.dp)
                            .clip(CircleShape)
                            .background(AuraDarkSurfaceVariant),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Folder,
                            contentDescription = null,
                            tint = AuraPrimaryPurpleLight,
                            modifier = Modifier.size(36.dp)
                        )
                    }
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = "Gestor de Archivos del Celular",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        color = Color.White,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.semantics { heading() }
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Navega directamente por las carpetas de tu teléfono y reproduce música sin pérdida sin interrupciones.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = AuraTextSecondary,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(horizontal = 24.dp)
                    )
                    Spacer(modifier = Modifier.height(24.dp))
                    Button(
                        onClick = onOpenFileExplorer,
                        colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                            containerColor = AuraPrimaryPurple
                        )
                    ) {
                        Icon(Icons.Default.FolderOpen, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Abrir Gestor de Archivos")
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyTabState(
    title: String,
    message: String,
    actionLabel: String,
    onAction: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
            color = Color.White,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = AuraTextSecondary,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 24.dp)
        )
        Spacer(modifier = Modifier.height(20.dp))
        Button(
            onClick = onAction,
            colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                containerColor = AuraPrimaryPurple
            )
        ) {
            Text(actionLabel)
        }
    }
}

/**
 * SongListItem: fila de canción con botón de favorito y menú para añadirla a una lista.
 */
@Composable
private fun SongListItem(
    song: Song,
    playlists: List<Playlist>,
    onClick: () -> Unit,
    onToggleFavorite: () -> Unit,
    onAddToPlaylist: (String) -> Unit
) {
    var showMenu by remember { mutableStateOf(false) }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(
                role = Role.Button,
                onClickLabel = "Reproducir ${song.title}"
            ) { onClick() },
        color = AuraDarkSurfaceVariant.copy(alpha = 0.85f)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(42.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color(song.coverColorHex).copy(alpha = 0.25f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.MusicNote,
                    contentDescription = null,
                    tint = Color(song.coverColorHex),
                    modifier = Modifier.size(24.dp)
                )
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = song.title,
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = song.artist,
                        style = MaterialTheme.typography.bodySmall,
                        color = AuraTextSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (song.isLossless) {
                        Spacer(modifier = Modifier.width(6.dp))
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .background(AuraPrimaryPurple.copy(alpha = 0.25f))
                                .padding(horizontal = 4.dp, vertical = 1.dp)
                        ) {
                            Text(
                                text = song.format,
                                style = AuraBadge,
                                color = AuraPrimaryPurpleLight
                            )
                        }
                    }
                }
            }

            // IconToggleButton expone el estado marcado/no marcado a TalkBack.
            IconToggleButton(
                checked = song.isFavorite,
                onCheckedChange = { onToggleFavorite() }
            ) {
                Icon(
                    imageVector = if (song.isFavorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                    contentDescription = "Favorito",
                    tint = if (song.isFavorite) AuraAccentPink else AuraTextMuted,
                    modifier = Modifier.size(20.dp)
                )
            }

            Box {
                IconButton(onClick = { showMenu = true }) {
                    Icon(
                        imageVector = Icons.Default.MoreVert,
                        contentDescription = "Opciones de ${song.title}",
                        tint = AuraTextSecondary,
                        modifier = Modifier.size(20.dp)
                    )
                }

                DropdownMenu(
                    expanded = showMenu,
                    onDismissRequest = { showMenu = false },
                    modifier = Modifier.background(AuraDarkSurfaceVariant)
                ) {
                    if (playlists.isEmpty()) {
                        DropdownMenuItem(
                            text = { Text("No hay listas creadas", color = AuraTextSecondary) },
                            onClick = { showMenu = false }
                        )
                    } else {
                        playlists.forEach { pl ->
                            DropdownMenuItem(
                                text = { Text("Añadir a \"${pl.name}\"", color = Color.White) },
                                onClick = {
                                    showMenu = false
                                    onAddToPlaylist(pl.id)
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}
