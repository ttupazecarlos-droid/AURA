package com.example.aura.data.storage

import android.Manifest
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.database.MatrixCursor
import android.media.AudioFormat
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import androidx.test.core.app.ApplicationProvider
import com.example.aura.model.Song
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver
import org.robolectric.shadows.ShadowMediaExtractor
import org.robolectric.shadows.ShadowMediaMetadataRetriever
import org.robolectric.shadows.util.DataSource
import java.io.ByteArrayInputStream
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LocalFileManagerTest {
    private lateinit var context: Context
    private lateinit var provider: ScanDocumentsProvider
    private lateinit var manager: LocalFileManager
    private val treeUri: Uri get() = DocumentsContract.buildTreeDocumentUri(AUTHORITY, ROOT)

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        provider = ScanDocumentsProvider()
        provider.attachInfo(context, ProviderInfo().apply {
            authority = AUTHORITY
            exported = true
            grantUriPermissions = true
            readPermission = Manifest.permission.MANAGE_DOCUMENTS
            writePermission = Manifest.permission.MANAGE_DOCUMENTS
        })
        ShadowContentResolver.registerProviderInternal(AUTHORITY, provider)
        manager = LocalFileManager(context)
    }

    @Test
    fun `a null root query is not a successful empty scan`() = runBlocking {
        provider.nullQueries += ROOT
        expectScanFailure()
    }

    @Test
    fun `a null child query invalidates the entire scan`() = runBlocking {
        provider.add(ROOT, "child", "Subcarpeta", Document.MIME_TYPE_DIR)
        provider.add(ROOT, "track", "Music.flac", "audio/flac")
        provider.nullQueries += "child"
        expectScanFailure()
    }

    @Test
    fun `a denied child query invalidates the entire scan`() = runBlocking {
        provider.add(ROOT, "child", "Subcarpeta", Document.MIME_TYPE_DIR)
        provider.deniedQueries += "child"
        expectScanFailure()
    }

    @Test
    fun `a child cursor marked as loading is not a complete scan`() = runBlocking {
        provider.add(ROOT, "child", "Subcarpeta", Document.MIME_TYPE_DIR)
        provider.queryExtras["child"] = Bundle().apply { putBoolean(DocumentsContract.EXTRA_LOADING, true) }
        expectScanFailure()
    }

    @Test
    fun `a child cursor with a provider error is not a complete scan`() = runBlocking {
        provider.add(ROOT, "child", "Subcarpeta", Document.MIME_TYPE_DIR)
        provider.queryExtras["child"] = Bundle().apply { putString(DocumentsContract.EXTRA_ERROR, "Offline") }
        expectScanFailure()
    }

    @Test
    fun `a cursor missing visible names cannot silently omit audio`() = runBlocking {
        audio(name = "Music.flac", mime = "application/octet-stream")
        provider.omittedColumns += Document.COLUMN_DISPLAY_NAME
        expectScanFailure()
    }

    @Test
    fun `a cursor missing MIME types cannot silently omit extensionless audio`() = runBlocking {
        audio(name = "Opaque name", mime = "audio/flac")
        provider.omittedColumns += Document.COLUMN_MIME_TYPE
        expectScanFailure()
    }

    @Test
    fun `a directory with an empty MIME invalidates the scan instead of hiding its children`() = runBlocking {
        provider.add(ROOT, "child", "Subcarpeta", "")
        provider.add("child", "track", "Music.flac", "audio/flac")
        expectScanFailure()
    }

    @Test
    fun `a genuinely empty tree is a complete scan`() = runBlocking {
        val result = manager.scanFolderFromTree(treeUri)
        assertTrue(result.songs.isEmpty())
        assertTrue(result.discoveredUriStrings.isEmpty())
    }

    @Test
    fun `discovered audio survives metadata errors and includes nested opaque IDs`() = runBlocking {
        provider.add(ROOT, "child", "Subcarpeta", Document.MIME_TYPE_DIR)
        provider.add(ROOT, "opaque-a", "Music.FLAC", "application/octet-stream")
        provider.add("child", "opaque-b", "Sin extensión", "audio/opus")
        provider.add(ROOT, "text", "Music.lrc", "text/plain")
        listOf("opaque-a", "opaque-b").forEach {
            ShadowMediaMetadataRetriever.addException(
                source(uri(it)), IllegalArgumentException("Metadata temporarily unavailable")
            )
        }

        val result = manager.scanFolderFromTree(treeUri)

        assertTrue(result.songs.isEmpty())
        assertEquals(setOf(uri("opaque-a").toString(), uri("opaque-b").toString()), result.discoveredUriStrings)
    }

    @Test
    fun `the visible name supplies title and format instead of the opaque document ID`() = runBlocking {
        val audio = audio(name = "Luciérnaga.FLAC", mime = "application/octet-stream")

        val song = requireNotNull(manager.parseSongFromUri(audio))

        assertEquals("Luciérnaga", song.title)
        assertEquals("FLAC", song.format)
        assertTrue(song.isLossless)
        assertEquals(songIdForUri(audio), song.id)
        assertEquals(123L, song.sourceModifiedAt)
        assertEquals(100L, song.fileSizeBytes)
    }

    @Test
    fun `provider MIME identifies an extensionless audio file`() = runBlocking {
        val audio = audio(name = "Sin extensión", mime = "audio/flac")

        val song = requireNotNull(manager.parseSongFromUri(audio))

        assertEquals("FLAC", song.format)
        assertTrue(song.isLossless)
    }

    @Test
    fun `unknown audio is not silently classified as MP3 or lossless`() = runBlocking {
        val audio = audio(name = "Sin extensión", mime = "audio/x-unrecognized")

        val song = requireNotNull(manager.parseSongFromUri(audio))

        assertFalse(song.format.uppercase() in setOf("MP3", "FLAC", "WAV", "ALAC", "AAC", "M4A"))
        assertFalse(song.isLossless)
        assertEquals(0, song.sampleRate)
        assertEquals(0, song.bitDepth)
        assertEquals(0, song.bitrateKbps)
    }

    @Test
    fun `ALAC in M4A is detected from the audio track after a video track`() = runBlocking {
        val audio = audio(name = "Music.m4a", mime = "audio/mp4")
        ShadowMediaExtractor.addTrack(source(audio), MediaFormat().apply {
            setString(MediaFormat.KEY_MIME, "video/avc")
        }, byteArrayOf())
        ShadowMediaExtractor.addTrack(source(audio), MediaFormat.createAudioFormat("audio/alac", 96_000, 2).apply {
            setInteger(MediaFormat.KEY_BIT_RATE, 900_000)
        }, byteArrayOf())

        val song = requireNotNull(manager.parseSongFromUri(audio))

        assertEquals("ALAC", song.format)
        assertTrue(song.isLossless)
        assertEquals(96_000, song.sampleRate)
        assertEquals(900, song.bitrateKbps)
        assertEquals(0, song.bitDepth)
    }

    @Test
    fun `Vorbis tracks retain the supported OGG classification`() = runBlocking {
        val audio = audio(name = "Music.ogg", mime = "audio/ogg")
        ShadowMediaExtractor.addTrack(
            source(audio), MediaFormat.createAudioFormat("audio/vorbis", 48_000, 2), byteArrayOf()
        )
        val song = requireNotNull(manager.parseSongFromUri(audio))
        assertEquals("OGG", song.format)
        assertFalse(song.isLossless)
    }

    @Test
    fun `a real AAC track overrides a misleading FLAC filename`() = runBlocking {
        val audio = audio(name = "Mislabelled.flac", mime = "audio/flac")
        ShadowMediaExtractor.addTrack(
            source(audio), MediaFormat.createAudioFormat("audio/mp4a-latm", 48_000, 2), byteArrayOf()
        )

        val song = requireNotNull(manager.parseSongFromUri(audio))

        assertEquals("AAC", song.format)
        assertFalse(song.isLossless)
        assertEquals(48_000, song.sampleRate)
        assertEquals(0, song.bitDepth)
    }

    @Test
    fun `reported sampling and bitrate never imply bit depth`() = runBlocking {
        val audio = audio()
        metadata(audio, MediaMetadataRetriever.METADATA_KEY_SAMPLERATE, "192000")
        metadata(audio, MediaMetadataRetriever.METADATA_KEY_BITRATE, "2500000")

        val song = requireNotNull(manager.parseSongFromUri(audio))

        assertEquals(192_000, song.sampleRate)
        assertEquals(2500, song.bitrateKbps)
        assertEquals(0, song.bitDepth)
    }

    @Test
    fun `missing or invalid specs stay unknown even for lossless files`() = runBlocking {
        val audio = audio()
        metadata(audio, MediaMetadataRetriever.METADATA_KEY_SAMPLERATE, "-1")
        metadata(audio, MediaMetadataRetriever.METADATA_KEY_BITRATE, "invalid")

        val song = requireNotNull(manager.parseSongFromUri(audio))

        assertEquals(0, song.sampleRate)
        assertEquals(0, song.bitrateKbps)
        assertEquals(0, song.bitDepth)
    }

    @Test
    @Config(sdk = [28])
    fun `an extractor supplies real specs on Android without retriever sample rate`() = runBlocking {
        val audio = audio(name = "Music.wav", mime = "audio/wav")
        ShadowMediaExtractor.addTrack(source(audio), MediaFormat.createAudioFormat("audio/raw", 48_000, 2).apply {
            setInteger(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
        }, byteArrayOf())

        val song = requireNotNull(manager.parseSongFromUri(audio))

        assertEquals(48_000, song.sampleRate)
        assertEquals(16, song.bitDepth)
        assertEquals(0, song.bitrateKbps)
    }

    @Test
    fun `positive equal size and modification time reuse metadata but refresh URI and LRC`() = runBlocking {
        val audio = audio()
        val oldUri = DocumentsContract.buildDocumentUriUsingTree(
            DocumentsContract.buildTreeDocumentUri(AUTHORITY, "another-tree"), "track"
        )
        val known = cachedSong(audio).copy(uriString = oldUri.toString(), lyrics = "Old lyrics")
        val lrc = "[00:01.00]Letra nueva"
        lyrics(lrc.toByteArray())
        ShadowMediaMetadataRetriever.addException(source(audio), IllegalArgumentException("Must not reparse"))

        val result = manager.scanFolderFromTree(treeUri, mapOf(known.id to known))
        val song = result.songs.single()

        assertEquals(known.copy(uriString = audio.toString(), lyrics = lrc), song)
        assertEquals(setOf(audio.toString()), result.discoveredUriStrings)
    }

    @Test
    fun `cached legacy IDs are returned with their canonical document ID`() = runBlocking {
        val audio = audio()
        val canonicalId = songIdForUri(audio)
        val known = cachedSong(audio).copy(id = "saf_legacy_tree_alias")
        ShadowMediaMetadataRetriever.addException(source(audio), IllegalArgumentException("Must not reparse"))

        val result = manager.scanFolderFromTree(treeUri, mapOf(canonicalId to known))

        assertEquals(known.copy(id = canonicalId), result.songs.single())
    }

    @Test
    fun `metadata cancellation propagates instead of becoming a missing song`() = runBlocking {
        val audio = audio()
        val cancellation = CancellationException("Import cancelled")
        ShadowMediaMetadataRetriever.addException(source(audio), cancellation)

        try {
            manager.parseSongFromUri(audio)
            fail("Cancellation must propagate")
        } catch (error: CancellationException) {
            assertEquals(cancellation.message, error.message)
        }
    }

    @Test
    fun `lyrics cancellation propagates even for an unchanged cached song`() = runBlocking {
        val audio = audio()
        val known = cachedSong(audio)
        lyrics("[00:01.00]Unavailable".toByteArray())
        val cancellation = CancellationException("Scan cancelled")
        shadowOf(context.contentResolver).registerInputStream(uri("opaque-lyrics"), object : InputStream() {
            override fun read(): Int = throw cancellation
        })

        try {
            manager.scanFolderFromTree(treeUri, mapOf(known.id to known))
            fail("Cancellation must propagate")
        } catch (error: CancellationException) {
            assertEquals(cancellation.message, error.message)
        }
    }

    @Test
    fun `changed or unreliable size and modification time force a fresh parse`() = runBlocking {
        val audio = audio()
        metadata(audio, MediaMetadataRetriever.METADATA_KEY_TITLE, "Fresh metadata")
        val known = cachedSong(audio)
        val cases = listOf(
            Triple(0L, 100L, known.copy(sourceModifiedAt = 0L)),
            Triple(123L, 0L, known.copy(fileSizeBytes = 0L)),
            Triple(124L, 100L, known),
            Triple(123L, 101L, known),
            Triple(null, null, known),
            Triple(123L, 100L, known.copy(sourceModifiedAt = 0L, fileSizeBytes = 0L))
        )

        cases.forEach { (modified, size, previous) ->
            provider.documents["track"] = provider.documents.getValue("track").copy(modified = modified, size = size)

            val result = manager.scanFolderFromTree(treeUri, mapOf(previous.id to previous))

            assertEquals("Fresh metadata", result.songs.single().title)
            assertEquals(modified ?: 0L, result.songs.single().sourceModifiedAt)
            assertEquals(size ?: 0L, result.songs.single().fileSizeBytes)
        }
    }

    @Test
    fun `a scan reads sibling LRC by visible name despite opaque document IDs`() = runBlocking {
        val audio = audio()
        val text = "[00:01.00]Letra SAF"
        lyrics(text.toByteArray(), name = "Music.LRC")

        val result = manager.scanFolderFromTree(treeUri)

        assertEquals(text, result.songs.single().lyrics)
        assertEquals(audio.toString(), result.songs.single().uriString)
    }

    @Test
    fun `single imports also load sibling SAF lyrics`() = runBlocking {
        val audio = audio()
        val text = "[00:01.00]Importación suelta"
        lyrics(text.toByteArray())

        assertEquals(text, requireNotNull(manager.parseSongFromUri(audio)).lyrics)
    }

    @Test
    fun `oversized LRC is bounded by bytes even when provider size lies or is null`() = runBlocking {
        val audio = audio()
        val bytes = ("[00:01.00]" + "é".repeat(MAX_LYRICS_BYTES / 2 + 10)).toByteArray(Charsets.UTF_8)

        listOf(1L, null).forEach { reportedSize ->
            val stream = lyrics(bytes, reportedSize = reportedSize)

            assertEquals("", requireNotNull(manager.parseSongFromUri(audio)).lyrics)
            // One sentinel byte is sufficient to distinguish exactly-at-limit from oversized.
            assertTrue("Read ${stream.bytesRead} bytes", stream.bytesRead <= MAX_LYRICS_BYTES + 1)
            assertTrue(stream.closed)
        }
    }

    @Test
    fun `cached scans also bound LRC reads and remove stale lyrics`() = runBlocking {
        val audio = audio()
        val known = cachedSong(audio).copy(lyrics = "Stale lyrics")
        val stream = lyrics(ByteArray(MAX_LYRICS_BYTES + 20_000) { 'x'.code.toByte() }, reportedSize = 1)
        ShadowMediaMetadataRetriever.addException(source(audio), IllegalArgumentException("Must not reparse"))

        val result = manager.scanFolderFromTree(treeUri, mapOf(known.id to known))

        assertEquals("", result.songs.single().lyrics)
        assertTrue(stream.bytesRead <= MAX_LYRICS_BYTES + 1)
        assertTrue(stream.closed)
    }

    @Test
    fun `a valid LRC at exactly the byte limit is accepted and its stream is closed`() = runBlocking {
        val audio = audio()
        val text = "[00:01.00]" + "x".repeat(MAX_LYRICS_BYTES - "[00:01.00]".length)
        val stream = lyrics(text.toByteArray(), reportedSize = null)

        assertEquals(text, requireNotNull(manager.parseSongFromUri(audio)).lyrics)
        assertEquals(MAX_LYRICS_BYTES, stream.bytesRead)
        assertTrue(stream.closed)
    }

    @Test
    fun `unreadable lyrics do not discard valid audio metadata`() = runBlocking {
        val audio = audio()
        lyrics("[00:01.00]Unavailable".toByteArray())
        shadowOf(context.contentResolver).registerInputStream(uri("opaque-lyrics"), object : InputStream() {
            override fun read(): Int = throw IOException("Provider temporarily unavailable")
        })

        assertEquals("", requireNotNull(manager.parseSongFromUri(audio)).lyrics)
    }

    @Test
    fun `a title whose hash is Int MIN_VALUE still has a valid floorMod palette color`() = runBlocking {
        val audio = audio()
        val title = "polygenelubricants"
        assertEquals(Int.MIN_VALUE, title.hashCode())
        metadata(audio, MediaMetadataRetriever.METADATA_KEY_TITLE, title)

        val song = requireNotNull(manager.parseSongFromUri(audio))

        assertEquals(0xFF8B7DE8L, song.coverColorHex)
    }

    @Test
    fun `the same document has one ID across overlapping trees and direct document URIs`() {
        val documentId = "primary:Music/Album/Music.flac"
        val first = DocumentsContract.buildDocumentUriUsingTree(treeUri, documentId)
        val overlapping = DocumentsContract.buildDocumentUriUsingTree(
            DocumentsContract.buildTreeDocumentUri(AUTHORITY, "primary:Music/Album"), documentId
        )
        val direct = DocumentsContract.buildDocumentUri(AUTHORITY, documentId)

        assertEquals(songIdForUri(first), songIdForUri(overlapping))
        assertEquals(songIdForUri(first), songIdForUri(direct))
    }

    @Test
    fun `document identity distinguishes providers and document IDs`() {
        val first = DocumentsContract.buildDocumentUri(AUTHORITY, "opaque-1")
        val otherProvider = DocumentsContract.buildDocumentUri("other.documents", "opaque-1")
        val otherDocument = DocumentsContract.buildDocumentUri(AUTHORITY, "opaque-2")

        assertNotEquals(songIdForUri(first), songIdForUri(otherProvider))
        assertNotEquals(songIdForUri(first), songIdForUri(otherDocument))
    }

    @Test
    fun `non document identity uses the full URI including its query`() {
        val first = Uri.parse("content://media/external/audio/42?version=1")
        val second = Uri.parse("content://media/external/audio/42?version=2")
        val file = Uri.parse("file:///storage/Music/song.flac")

        assertEquals(songIdForUri(first), songIdForUri(Uri.parse(first.toString())))
        assertNotEquals(songIdForUri(first), songIdForUri(second))
        assertNotEquals(songIdForUri(first), songIdForUri(file))
    }

    private suspend fun expectScanFailure() {
        try {
            manager.scanFolderFromTree(treeUri)
            fail("An incomplete enumeration must throw, never return a complete-looking result")
        } catch (_: Exception) {
            // AssertionError from fail() is not an Exception.
        }
    }

    private fun uri(id: String): Uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, id)

    private fun source(uri: Uri): DataSource = DataSource.toDataSource(context, uri)

    private fun metadata(uri: Uri, key: Int, value: String) {
        ShadowMediaMetadataRetriever.addMetadata(source(uri), key, value)
    }

    private fun audio(name: String = "Music.flac", mime: String = "audio/flac"): Uri {
        provider.add(ROOT, "track", name, mime)
        return uri("track").also {
            metadata(it, MediaMetadataRetriever.METADATA_KEY_DURATION, "180000")
        }
    }

    private fun cachedSong(uri: Uri) = Song(
        id = songIdForUri(uri),
        title = "Cached metadata",
        artist = "Artist",
        uriString = uri.toString(),
        durationMs = 180_000,
        format = "FLAC",
        sampleRate = 48_000,
        bitDepth = 16,
        bitrateKbps = 900,
        isLossless = true,
        isFavorite = true,
        dateAdded = 7,
        sourceModifiedAt = 123,
        fileSizeBytes = 100
    )

    private fun lyrics(
        bytes: ByteArray,
        name: String = "Music.lrc",
        reportedSize: Long? = bytes.size.toLong()
    ): CountingStream {
        provider.add(ROOT, "opaque-lyrics", name, "text/plain", size = reportedSize)
        return CountingStream(bytes).also {
            shadowOf(context.contentResolver).registerInputStream(uri("opaque-lyrics"), it)
        }
    }

    private class CountingStream(bytes: ByteArray) : ByteArrayInputStream(bytes) {
        var bytesRead = 0
        var closed = false

        override fun read(): Int = super.read().also { if (it >= 0) bytesRead++ }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
            super.read(buffer, offset, length).also { if (it > 0) bytesRead += it }

        override fun close() {
            closed = true
            super.close()
        }
    }

    class ScanDocumentsProvider : ContentProvider() {
        data class Entry(
            val id: String,
            val parent: String?,
            val name: String,
            val mime: String,
            val modified: Long?,
            val size: Long?
        )

        val documents = linkedMapOf(ROOT to Entry(ROOT, null, "Music", Document.MIME_TYPE_DIR, 0, 0))
        val nullQueries = mutableSetOf<String>()
        val deniedQueries = mutableSetOf<String>()
        val queryExtras = mutableMapOf<String, Bundle>()
        val omittedColumns = mutableSetOf<String>()
        var onChildrenQuery: ((String) -> Unit)? = null

        fun add(parent: String, id: String, name: String, mime: String, modified: Long? = 123, size: Long? = 100) {
            documents[id] = Entry(id, parent, name, mime, modified, size)
        }

        override fun onCreate(): Boolean = true

        private fun queryDocument(documentId: String, projection: Array<out String>?): Cursor =
            cursor(projection, listOf(documents[documentId] ?: throw FileNotFoundException(documentId)))

        private fun queryChildDocuments(
            parentDocumentId: String,
            projection: Array<out String>?,
            sortOrder: String?
        ): Cursor? {
            onChildrenQuery?.invoke(parentDocumentId)
            if (parentDocumentId in deniedQueries) throw SecurityException("Access revoked")
            if (parentDocumentId in nullQueries) return null
            return cursor(projection, documents.values.filter { it.parent == parentDocumentId }).apply {
                extras = queryExtras[parentDocumentId] ?: Bundle.EMPTY
            }
        }

        override fun query(
            uri: Uri,
            projection: Array<out String>?,
            selection: String?,
            selectionArgs: Array<out String>?,
            sortOrder: String?
        ): Cursor? {
            val id = DocumentsContract.getDocumentId(uri)
            return if (uri.lastPathSegment == "children") queryChildDocuments(id, projection, sortOrder)
            else queryDocument(id, projection)
        }

        override fun getType(uri: Uri): String? = documents[DocumentsContract.getDocumentId(uri)]?.mime

        override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor =
            throw FileNotFoundException("No real audio fixture: $uri")

        override fun insert(uri: Uri, values: ContentValues?): Uri = throw UnsupportedOperationException()

        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int =
            throw UnsupportedOperationException()

        override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int =
            throw UnsupportedOperationException()

        private fun cursor(projection: Array<out String>?, entries: List<Entry>): Cursor {
            val columns = (projection ?: arrayOf(
                Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE,
                Document.COLUMN_LAST_MODIFIED, Document.COLUMN_SIZE
            )).filterNot { it in omittedColumns }.toTypedArray()
            return MatrixCursor(columns).apply {
                entries.forEach { entry ->
                    addRow(columns.map { column ->
                        when (column) {
                            Document.COLUMN_DOCUMENT_ID -> entry.id
                            Document.COLUMN_DISPLAY_NAME -> entry.name
                            Document.COLUMN_MIME_TYPE -> entry.mime
                            Document.COLUMN_LAST_MODIFIED -> entry.modified
                            Document.COLUMN_SIZE -> entry.size
                            Document.COLUMN_FLAGS -> 0
                            else -> null
                        }
                    }.toTypedArray<Any?>())
                }
            }
        }
    }

    companion object {
        private const val AUTHORITY = "aura.test.documents"
        private const val ROOT = "opaque-root"
        private const val MAX_LYRICS_BYTES = 512 * 1024
    }
}
