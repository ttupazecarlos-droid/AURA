package com.example.aura.data.storage

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowMediaMetadataRetriever
import org.robolectric.shadows.util.DataSource
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], shadows = [LocalFileManagerConcurrencyTest.BlockingMetadataRetriever::class])
class LocalFileManagerConcurrencyTest {
    @Test
    fun `concurrent single imports share the same four parser limit`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val manager = LocalFileManager(context)
        BlockingMetadataRetriever.resetGate()
        val uris = (1..12).map { Uri.parse("content://imports/audio/$it.flac") }
        uris.forEach {
            ShadowMediaMetadataRetriever.addMetadata(
                DataSource.toDataSource(context, it), MediaMetadataRetriever.METADATA_KEY_DURATION, "180000"
            )
        }
        val imports = uris.map { async(Dispatchers.IO) { manager.parseSongFromUri(it) } }

        try {
            assertTrue("Four independent imports should start", BlockingMetadataRetriever.firstFour.await(5, TimeUnit.SECONDS))
            assertFalse(
                "A fifth metadata parser entered while four were blocked",
                BlockingMetadataRetriever.fifth.await(300, TimeUnit.MILLISECONDS)
            )
        } finally {
            BlockingMetadataRetriever.release.countDown()
        }

        assertEquals(12, imports.awaitAll().filterNotNull().size)
        assertEquals(4, BlockingMetadataRetriever.maximum.get())
    }

    @Implements(MediaMetadataRetriever::class)
    class BlockingMetadataRetriever : ShadowMediaMetadataRetriever() {
        @Implementation
        public override fun setDataSource(context: Context, uri: Uri) {
            val active = current.incrementAndGet()
            maximum.accumulateAndGet(active, ::maxOf)
            firstFour.countDown()
            if (active > 4) fifth.countDown()
            try {
                check(release.await(10, TimeUnit.SECONDS)) { "Timed out waiting for test release" }
                super.setDataSource(context, uri)
            } finally {
                current.decrementAndGet()
            }
        }

        companion object {
            var firstFour = CountDownLatch(4)
            var fifth = CountDownLatch(1)
            var release = CountDownLatch(1)
            val current = AtomicInteger()
            val maximum = AtomicInteger()

            fun resetGate() {
                firstFour = CountDownLatch(4)
                fifth = CountDownLatch(1)
                release = CountDownLatch(1)
                current.set(0)
                maximum.set(0)
            }
        }
    }
}
