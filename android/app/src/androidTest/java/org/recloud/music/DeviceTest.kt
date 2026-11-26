package org.recloud.music

import android.content.ComponentName
import android.net.Uri
import androidx.lifecycle.ViewModelProvider
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.room.Room
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.nio.*
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.recloud.music.auth.EncryptedPreference
import org.recloud.music.data.*
import org.recloud.music.database.Library
import org.recloud.music.playback.MusicService
import org.recloud.music.playback.media
import org.recloud.music.repository.Collection
import org.recloud.music.settings.Options
import org.recloud.music.viewmodel.MusicModel
import org.recloud.music.ui.queueRows

@RunWith(AndroidJUnit4::class)
class DeviceTest {
    private val instrument = InstrumentationRegistry.getInstrumentation()
    private val app = instrument.targetContext.applicationContext as ReCloud

    @Test
    fun selectingDuplicateInMixedPlaylistKeepsItsVideoQueuePosition() {
        val repeated = Song("yt-01234567890", "Repeated")
        val other = Song("yt-09876543210", "Other")
        val songs = listOf(repeated, Song("local-example", "Local"), other, repeated)
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val model = ViewModelProvider(activity)[MusicModel::class.java]
                model.play(songs, at = 3)
                assertEquals(listOf("Repeated", "Other", "Repeated"), model.videoOrder.map { model.videoQueue.getValue(it).title })
                assertEquals(2, model.videoPosition)
                val session = model.showVideo
                model.updateVideoPosition(session, 1)
                assertEquals("Other", model.video?.title)
                model.play(listOf(repeated, other), shuffle = true)
                val start = model.videoPosition
                model.updateVideoPosition(session, 1 - start)
                assertEquals(start, model.videoPosition)
                assertTrue(model.videoShuffled)
            }
        }
    }

    @Test
    fun queueEditsAreIndependentOfThePlaylistAndPreserveDuplicateOccurrences() {
        val song = Song("yt-01234567890", "Repeated")
        val other = Song("yt-09876543210", "Other")
        val playlist = listOf(song, other, song)
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity {
                val model = ViewModelProvider(it)[MusicModel::class.java]
                model.play(playlist, 2)
                model.moveVideo(2, 0)
                assertEquals(2, model.videoPosition)
                assertEquals(listOf(2, 0, 1), model.videoOrder)
                model.toggleVideoShuffle()
                val shuffled = model.videoOrder
                model.moveVideo(2, 1)
                assertEquals(shuffled, model.videoOrder)
                model.removeVideo(0)
                model.addToQueue(song, false)
                model.toggleVideoShuffle()
                assertEquals(listOf(2, 1, 3), model.videoOrder)
                assertEquals(2, model.videoPosition)
                model.removeVideo(2)
                assertEquals(1, model.videoPosition)
                model.removeVideo(1)
                model.removeVideo(3)
                assertNull(model.video)
                assertTrue(model.videoOrder.isEmpty())
                assertEquals(listOf(song, other, song), playlist)
            }
        }
    }

    @Test
    fun videoHistoryAddsNewMetadataAndPreservesSavedTags() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(app, Library::class.java).build()
        try {
            val store = Collection(app, db)
            val video = Song("yt-01234567890", "First listen", artist = "Artist")
            store.listened(video.id, 0, true, video)
            assertEquals(video, store.dao.song(video.id))
            store.dao.put(video.copy(album = "Saved album"))
            store.listened(video.id, 1_500, false, video)
            val row = store.dao.stats().first().single()
            assertEquals(1L, row.starts)
            assertEquals(1_500L, row.millis)
            assertEquals("Saved album", store.dao.song(video.id)?.album)
        } finally {
            db.close()
        }
    }

    @Test
    fun playlistChangesAndRepeatedSyncAreTransactional() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(app, Library::class.java).build()
        try {
            val store = Collection(app, db)
            val song = Song("local-" + digest("fixture"), "Track")
            store.changeMix(null, "First")
            val mix = store.dao.allMixes().single()
            store.member(mix.id, song, true)
            store.member(mix.id, song, true)
            store.changeMix(mix.id) { it.copy(name = "Renamed") }
            store.favorite(song)
            store.listened(song.id, 2800, true)
            val packet = store.snapshot()
            assertEquals(listOf(song.id), packet.mixes.single().ids())
            store.merge(packet)
            store.merge(packet)
            assertEquals(packet, store.snapshot())
            store.member(mix.id, song, false)
            store.changeMix(mix.id) { it.copy(deleted = true) }
            store.merge(packet)
            assertTrue(store.dao.mixes().first().isEmpty())
            val bad = packet.copy(stars = listOf(Star("missing", true, 100)))
            assertTrue(runCatching { store.merge(bad) }.isFailure)
        } finally {
            db.close()
        }
    }

    @Test
    fun legacyPlaylistRoundTripUsesOriginalVideoIds() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(app, Library::class.java).build()
        try {
            val store = Collection(app, db)
            val json =
                """[{"id":"library1","name":"Songs","songs":[{"videoId":"01234567890","title":"Example","duration":9.0}]}]"""
            repeat(2) {
                org.recloud.music.sync.LegacyPlaylists.import(json.byteInputStream(), store)
            }
            val out = java.io.ByteArrayOutputStream()
            assertEquals(1, org.recloud.music.sync.LegacyPlaylists.export(out, store))
            assertEquals(
                "01234567890",
                codec
                    .decodeFromString<List<org.recloud.music.sync.LegacyPlaylist>>(
                        out.toString("UTF-8")
                    )
                    .single()
                    .songs
                    .single()
                    .videoId,
            )
        } finally {
            db.close()
        }
    }

    @Test
    fun pairedPeersConfirmBeforeExchangingLibraries() = runBlocking {
        val dbA = Room.inMemoryDatabaseBuilder(app, Library::class.java).build()
        val dbB = Room.inMemoryDatabaseBuilder(app, Library::class.java).build()
        val a = Collection(app, dbA)
        val b = Collection(app, dbB)
        val host = org.recloud.music.sync.PeerSync(app, a)
        val guest = org.recloud.music.sync.PeerSync(app, b)
        try {
            if (android.os.Build.VERSION.SDK_INT >= 37)
                instrument.uiAutomation.grantRuntimePermission(
                    app.packageName,
                    "android.permission.ACCESS_LOCAL_NETWORK",
                )
            a.changeMix(null, "Host list")
            b.changeMix(null, "Guest list")
            withContext(Dispatchers.Main) { host.host() }
            withTimeout(15000) { host.state.first { it.status.startsWith("Choose") || !it.active } }
            withContext(Dispatchers.Main) { guest.discover() }
            val peer =
                withTimeout(20000) { guest.state.first { it.peers.isNotEmpty() }.peers.first() }
            withContext(Dispatchers.Main) { guest.join(peer) }
            val first =
                withTimeout(20000) { host.state.first { it.code.isNotEmpty() || !it.active } }
            val second =
                withTimeout(20000) { guest.state.first { it.code.isNotEmpty() || !it.active } }
            assertTrue(first.status + " / " + second.status, first.code.isNotEmpty())
            assertEquals(second.status, first.code, second.code)
            host.confirm()
            assertEquals(1, a.dao.allMixes().size)
            assertEquals(1, b.dao.allMixes().size)
            guest.confirm()
            val done = withTimeout(15000) { host.state.first { !it.active } }
            assertEquals("Libraries synchronized.", done.status)
            assertEquals(a.dao.allMixes().toSet(), b.dao.allMixes().toSet())
            assertEquals(2, b.dao.allMixes().size)
        } finally {
            withContext(Dispatchers.Main) {
                host.close()
                guest.close()
            }
            dbA.close()
            dbB.close()
        }
    }

    @Test
    fun optionsAndEncryptedKeySurviveNewInstances() = runBlocking {
        val old = Options(app).values.first()["theme"] ?: "Dark"
        try {
            Options(app).set("theme", "Light")
            assertEquals("Light", Options(app).values.first()["theme"])
        } finally {
            Options(app).set("theme", old)
        }
        val keys = EncryptedPreference.apiKey(app)
        val previous = keys.read()
        try {
            keys.save("instrumentation-test")
            assertEquals("instrumentation-test", EncryptedPreference.apiKey(app).read())
        } finally {
            keys.save(previous)
        }
    }

    @Test
    fun coldLaunchRemembersAccountAndExplicitSignOutSurvivesRelaunch() {
        val account = EncryptedPreference.googleAccount(app)
        val previous = account.read()
        val key = EncryptedPreference.apiKey(app).read()
        try {
            account.save("listener@example.invalid")
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity {
                    val model = ViewModelProvider(it)[MusicModel::class.java]
                    assertTrue(model.signedIn)
                    model.signOut()
                    assertFalse(model.signedIn)
                }
            }
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity { assertFalse(ViewModelProvider(it)[MusicModel::class.java].signedIn) }
            }
            assertEquals("", EncryptedPreference.googleAccount(app).read())
            assertEquals(key, EncryptedPreference.apiKey(app).read())
        } finally {
            account.save(previous)
        }
    }

    @Test
    fun sessionPlaysSeeksShufflesRepeatsAndReordersLocalAudio() = runBlocking {
        val data = ByteArray(8000 * 2 * 15)
        val header =
            ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
                put("RIFF".toByteArray())
                putInt(36 + data.size)
                put("WAVEfmt ".toByteArray())
                putInt(16)
                putShort(1)
                putShort(1)
                putInt(8000)
                putInt(16000)
                putShort(2)
                putShort(16)
                put("data".toByteArray())
                putInt(data.size)
            }
        val file =
            File(app.cacheDir, "transport-test.wav").apply {
                outputStream().use {
                    it.write(header.array())
                    it.write(data)
                }
            }
        val song = Song("local-" + digest("transport-fixture"), "Transport fixture")
        app.collection.dao.put(song)
        app.collection.dao.put(LocalSource(song.id, Uri.fromFile(file).toString()))
        val future =
            withContext(Dispatchers.Main) {
                MediaController.Builder(
                        app,
                        SessionToken(app, ComponentName(app, MusicService::class.java)),
                    )
                    .buildAsync()
            }
        val scenario = androidx.test.core.app.ActivityScenario.launch(MainActivity::class.java)
        val player = future.get(15, TimeUnit.SECONDS)
        try {
            withContext(Dispatchers.Main) {
                player.setMediaItems(listOf(song.media(), song.media()))
                player.prepare()
                player.play()
                player.shuffleModeEnabled = true
                player.repeatMode = Player.REPEAT_MODE_ALL
            }
            withTimeout(15000) {
                while (
                    !withContext(Dispatchers.Main) {
                        player.isPlaying && player.currentPosition > 100
                    }
                ) delay(100)
            }
            scenario.moveToState(androidx.lifecycle.Lifecycle.State.CREATED)
            val backgroundPosition = withContext(Dispatchers.Main) { player.currentPosition }
            withTimeout(5000) {
                while (!withContext(Dispatchers.Main) {
                    player.isPlaying && player.currentPosition >= backgroundPosition + 700
                }) delay(100)
            }
            withContext(Dispatchers.Main) {
                assertEquals(2, player.mediaItemCount)
                assertTrue(player.shuffleModeEnabled)
                assertEquals(Player.REPEAT_MODE_ALL, player.repeatMode)
                val order = player.queueRows().map { it.key }
                assertEquals(listOf(0, 1), order.sorted())
                val next = order[(order.indexOf(player.currentMediaItemIndex) + 1) % order.size]
                assertEquals(next, player.nextMediaItemIndex)
                player.pause()
                player.seekTo(1, 4000)
                player.moveMediaItem(1, 0)
            }
            withTimeout(5000) {
                while (
                    !withContext(Dispatchers.Main) {
                        player.currentPosition in 3800..4300 && player.currentMediaItemIndex == 0
                    }
                ) delay(100)
            }
            for (mode in listOf(Player.REPEAT_MODE_ALL, Player.REPEAT_MODE_ONE, Player.REPEAT_MODE_OFF)) {
                withContext(Dispatchers.Main) {
                    player.shuffleModeEnabled = false
                    player.repeatMode = mode
                    player.seekTo(1, 14500)
                    player.play()
                }
                withTimeout(8000) {
                    while (!withContext(Dispatchers.Main) {
                        when (mode) {
                            Player.REPEAT_MODE_OFF -> player.playbackState == Player.STATE_ENDED
                            else -> player.isPlaying && player.currentPosition in 0..3000 &&
                                player.currentMediaItemIndex == (if (mode == Player.REPEAT_MODE_ALL) 0 else 1)
                        }
                    }) delay(100)
                }
            }
            withContext(Dispatchers.Main) {
                player.removeMediaItem(1)
                assertEquals(1, player.mediaItemCount)
            }
        } finally {
            withContext(Dispatchers.Main) {
                player.clearMediaItems()
                MediaController.releaseFuture(future)
            }
            scenario.close()
        }
    }
}
