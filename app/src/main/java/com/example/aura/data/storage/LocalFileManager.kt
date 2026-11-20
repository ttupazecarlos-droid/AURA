package com.example.aura.data.storage

/**
 * ARCHIVO: LocalFileManager.kt
 * FUNCIÓN: Acceso al almacenamiento local mediante SAF: escaneo recursivo de las carpetas que el
 *          usuario autoriza, lectura de metadatos de URIs y gestión de carpetas de música.
 * NAVEGACIÓN: AuraViewModel -> LocalFileManager; la UI abre el flujo desde FileManagerSheet.
 *
 * La exploración de árbol está basada en `SafUtils.listTree` de Namida
 * (https://github.com/namidaco/namida): cola BFS, sin recursión y sin repetir directorios.
 */

import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.media.AudioFormat
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import android.util.Log
import com.example.aura.model.Song
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Locale

// El árbol concede acceso, pero no forma parte de la identidad del documento.
fun songIdForUri(uri: Uri): String {
    val documentId = if (uri.scheme == "content" && !uri.authority.isNullOrBlank()) {
        try {
            DocumentsContract.getDocumentId(uri).takeIf { it.isNotBlank() }
        } catch (_: IllegalArgumentException) {
            null
        }
    } else null
    val canonicalUri = documentId?.let { DocumentsContract.buildDocumentUri(uri.authority, it) } ?: uri
    return "saf_$canonicalUri"
}

private inline fun <T> readOptional(block: () -> T): T? = try {
    block()
} catch (error: CancellationException) {
    throw error
} catch (_: Exception) {
    null
}

// Sin permiso persistente, la carpeta importada deja de leerse tras reiniciar la app.
fun Context.takePersistableReadPermission(uri: Uri) {
    try {
        contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
    } catch (_: SecurityException) {
        // Algunos proveedores no conceden permisos persistentes; la carpeta funciona solo en esta sesión.
    }
}

// Libera el permiso persistente cuando el usuario quita una carpeta de la biblioteca.
fun Context.releasePersistableReadPermission(uri: Uri) {
    try {
        contentResolver.releasePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
    } catch (error: CancellationException) {
        throw error
    } catch (_: Exception) {
        // El proveedor pudo revocar el permiso antes; no hay nada más que liberar.
    }
}

/**
 * MusicFolder: carpeta autorizada por el usuario. El URI es un tree URI SAF y se conserva en
 * SharedPreferences para que la selección sobreviva a las actualizaciones de la app.
 */
data class MusicFolder(
    val uri: Uri,
    val displayName: String,
    val pathLabel: String,
    val hasReadPermission: Boolean = true
) {
    val uriString: String get() = uri.toString()

    // Prefijo con separador para no confundir "Música" con "Música2".
    val songUriPrefix: String get() = "$uriString/"
}

/**
 * FolderScanResult: canciones leídas y todos los URIs de audio encontrados. Los URIs permiten
 * distinguir un archivo borrado de uno que existe pero cuyos metadatos fallaron temporalmente.
 */
data class FolderScanResult(
    val songs: List<Song>,
    val discoveredUriStrings: Set<String>
)

class LocalFileManager(private val context: Context) {
    private val parserSemaphore = Semaphore(4)

    companion object {
        // Duración mínima de 1 minuto (60,000 ms) para ignorar notas de voz, tonos y sonidos cortos
        const val MIN_SONG_DURATION_MS = 60_000L
        private const val MAX_LYRICS_BYTES = 512 * 1024

        private val AUDIO_EXTENSIONS = setOf(
            "flac", "wav", "alac", "aiff", "aif", "mp3", "m4a", "m4b", "aac", "ogg", "opus", "wma", "ape"
        )
    }

    /**
     * describeFolder: obtiene el nombre visible y la ruta legible de una carpeta SAF.
     */
    fun describeFolder(treeUri: Uri): MusicFolder {
        return MusicFolder(
            uri = treeUri,
            displayName = getFolderDisplayName(treeUri),
            pathLabel = getFolderPathLabel(treeUri),
            hasReadPermission = hasPersistedReadPermission(treeUri)
        )
    }

    // Comprueba que la app conserve el permiso de lectura persistente para la carpeta.
    fun hasPersistedReadPermission(treeUri: Uri): Boolean {
        return try {
            context.contentResolver.persistedUriPermissions.any { permission ->
                permission.isReadPermission && permission.uri == treeUri
            }
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            false
        }
    }

    // Nombre visible de la carpeta raíz del árbol SAF (por ejemplo "Música").
    fun getFolderDisplayName(treeUri: Uri): String {
        val fallback = try {
            DocumentsContract.getTreeDocumentId(treeUri)
                .substringAfterLast(':')
                .substringAfterLast('/')
                .takeIf { it.isNotBlank() }
                ?: "Carpeta de música"
        } catch (_: Exception) {
            "Carpeta de música"
        }

        return try {
            val treeDocId = DocumentsContract.getTreeDocumentId(treeUri)
            val documentUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, treeDocId)
            context.contentResolver.query(
                documentUri,
                arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME),
                null,
                null,
                null
            )?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }?.takeIf { it.isNotBlank() } ?: fallback
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            fallback
        }
    }

    // Ruta legible para mostrar debajo del nombre de la carpeta.
    fun getFolderPathLabel(treeUri: Uri): String {
        return try {
            val documentId = DocumentsContract.getTreeDocumentId(treeUri)
            val volumeId = documentId.substringBefore(':', missingDelimiterValue = documentId)
            val relativePath = documentId.substringAfter(':', missingDelimiterValue = "").trim('/')
            val volumeLabel = if (volumeId.equals("primary", ignoreCase = true)) {
                "Almacenamiento interno"
            } else {
                volumeId
            }
            listOf(volumeLabel, relativePath)
                .filter { it.isNotBlank() }
                .joinToString("/")
        } catch (_: Exception) {
            treeUri.toString()
        }
    }

    /**
     * parseSongFromUri: lee una URI concreta (selector SAF o explorador) con MediaMetadataRetriever,
     * comprueba que dure más de 1 minuto y descarta notas de voz o tonos.
     */
    suspend fun parseSongFromUri(uri: Uri): Song? = withContext(Dispatchers.IO) {
        parserSemaphore.withPermit {
            parseAudioDocument(describeAudioDocument(uri))
        }
    }

    private suspend fun parseAudioDocument(document: AudioDocument): Song? {
        var retriever: MediaMetadataRetriever? = null
        try {
            currentCoroutineContext().ensureActive()
            val metadata = MediaMetadataRetriever().also { retriever = it }
            metadata.setDataSource(context, document.uri)
            val title = readMetadata(metadata, MediaMetadataRetriever.METADATA_KEY_TITLE)
                ?: document.name.substringBeforeLast('.').takeIf { it.isNotBlank() }
                ?: "Audio Sin Título"
            val artist = readMetadata(metadata, MediaMetadataRetriever.METADATA_KEY_ARTIST)
                ?: "Artista Desconocido"
            val album = readMetadata(metadata, MediaMetadataRetriever.METADATA_KEY_ALBUM)
                ?: "Álbum Local"
            val durationMs = readMetadata(metadata, MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            val titleLower = title.lowercase(Locale.ROOT)
            val uriLower = document.uri.toString().lowercase(Locale.ROOT)

            val isWhatsApp = uriLower.contains("whatsapp") || titleLower.startsWith("ptt-") || titleLower.startsWith("aud-")
            val isRingtoneOrNotif = uriLower.contains("ringtones") || uriLower.contains("notifications") || uriLower.contains("alarms")

            if (durationMs < MIN_SONG_DURATION_MS || isWhatsApp || isRingtoneOrNotif) {
                return null
            }

            val track = readAudioTrack(document.uri)
            val format = detectFormat(document, track, readMetadata(metadata, MediaMetadataRetriever.METADATA_KEY_MIMETYPE))
            val specs = extractAudioSpecs(metadata, track)
            val lyrics = readSiblingLyrics(document)
            currentCoroutineContext().ensureActive()
            return Song(
                id = songIdForUri(document.uri),
                title = title,
                artist = artist,
                album = album,
                durationMs = durationMs,
                uriString = document.uri.toString(),
                filePath = document.uri.takeIf { it.scheme == "file" }?.path,
                format = format,
                sampleRate = specs.sampleRate,
                bitDepth = specs.bitDepth,
                bitrateKbps = specs.bitrateKbps,
                isLossless = format in setOf("FLAC", "WAV", "ALAC", "AIFF", "APE", "PCM"),
                lyrics = lyrics,
                coverColorHex = getPaletteColor(title.hashCode()),
                sourceModifiedAt = document.modifiedAt,
                fileSizeBytes = document.sizeBytes
            )
        } catch (error: CancellationException) {
            throw error
        } catch (e: Exception) {
            Log.e("LocalFileManager", "Error parsing uri: ${document.uri}", e)
            return null
        } finally {
            readOptional { retriever?.release() }
        }
    }

    private fun readMetadata(retriever: MediaMetadataRetriever, key: Int): String? =
        readOptional { retriever.extractMetadata(key) }?.takeIf { it.isNotBlank() }

    private fun readAudioTrack(uri: Uri): MediaFormat? {
        var extractor: MediaExtractor? = null
        return try {
            val source = MediaExtractor().also { extractor = it }
            source.setDataSource(context, uri, null)
            (0 until source.trackCount).asSequence()
                .map { source.getTrackFormat(it) }
                .firstOrNull { it.getString(MediaFormat.KEY_MIME)?.startsWith("audio/", ignoreCase = true) == true }
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            null
        } finally {
            readOptional { extractor?.release() }
        }
    }

    private fun extractAudioSpecs(retriever: MediaMetadataRetriever, track: MediaFormat?): AudioSpecs {
        val reportedRate = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            readMetadata(retriever, MediaMetadataRetriever.METADATA_KEY_SAMPLERATE)?.toIntOrNull()?.coerceAtLeast(0) ?: 0
        } else 0
        val reportedDepth = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            readMetadata(retriever, MediaMetadataRetriever.METADATA_KEY_BITS_PER_SAMPLE)?.toIntOrNull()?.coerceAtLeast(0) ?: 0
        } else 0
        val reportedBitrate = readMetadata(retriever, MediaMetadataRetriever.METADATA_KEY_BITRATE)?.toIntOrNull()?.coerceAtLeast(0) ?: 0
        val pcmEncoding = track.intValue(MediaFormat.KEY_PCM_ENCODING)
        val pcmDepth = when (pcmEncoding) {
            AudioFormat.ENCODING_PCM_8BIT -> 8
            AudioFormat.ENCODING_PCM_16BIT -> 16
            AudioFormat.ENCODING_PCM_FLOAT -> 32
            else -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                when (pcmEncoding) {
                    AudioFormat.ENCODING_PCM_24BIT_PACKED -> 24
                    AudioFormat.ENCODING_PCM_32BIT -> 32
                    else -> 0
                }
            } else 0
        }
        return AudioSpecs(
            sampleRate = track.intValue(MediaFormat.KEY_SAMPLE_RATE).takeIf { it > 0 } ?: reportedRate,
            bitDepth = pcmDepth.takeIf { it > 0 } ?: reportedDepth,
            bitrateKbps = (track.intValue(MediaFormat.KEY_BIT_RATE).takeIf { it > 0 } ?: reportedBitrate) / 1000
        )
    }

    private fun MediaFormat?.intValue(key: String): Int =
        readOptional { this?.getInteger(key) }?.coerceAtLeast(0) ?: 0

    private data class AudioSpecs(
        val sampleRate: Int,
        val bitDepth: Int,
        val bitrateKbps: Int
    )

    /**
     * scanFolderFromTree: porta `listTree` de Namida. Recorre todo el árbol SAF con una cola BFS,
     * incluidas las subcarpetas. Nunca entrega resultados de una enumeración incompleta.
     */
    suspend fun scanFolderFromTree(
        treeUri: Uri,
        knownSongs: Map<String, Song> = emptyMap()
    ): FolderScanResult = withContext(Dispatchers.IO) {
        val documents = listAudioDocuments(treeUri)
        val songs = mutableListOf<Song>()

        documents.chunked(4).forEach { batch ->
            val parsed = coroutineScope {
                batch.map { document ->
                    async {
                        parserSemaphore.withPermit {
                            val id = songIdForUri(document.uri)
                            val known = knownSongs[id]
                            if (known != null && document.modifiedAt > 0 && document.sizeBytes > 0 &&
                                known.sourceModifiedAt == document.modifiedAt && known.fileSizeBytes == document.sizeBytes
                            ) {
                                known.copy(
                                    id = id,
                                    uriString = document.uri.toString(),
                                    filePath = document.uri.takeIf { it.scheme == "file" }?.path,
                                    lyrics = readSiblingLyrics(document)
                                )
                            } else {
                                parseAudioDocument(document)
                            }
                        }
                    }
                }.awaitAll()
            }
            songs += parsed.filterNotNull()
        }

        FolderScanResult(
            songs = songs,
            discoveredUriStrings = documents.mapTo(mutableSetOf()) { it.uri.toString() }
        )
    }

    private data class AudioDocument(
        val uri: Uri,
        val name: String,
        val mime: String = "",
        val modifiedAt: Long = 0,
        val sizeBytes: Long = 0,
        val lyricsUri: Uri? = null
    )

    private suspend fun listAudioDocuments(treeUri: Uri): List<AudioDocument> {
        val audio = linkedMapOf<Uri, AudioDocument>()
        val pending = ArrayDeque<String>()
        val visited = mutableSetOf<String>()
        pending.add(DocumentsContract.getTreeDocumentId(treeUri))

        while (pending.isNotEmpty()) {
            currentCoroutineContext().ensureActive()
            val parentId = pending.removeFirst()
            if (!visited.add(parentId)) continue
            val children = readChildren(treeUri, parentId)
            val lyrics = children.filter { it.mime != Document.MIME_TYPE_DIR && it.name.endsWith(".lrc", ignoreCase = true) }
                .associateBy { it.name.lowercase(Locale.ROOT) }
            children.forEach { document ->
                if (document.mime == Document.MIME_TYPE_DIR) {
                    pending.add(DocumentsContract.getDocumentId(document.uri))
                } else if (isAudioDocument(document.name, document.mime)) {
                    val lyricName = "${document.name.substringBeforeLast('.')}.lrc".lowercase(Locale.ROOT)
                    audio.putIfAbsent(document.uri, document.copy(lyricsUri = lyrics[lyricName]?.uri))
                }
            }
        }
        return audio.values.toList()
    }

    private suspend fun readChildren(treeUri: Uri, parentId: String): List<AudioDocument> {
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentId)
        val projection = arrayOf(
            Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE,
            Document.COLUMN_LAST_MODIFIED, Document.COLUMN_SIZE
        )
        val cursor = context.contentResolver.query(childrenUri, projection, null, null, null)
            ?: throw IllegalStateException("No se pudo enumerar la carpeta: $childrenUri")
        return cursor.use { c ->
            if (c.extras.getBoolean(DocumentsContract.EXTRA_LOADING) || c.extras.getString(DocumentsContract.EXTRA_ERROR) != null) {
                throw IllegalStateException("Enumeración incompleta: $childrenUri")
            }
            val idColumn = c.getColumnIndexOrThrow(Document.COLUMN_DOCUMENT_ID)
            val nameColumn = c.getColumnIndexOrThrow(Document.COLUMN_DISPLAY_NAME)
            val mimeColumn = c.getColumnIndexOrThrow(Document.COLUMN_MIME_TYPE)
            buildList {
                while (c.moveToNext()) {
                    currentCoroutineContext().ensureActive()
                    val id = c.getString(idColumn)?.takeIf { it.isNotBlank() }
                        ?: throw IllegalStateException("Documento sin identidad: $childrenUri")
                    // Sin MIME no se distingue una carpeta de un audio: omitirla borraría sus pistas.
                    val mime = c.getString(mimeColumn)?.takeIf { it.isNotBlank() }
                        ?: throw IllegalStateException("Documento sin tipo: $childrenUri")
                    add(AudioDocument(
                        uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, id),
                        name = c.getString(nameColumn).orEmpty(),
                        mime = mime,
                        modifiedAt = c.longValue(Document.COLUMN_LAST_MODIFIED),
                        sizeBytes = c.longValue(Document.COLUMN_SIZE)
                    ))
                }
            }
        }
    }

    private fun isAudioDocument(name: String, mime: String): Boolean {
        if (mime.startsWith("audio/", ignoreCase = true)) return true
        val extension = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
        return extension in AUDIO_EXTENSIONS
    }

    private suspend fun describeAudioDocument(uri: Uri): AudioDocument {
        val fallback = AudioDocument(uri, uri.lastPathSegment?.substringAfterLast('/').orEmpty())
        if (uri.scheme == "file") {
            val file = File(uri.path ?: return fallback)
            return fallback.copy(name = file.name, modifiedAt = file.lastModified().coerceAtLeast(0), sizeBytes = file.length().coerceAtLeast(0))
        }
        val document = readOptional {
            context.contentResolver.query(uri, null, null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    fallback.copy(
                        name = c.stringValue(Document.COLUMN_DISPLAY_NAME)?.takeIf { it.isNotBlank() } ?: fallback.name,
                        mime = c.stringValue(Document.COLUMN_MIME_TYPE).orEmpty(),
                        modifiedAt = c.longValue(Document.COLUMN_LAST_MODIFIED),
                        sizeBytes = c.longValue(Document.COLUMN_SIZE)
                    )
                } else null
            }
        } ?: fallback
        val trees = if (DocumentsContract.isTreeUri(uri)) listOf(uri) else {
            readOptional {
                context.contentResolver.persistedUriPermissions
                    .filter { it.isReadPermission && it.uri.authority == uri.authority && DocumentsContract.isTreeUri(it.uri) }
                    .map { it.uri }
            }.orEmpty()
        }
        for (tree in trees) {
            val match = readOptional {
                listAudioDocuments(tree).firstOrNull { songIdForUri(it.uri) == songIdForUri(uri) }
            }
            if (match != null) return match.copy(uri = uri)
        }
        // shortcut: sin acceso al árbol no se pueden localizar hermanos SAF; requiere autorizar su carpeta.
        return document.copy(mime = document.mime.ifBlank { readOptional { context.contentResolver.getType(uri) }.orEmpty() })
    }

    private fun Cursor.stringValue(column: String): String? =
        getColumnIndex(column).takeIf { it >= 0 }?.let { if (isNull(it)) null else getString(it) }

    private fun Cursor.longValue(column: String): Long =
        getColumnIndex(column).takeIf { it >= 0 }?.let { index ->
            if (isNull(index)) 0 else readOptional { getLong(index) }?.coerceAtLeast(0)
        } ?: 0

    private suspend fun readSiblingLyrics(document: AudioDocument): String {
        val uri = document.lyricsUri ?: if (document.uri.scheme == "file") {
            val audio = File(document.uri.path ?: return "")
            listOf(File(audio.parentFile, "${audio.nameWithoutExtension}.lrc"), File(audio.parentFile, "${audio.nameWithoutExtension}.LRC"))
                .firstOrNull { it.isFile && it.canRead() }?.let { Uri.fromFile(it) }
        } else null
        if (uri == null) return ""
        return readOptional {
            val stream = if (uri.scheme == "file") File(uri.path ?: return "").inputStream() else context.contentResolver.openInputStream(uri)
            stream?.use { input ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (output.size() <= MAX_LYRICS_BYTES) {
                    currentCoroutineContext().ensureActive()
                    val count = input.read(buffer, 0, minOf(buffer.size, MAX_LYRICS_BYTES + 1 - output.size()))
                    if (count < 0) break
                    if (count == 0) {
                        val byte = input.read()
                        if (byte < 0) break
                        output.write(byte)
                    } else {
                        output.write(buffer, 0, count)
                    }
                }
                if (output.size() > MAX_LYRICS_BYTES) "" else {
                    output.toString(Charsets.UTF_8.name()).removePrefix("\uFEFF").takeIf { it.isNotBlank() }.orEmpty()
                }
            }
        }.orEmpty()
    }

    private fun detectFormat(document: AudioDocument, track: MediaFormat?, metadataMime: String?): String {
        val extension = document.name.substringAfterLast('.', "").lowercase(Locale.ROOT)
        val namedFormat = when (extension) {
            "m4a", "m4b" -> "M4A"
            "aif", "aiff" -> "AIFF"
            in AUDIO_EXTENSIONS -> extension.uppercase(Locale.ROOT)
            else -> null
        }
        val trackMime = readOptional { track?.getString(MediaFormat.KEY_MIME) }?.lowercase(Locale.ROOT)
        if (trackMime == "audio/raw") return namedFormat?.takeIf { it == "WAV" || it == "AIFF" } ?: "PCM"
        if (!trackMime.isNullOrBlank()) return formatForMime(trackMime) ?: "Desconocido"
        return formatForMime(metadataMime) ?: namedFormat ?: formatForMime(document.mime) ?: "Desconocido"
    }

    private fun formatForMime(mime: String?): String? = when (mime?.substringBefore(';')?.trim()?.lowercase(Locale.ROOT)) {
        "audio/flac", "audio/x-flac" -> "FLAC"
        "audio/wav", "audio/x-wav", "audio/wave", "audio/vnd.wave" -> "WAV"
        "audio/alac", "audio/x-alac" -> "ALAC"
        "audio/aiff", "audio/x-aiff" -> "AIFF"
        "audio/mpeg", "audio/mp3", "audio/x-mp3" -> "MP3"
        "audio/mp4a-latm", "audio/aac", "audio/aac-adts", "audio/x-aac" -> "AAC"
        "audio/mp4", "audio/x-m4a" -> "M4A"
        "audio/ogg", "audio/vorbis", "application/ogg" -> "OGG"
        "audio/opus" -> "OPUS"
        "audio/x-ms-wma", "audio/wma" -> "WMA"
        "audio/ape", "audio/x-ape", "audio/x-monkeys-audio" -> "APE"
        "audio/raw" -> "PCM"
        else -> null
    }

    private fun getPaletteColor(seed: Int): Long {
        val palette = listOf(
            0xFF9155FD, 0xFF5B78EE, 0xFF2EC4B6, 0xFFE85D9E, 0xFF8B7DE8, 0xFFE65D79
        )
        val index = Math.floorMod(seed, palette.size)
        return palette[index]
    }
}
