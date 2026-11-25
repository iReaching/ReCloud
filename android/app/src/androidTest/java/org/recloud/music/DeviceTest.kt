package org.recloud.music

import android.content.ComponentName
import android.net.Uri
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.room.Room
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
import org.recloud.music.auth.ApiKey
import org.recloud.music.data.*
import org.recloud.music.database.Library
import org.recloud.music.playback.MusicService
import org.recloud.music.playback.media
import org.recloud.music.repository.Collection
import org.recloud.music.settings.Options

@RunWith(AndroidJUnit4::class)
class DeviceTest {
    private val instrument = InstrumentationRegistry.getInstrumentation()
    private val app = instrument.targetContext.applicationContext as ReCloud

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
        val keys = ApiKey(app)
        val previous = keys.read()
        try {
            keys.save("instrumentation-test")
            assertEquals("instrumentation-test", ApiKey(app).read())
        } finally {
            keys.save(previous)
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
            withContext(Dispatchers.Main) {
                assertEquals(2, player.mediaItemCount)
                assertTrue(player.shuffleModeEnabled)
                assertEquals(Player.REPEAT_MODE_ALL, player.repeatMode)
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
