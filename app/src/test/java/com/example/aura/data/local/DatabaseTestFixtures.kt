package com.example.aura.data.local

internal fun testSong(
    id: String = "song_1",
    isFavorite: Boolean = false,
    dateAdded: Long = 123L
) = SongEntity(
    id = id,
    title = "Título $id",
    artist = "Artista",
    album = "Álbum",
    durationMs = 180_000L,
    uriString = "content://provider/document/$id",
    filePath = null,
    format = "FLAC",
    sampleRate = 44_100,
    bitDepth = 16,
    bitrateKbps = 900,
    isLossless = true,
    isFavorite = isFavorite,
    lyrics = "[00:01.00]Letra",
    coverColorHex = 0xFF9155FD,
    dateAdded = dateAdded
)

internal fun testPlaylist(id: String = "playlist_1") = PlaylistEntity(
    id = id,
    name = "Lista $id",
    description = "Descripción conservada",
    coverColorHex = 0xFF9155FD,
    createdAt = 321L
)
