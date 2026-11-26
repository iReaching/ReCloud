package org.recloud.music

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import coil.imageLoader
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.recloud.music.data.*
import org.recloud.music.downloads.Offline

@RunWith(AndroidJUnit4::class)
class OfflinePlaylistTest {
    private val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as ReCloud

    private fun recording(name: String): Song {
        val item = "recloud-offline-test"
        return Song(archiveId(item, name), name, item = item, filename = name,
            license = "https://creativecommons.org/licenses/by/4.0/")
    }

    @Test
    fun pauseResumeAndClearKeepCompletedAudioAndPlaylistArtwork() = runBlocking {
        val store = app.collection
        val done = recording("completed.mp3")
        val pending = recording("pending.mp3")
        val legacy = recording("legacy.mp3")
        val tracks = listOf(done, pending, pending, Song("local-fixture", "Device file"),
            Song("yt-01234567890", "Video"))
        val mix = Mix("offline-cover-test", "Offline playlist", codec.encodeToString(tracks.map { it.id }), 1)
        val source = File(app.cacheDir, "playlist-cover-test.png")
        val manager = WorkManager.getInstance(app)
        try {
            tracks.forEach { store.dao.put(it) }
            store.dao.put(legacy)
            store.dao.put(mix)
            val bitmap = Bitmap.createBitmap(1024, 768, Bitmap.Config.ARGB_8888)
            bitmap.eraseColor(android.graphics.Color.RED)
            source.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
            store.setPlaylistCover(mix.id, Uri.fromFile(source))
            source.delete()
            app.imageLoader.memoryCache?.clear()
            store.offline(done.id).writeBytes(byteArrayOf(1, 2, 3))
            store.dao.put(Download(done.id, 3))

            // Adopt an unfinished job created by the original single-track downloader.
            val oldJob = OneTimeWorkRequestBuilder<Offline>().addTag(Offline.TAG)
                .addTag("song=${legacy.id}").setInputData(workDataOf("song" to legacy.id))
                .setInitialDelay(1, TimeUnit.DAYS).build()
            manager.enqueueUniqueWork("offline-${legacy.id}", ExistingWorkPolicy.KEEP, oldJob).result.get()
            Offline.setPaused(app, true)
            assertEquals(1, Offline.enqueue(app, tracks))
            assertEquals(0, Offline.enqueue(app, tracks))
            Offline.reconcile(app)
            assertEquals(setOf(legacy.id, pending.id), store.dao.heldSongs().first().toSet())
            Offline.cancel(app, legacy.id)
            Offline.setPaused(app, false)
            assertTrue(store.dao.heldSongs().first().isEmpty())
            assertTrue(manager.getWorkInfosForUniqueWork("offline-${pending.id}").get().isNotEmpty())
            Offline.clear(app, includeCompleted = false)
            Offline.reconcile(app)
            assertArrayEquals(byteArrayOf(1, 2, 3), store.offline(done.id).readBytes())
            assertTrue(store.dao.downloads().first().contains(Download(done.id, 3)))
            assertEquals(mix.members, store.dao.mix(mix.id)?.members)
            val jpeg = requireNotNull(store.dao.cover(mix.id).first())
            val cover = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size)
            assertEquals(512, cover.width)
            assertEquals(384, cover.height)
            cover.recycle()
            Offline.remove(app, done.id)
            assertFalse(store.offline(done.id).exists())
            assertEquals(mix.members, store.dao.mix(mix.id)?.members)
            store.changeMix(mix.id) { it.copy(deleted = true) }
            assertNull(store.dao.cover(mix.id).first())
        } finally {
            Offline.clear(app, includeCompleted = false)
            Offline.setPaused(app, false)
            Offline.remove(app, done.id)
            source.delete()
        }
    }
}
