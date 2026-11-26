package org.recloud.music.playback

import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.media3.common.*
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import kotlinx.coroutines.*
import kotlinx.serialization.Serializable
import org.recloud.music.ReCloud
import org.recloud.music.data.*

fun Song.media(): MediaItem =
    MediaItem.Builder()
        .setMediaId(id)
        .setUri("recloud://track/$id")
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(title)
                .setArtist(artist)
                .setAlbumTitle(album)
                .setArtworkUri(
                    art.takeIf {
                            it.startsWith("https://archive.org/") ||
                                (id.startsWith("local-") && it.startsWith("file:///"))
                        }
                        ?.let(Uri::parse)
                )
                .build()
        )
        .build()

@Serializable
data class QueueState(
    val ids: List<String>,
    val index: Int,
    val position: Long,
    val shuffle: Boolean,
    val repeat: Int,
) {
    fun validIndex() = index.coerceIn(0, (ids.size - 1).coerceAtLeast(0))
}

@androidx.annotation.OptIn(UnstableApi::class)
class MusicService : MediaSessionService(), Player.Listener {
    private val app
        get() = application as ReCloud

    private lateinit var player: ExoPlayer
    private lateinit var session: MediaSession
    private val serviceJob = SupervisorJob()
    private val scope = CoroutineScope(serviceJob + Dispatchers.Main.immediate)
    private val handler = Handler(Looper.getMainLooper())
    private var active: String? = null
    private var counted: String? = null
    private var since = 0L
    private val checkpoint =
        object : Runnable {
            override fun run() {
                account()
                save()
                if (player.isPlaying) handler.postDelayed(this, 10000)
            }
        }

    override fun onCreate() {
        super.onCreate()
        val sources =
            ResolvingDataSource.Factory(DefaultDataSource.Factory(this)) { spec ->
                require(spec.uri.scheme == "recloud")
                val id = requireNotNull(spec.uri.lastPathSegment)
                val uri = runBlocking { app.collection.uri(id) }
                spec.withUri(Uri.parse(uri))
            }
        player =
            ExoPlayer.Builder(this)
                .setWakeMode(C.WAKE_MODE_NETWORK)
                .setMediaSourceFactory(DefaultMediaSourceFactory(sources))
                .build()
                .apply {
                    setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(C.USAGE_MEDIA)
                            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                            .build(),
                        true,
                    )
                    setHandleAudioBecomingNoisy(true)
                }
        session = MediaSession.Builder(this, player).build()
        val saved =
            getSharedPreferences("transport", 0).getString("queue", null)?.let {
                runCatching { codec.decodeFromString<QueueState>(it) }.getOrNull()
            }
        player.addListener(this)
        if (saved != null)
            scope.launch {
                val list = saved.ids.mapNotNull { app.collection.dao.song(it)?.media() }
                if (player.mediaItemCount == 0 && list.isNotEmpty()) {
                    player.setMediaItems(
                        list,
                        saved.validIndex().coerceAtMost(list.lastIndex),
                        saved.position.coerceAtLeast(0),
                    )
                    player.shuffleModeEnabled = saved.shuffle
                    player.repeatMode = saved.repeat.coerceIn(0, 2)
                    // Leave restored media idle until the listener requests playback.
                }
            }
    }

    private fun account() {
        val now = SystemClock.elapsedRealtime()
        val id = active
        if (id != null) {
            val elapsed = (now - since).coerceAtLeast(0)
            scope.launch { app.collection.listened(id, elapsed, false) }
        }
        active = if (player.isPlaying) player.currentMediaItem?.mediaId else null
        since = now
        val playing = active
        if (playing != null && counted != playing) {
            counted = playing
            scope.launch { app.collection.listened(playing, 0, true) }
        }
    }

    private fun save() {
        val state =
            QueueState(
                List(player.mediaItemCount) { player.getMediaItemAt(it).mediaId },
                player.currentMediaItemIndex,
                player.currentPosition,
                player.shuffleModeEnabled,
                player.repeatMode,
            )
        getSharedPreferences("transport", 0)
            .edit()
            .putString("queue", codec.encodeToString(state))
            .apply()
    }

    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
        counted = null
    }

    override fun onEvents(player: Player, events: Player.Events) {
        account()
        handler.removeCallbacks(checkpoint)
        if (player.isPlaying) handler.postDelayed(checkpoint, 10000)
        save()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? =
        if (controllerInfo.packageName == packageName || controllerInfo.isTrusted) session else null

    override fun onDestroy() {
        handler.removeCallbacks(checkpoint)
        account()
        save()
        player.removeListener(this)
        session.release()
        player.release()
        serviceJob.complete()
        super.onDestroy()
    }
}
