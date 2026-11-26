package org.recloud.music.downloads

import android.content.Context
import androidx.work.*
import androidx.room.withTransaction
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.Request
import org.recloud.music.ReCloud
import org.recloud.music.data.Download
import org.recloud.music.data.HeldDownload
import org.recloud.music.data.Song
import org.recloud.music.network.archiveUrl

class Offline(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result =
        withContext(Dispatchers.IO) {
            val app = applicationContext as ReCloud
            val store = app.collection
            val song =
                store.dao.song(inputData.getString("song").orEmpty())
                    ?: return@withContext Result.failure()
            if (!song.archived) return@withContext Result.failure()
            val part = File(store.audioDir, "$id.part")
            try {
                transferLock.withLock {
                    if (isPaused(app)) return@withContext Result.retry()
                    val limit =
                        (app.options.values.first()["storage"]?.toLongOrNull() ?: 512L) *
                            1024 *
                            1024
                    val bytesAvailable =
                        limit -
                            store.audioDir
                                .listFiles()
                                .orEmpty()
                                .filter {
                                    !it.name.endsWith(".part") && it != store.offline(song.id)
                                }
                                .sumOf { it.length() }
                    if (bytesAvailable <= 0) return@withContext failed("Storage limit reached")
                    val request =
                        Request.Builder()
                            .url(archiveUrl("download", song.item, song.filename))
                            .build()
                    app.client.newCall(request).execute().use { response ->
                        if (!response.isSuccessful) {
                            if (response.code >= 500) throw IOException("Service unavailable")
                            return@withContext failed(
                                "Download unavailable (HTTP ${response.code})"
                            )
                        }
                        val body = response.body ?: return@withContext failed("Empty download")
                        if (body.contentType()?.type == "text")
                            return@withContext failed(
                                "The service returned a page instead of audio."
                            )
                        val total = body.contentLength()
                        if (total > bytesAvailable)
                            return@withContext failed("Storage limit reached")
                        var count = 0L
                        var lastReport = 0L
                        part.outputStream().buffered().use { out ->
                            val input = body.byteStream()
                            val buffer = ByteArray(65536)
                            while (true) {
                                currentCoroutineContext().ensureActive()
                                val n = input.read(buffer)
                                if (n < 0) break
                                count += n
                                if (count > bytesAvailable)
                                    return@withContext failed("Storage limit reached")
                                out.write(buffer, 0, n)
                                if (count - lastReport >= 256 * 1024) {
                                    setProgress(workDataOf("bytes" to count, "total" to total))
                                    lastReport = count
                                }
                            }
                        }
                        if (count == 0L || total >= 0 && count != total)
                            throw IOException("Incomplete file")
                        queueLock.withLock {
                            currentCoroutineContext().ensureActive()
                            if (isPaused(app)) return@withContext Result.retry()
                            if (manager(app).getWorkInfoById(id).get()?.state != WorkInfo.State.RUNNING)
                                return@withContext Result.success()
                            withContext(NonCancellable) {
                                Files.move(part.toPath(), store.offline(song.id).toPath(),
                                    StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
                                store.dao.put(Download(song.id, count))
                            }
                            Result.success()
                        }
                    }
                }
            } catch (e: IOException) {
                if (runAttemptCount < 3) Result.retry()
                else failed("Transfer interrupted. Try downloading again.")
            } finally {
                part.delete()
            }
        }

    companion object {
        const val TAG = "offline"
        const val PAUSED = "offlineQueuePaused"
        private val transferLock = Mutex()
        private val queueLock = Mutex()
        private fun manager(app: ReCloud) = WorkManager.getInstance(app)
        private suspend fun isPaused(app: ReCloud) = app.options.values.first()[PAUSED] == "true"

        private suspend fun <T> editQueue(block: suspend () -> T): T =
            withContext(Dispatchers.IO) { queueLock.withLock { block() } }

        fun songId(job: WorkInfo) = job.tags.firstOrNull { it.startsWith("song=") }?.removePrefix("song=")

        private fun activeSongs(app: ReCloud): Set<String> =
            manager(app).getWorkInfosByTag(TAG).get().filterNot { it.state.isFinished }
                .mapNotNull(::songId)
                .toSet()

        private fun failed(reason: String) = Result.failure(workDataOf("error" to reason))

        suspend fun reconcile(app: ReCloud) =
            withContext(Dispatchers.IO) {
                transferLock.withLock { editQueue {
                    val entries = app.collection.dao.downloads().first().associateBy { it.song }
                    val tracks =
                        app.collection.dao.allSongs().associateBy {
                            org.recloud.music.data.digest(it.id)
                        }
                    val files = app.collection.audioDir.listFiles().orEmpty()
                    files.filter { it.name.endsWith(".part") }.forEach { it.delete() }
                    for (file in files) {
                        val song = tracks[file.name] ?: continue
                        if (entries[song.id]?.bytes != file.length())
                            app.collection.dao.put(Download(song.id, file.length()))
                    }
                    entries.keys
                        .filterNot { app.collection.offline(it).exists() }
                        .forEach { app.collection.dao.removeDownload(it) }
                    if (isPaused(app)) holdActive(app) else scheduleHeld(app)
                } }
            }

        private fun schedule(app: ReCloud, id: String) {
            val task =
                OneTimeWorkRequestBuilder<Offline>()
                    .setInputData(workDataOf("song" to id))
                    .addTag(TAG)
                    .addTag("song=$id")
                    .setConstraints(
                        Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
                    )
                    .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.SECONDS)
                    .build()
            WorkManager.getInstance(app)
                .enqueueUniqueWork("offline-$id", ExistingWorkPolicy.KEEP, task)
                .result.get()
        }

        // Held rows survive process death between recording the intent and scheduling WorkManager.
        private suspend fun scheduleHeld(app: ReCloud) {
            val dao = app.collection.dao
            val held = dao.heldSongs().first()
            if (held.isEmpty()) return
            for (id in held) {
                if (!app.collection.offline(id).isFile) schedule(app, id)
            }
            dao.releaseAllHeld()
        }

        suspend fun enqueue(app: ReCloud, songs: List<Song>): Int = editQueue {
            val store = app.collection
            val existing = activeSongs(app) + store.dao.heldSongs().first()
            val missing = songs.distinctBy { it.id }.filter {
                it.archived && it.id !in existing && !store.offline(it.id).isFile
            }
            store.db.withTransaction {
                missing.forEach { store.dao.put(it) }
                store.dao.hold(missing.map { HeldDownload(it.id) })
            }
            if (!isPaused(app)) scheduleHeld(app)
            missing.size
        }

        private suspend fun holdActive(app: ReCloud) {
            val unfinished = activeSongs(app).filterNot { app.collection.offline(it).isFile }
            app.collection.dao.hold(unfinished.map(::HeldDownload))
            manager(app).cancelAllWorkByTag(TAG).result.get()
        }

        suspend fun setPaused(app: ReCloud, paused: Boolean) = editQueue {
            app.options.set(PAUSED, paused.toString())
            if (paused) holdActive(app) else scheduleHeld(app)
        }

        suspend fun cancel(app: ReCloud, id: String) = editQueue {
            manager(app).cancelUniqueWork("offline-$id").result.get()
            app.collection.dao.releaseHeld(id)
        }

        private suspend fun deleteCopy(app: ReCloud, id: String) {
            val file = app.collection.offline(id)
            check(!file.exists() || file.delete()) { "Could not remove the offline file." }
            app.collection.dao.removeDownload(id)
        }

        suspend fun remove(app: ReCloud, id: String) = editQueue {
            manager(app).cancelUniqueWork("offline-$id").result.get()
            app.collection.dao.releaseHeld(id)
            deleteCopy(app, id)
        }

        suspend fun clear(app: ReCloud, includeCompleted: Boolean) = editQueue {
            manager(app).cancelAllWorkByTag(TAG).result.get()
            app.collection.dao.releaseAllHeld()
            if (includeCompleted)
                app.collection.dao.downloads().first().forEach { deleteCopy(app, it.song) }
        }
    }
}
