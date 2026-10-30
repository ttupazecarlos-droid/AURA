package com.example.aura.data.storage

import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.util.Log
import com.example.aura.model.Song
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

// Sin permiso persistente, la pista importada deja de sonar tras reiniciar la app.
fun Context.takePersistableReadPermission(uri: Uri) {
    try {
        contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
    } catch (_: SecurityException) {
        // Algunos proveedores no conceden permisos persistentes; la pista funciona solo en esta sesión.
    }
}

class LocalFileManager(private val context: Context) {

    /**
     * Scans Android MediaStore for local audio files.
     */
    suspend fun scanMediaStore(): List<Song> = withContext(Dispatchers.IO) {
        val songList = mutableListOf<Song>()
        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.DATA,
            MediaStore.Audio.Media.MIME_TYPE,
            MediaStore.Audio.Media.DATE_ADDED
        )

        val selection = "${MediaStore.Audio.Media.IS_MUSIC} != 0"
        val sortOrder = "${MediaStore.Audio.Media.DATE_ADDED} DESC"

        try {
            val cursor = context.contentResolver.query(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                projection,
                selection,
                null,
                sortOrder
            )

            cursor?.use { c ->
                val idCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                val titleCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
                val artistCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
                val albumCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
                val durCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
                val dataCol = c.getColumnIndex(MediaStore.Audio.Media.DATA)
                val mimeCol = c.getColumnIndex(MediaStore.Audio.Media.MIME_TYPE)

                while (c.moveToNext()) {
                    val id = c.getLong(idCol)
                    val title = c.getString(titleCol) ?: "Pista $id"
                    val artist = c.getString(artistCol).takeIf { it != "<unknown>" } ?: "Artista Desconocido"
                    val album = c.getString(albumCol) ?: "Álbum"
                    val duration = c.getLong(durCol)
                    val path = if (dataCol >= 0) c.getString(dataCol) else null
                    val mime = if (mimeCol >= 0) c.getString(mimeCol) ?: "" else ""

                    val contentUri = ContentUris.withAppendedId(
                        MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                        id
                    )

                    val format = detectFormat(path, mime)
                    val isLossless = isFormatLossless(format)
                    val specs = extractMetadataForUri(contentUri, isLossless)

                    songList.add(
                        Song(
                            id = "media_$id",
                            title = title,
                            artist = artist,
                            album = album,
                            durationMs = duration,
                            uriString = contentUri.toString(),
                            filePath = path,
                            format = format,
                            sampleRate = specs.sampleRate,
                            bitDepth = specs.bitDepth,
                            bitrateKbps = specs.bitrateKbps,
                            isLossless = isLossless,
                            lyrics = readSiblingLyrics(path),
                            coverColorHex = getPaletteColor(title.hashCode())
                        )
                    )
                }
            }
        } catch (e: Exception) {
            Log.e("LocalFileManager", "Error scanning MediaStore", e)
        }

        songList
    }

    /**
     * Parse audio file from a specific picked Uri (SAF).
     */
    suspend fun parseSongFromUri(uri: Uri): Song? = withContext(Dispatchers.IO) {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(context, uri)
            val title = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)
                ?: uri.lastPathSegment?.substringAfterLast('/')?.substringBeforeLast('.')
                ?: "Audio Sin Título"
            val artist = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)
                ?: "Artista Desconocido"
            val album = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM)
                ?: "Álbum Local"
            val durationStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
            val durationMs = durationStr?.toLongOrNull() ?: 0L

            val fileName = uri.lastPathSegment ?: ""
            val format = when {
                fileName.endsWith(".flac", ignoreCase = true) -> "FLAC"
                fileName.endsWith(".wav", ignoreCase = true) -> "WAV"
                fileName.endsWith(".alac", ignoreCase = true) -> "ALAC"
                fileName.endsWith(".m4a", ignoreCase = true) -> "M4A"
                fileName.endsWith(".ogg", ignoreCase = true) -> "OGG"
                fileName.endsWith(".aac", ignoreCase = true) -> "AAC"
                else -> "MP3"
            }
            val isLossless = isFormatLossless(format)
            val specs = extractAudioSpecs(retriever, isLossless)
            val localPath = if (uri.scheme == "file") uri.path else null

            Song(
                // Id determinista: reimportar el mismo archivo no crea duplicados.
                id = "saf_$uri",
                title = title,
                artist = artist,
                album = album,
                durationMs = durationMs,
                uriString = uri.toString(),
                filePath = localPath,
                format = format,
                sampleRate = specs.sampleRate,
                bitDepth = specs.bitDepth,
                bitrateKbps = specs.bitrateKbps,
                isLossless = isLossless,
                lyrics = readSiblingLyrics(localPath),
                coverColorHex = getPaletteColor(title.hashCode())
            )
        } catch (e: Exception) {
            Log.e("LocalFileManager", "Error parsing uri: $uri", e)
            null
        } finally {
            try {
                retriever.release()
            } catch (_: Exception) {}
        }
    }

    private fun extractMetadataForUri(uri: Uri, isLossless: Boolean): AudioSpecs {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, uri)
            extractAudioSpecs(retriever, isLossless)
        } catch (_: Exception) {
            AudioSpecs(
                sampleRate = 44100,
                bitDepth = 16,
                bitrateKbps = if (isLossless) 1411 else 320
            )
        } finally {
            try { retriever.release() } catch (_: Exception) {}
        }
    }

    private fun extractAudioSpecs(
        retriever: MediaMetadataRetriever,
        isLossless: Boolean
    ): AudioSpecs {
        val sampleRateStr = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            try {
                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_SAMPLERATE)
            } catch (_: Exception) { null }
        } else null

        val bitrateStr = try {
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE)
        } catch (_: Exception) { null }

        val sampleRate = sampleRateStr?.toIntOrNull() ?: 44100
        val rawBitrate = bitrateStr?.toIntOrNull()
        val bitrateKbps = if (rawBitrate != null && rawBitrate > 0) rawBitrate / 1000 else if (isLossless) 1411 else 320
        val bitDepth = if (sampleRate >= 88200) 24 else 16

        return AudioSpecs(
            sampleRate = sampleRate,
            bitDepth = bitDepth,
            bitrateKbps = bitrateKbps
        )
    }

    private data class AudioSpecs(
        val sampleRate: Int,
        val bitDepth: Int,
        val bitrateKbps: Int
    )

    /**
     * Scan folder picked via SAF (OpenDocumentTree) using DocumentsContract.
     */
    suspend fun scanFolderFromTree(treeUri: Uri): List<Song> = withContext(Dispatchers.IO) {
        val results = mutableListOf<Song>()
        try {
            val treeDocId = DocumentsContract.getTreeDocumentId(treeUri)
            val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, treeDocId)
            val projection = arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE
            )

            context.contentResolver.query(childrenUri, projection, null, null, null)?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                val nameCol = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                val mimeCol = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE)

                while (cursor.moveToNext()) {
                    val docId = cursor.getString(idCol)
                    val name = cursor.getString(nameCol) ?: ""
                    val mime = cursor.getString(mimeCol) ?: ""
                    val docUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)

                    val isAudio = mime.startsWith("audio/") ||
                            name.endsWith(".flac", ignoreCase = true) ||
                            name.endsWith(".wav", ignoreCase = true) ||
                            name.endsWith(".alac", ignoreCase = true) ||
                            name.endsWith(".mp3", ignoreCase = true) ||
                            name.endsWith(".m4a", ignoreCase = true) ||
                            name.endsWith(".ogg", ignoreCase = true)

                    if (isAudio) {
                        parseSongFromUri(docUri)?.let { results.add(it) }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("LocalFileManager", "Error scanning folder tree", e)
        }
        results
    }

    /**
     * Lists physical device directories for integrated file explorer.
     */
    fun listDirectory(path: String?): List<FileItem> {
        val dir = if (path.isNullOrEmpty()) {
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC)
                .takeIf { it.exists() }
                ?: Environment.getExternalStorageDirectory()
        } else {
            File(path)
        }

        if (!dir.exists() || !dir.isDirectory) return emptyList()

        val files = dir.listFiles() ?: return emptyList()
        return files.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
            .map { file ->
                val isLossless = file.extension.lowercase() in listOf("flac", "wav", "alac", "aiff")
                val isAudio = isLossless || file.extension.lowercase() in listOf("mp3", "m4a", "ogg", "aac")
                FileItem(
                    name = file.name,
                    absolutePath = file.absolutePath,
                    isDirectory = file.isDirectory,
                    isAudio = isAudio,
                    isLossless = isLossless,
                    sizeBytes = file.length()
                )
            }
    }

    private fun readSiblingLyrics(audioPath: String?): String {
        if (audioPath.isNullOrEmpty()) return ""
        return try {
            val audio = File(audioPath)
            val dir = audio.parentFile ?: return ""
            val base = audio.nameWithoutExtension
            listOf(File(dir, "$base.lrc"), File(dir, "$base.LRC"))
                .firstOrNull { it.isFile && it.canRead() && it.length() in 1..(512 * 1024) }
                ?.readText()
                ?.takeIf { it.isNotBlank() }
                ?: ""
        } catch (_: Exception) {
            ""
        }
    }

    private fun detectFormat(path: String?, mime: String): String {
        val ext = path?.substringAfterLast('.', "")?.uppercase() ?: ""
        return when {
            ext in listOf("FLAC", "WAV", "ALAC", "AIFF", "MP3", "AAC", "OGG", "M4A") -> ext
            mime.contains("flac") -> "FLAC"
            mime.contains("wav") -> "WAV"
            mime.contains("alac") -> "ALAC"
            mime.contains("mp4") || mime.contains("m4a") -> "M4A"
            mime.contains("ogg") -> "OGG"
            else -> "MP3"
        }
    }

    private fun isFormatLossless(format: String): Boolean {
        return format.uppercase() in listOf("FLAC", "WAV", "ALAC", "AIFF")
    }

    private fun getPaletteColor(seed: Int): Long {
        val palette = listOf(
            0xFF9155FD, 0xFF5B78EE, 0xFF2EC4B6, 0xFFE85D9E, 0xFF8B7DE8, 0xFFE65D79
        )
        val index = kotlin.math.abs(seed) % palette.size
        return palette[index]
    }
}

data class FileItem(
    val name: String,
    val absolutePath: String,
    val isDirectory: Boolean,
    val isAudio: Boolean,
    val isLossless: Boolean,
    val sizeBytes: Long
)
