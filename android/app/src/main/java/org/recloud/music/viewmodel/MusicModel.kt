package org.recloud.music.viewmodel

import android.app.Application
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import androidx.compose.runtime.*
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.*
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.room.withTransaction
import androidx.work.WorkManager
import java.util.LinkedHashMap
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.recloud.music.ReCloud
import org.recloud.music.auth.*
import org.recloud.music.data.*
import org.recloud.music.downloads.Offline
import org.recloud.music.network.*
import org.recloud.music.playback.MusicService
import org.recloud.music.playback.VideoListening
import org.recloud.music.playback.media

class MusicModel(application: Application) : AndroidViewModel(application), Player.Listener {
    val app = application as ReCloud
    val store = app.collection
    val dao = store.dao
    val account = YouTubeAccount(app.client)
    val apiKey = EncryptedPreference.apiKey(app)
    val youtube = YouTubeCatalog(app, app.client)
    private val rememberedAccount = EncryptedPreference.googleAccount(app)
    private fun session(name: String, token: String? = null) = PlaylistSession(
        name, token, { account.restore(app, it) }, { account.invalidate(app, it) },
    )
    private var activeAccount by mutableStateOf(rememberedAccount.read().takeIf { it.isNotBlank() }?.let { session(it) })
    val signedIn get() = activeAccount != null
    var reconnectNeeded by mutableStateOf(false)
        private set

    private fun searchKey() =
        apiKey.read().ifBlank { org.recloud.music.BuildConfig.YOUTUBE_API_KEY }

    private suspend fun <T> authorized(current: PlaylistSession, fetch: suspend (String) -> T): T {
        try {
            return current.request(fetch)
        } catch (error: ConsentRequired) {
            if (activeAccount === current) reconnectNeeded = true
            throw error
        }
    }

    internal fun playlistReader(id: String): AccountPlaylistReader {
        val current = checkNotNull(activeAccount)
        return AccountPlaylistReader { cursor ->
            authorized(current) { account.playlistPage(id, cursor, it) }
        }
    }

    var accountSession by mutableStateOf(if (signedIn) java.util.UUID.randomUUID().toString() else "")
        private set
    var playlistsLoading by mutableStateOf(false)
        private set
    var playlistsError by mutableStateOf("")
        private set

    var playlists by mutableStateOf(emptyList<YouTubeLink>())
        private set

    var message by mutableStateOf("")
        private set

    var home by mutableStateOf(emptyList<Release>())
        private set

    var homeError by mutableStateOf("")
        private set

    var homeLoading by mutableStateOf(false)
        private set

    var releases by mutableStateOf(emptyList<Release>())
        private set

    var matches by mutableStateOf(emptyList<Song>())
        private set

    var videos by mutableStateOf(emptyList<VideoEntry>())
        private set

    var busy by mutableStateOf(false)
        private set

    var resultNote by mutableStateOf("")
        private set

    var album by mutableStateOf<Release?>(null)
        private set

    var albumSongs by mutableStateOf(emptyList<Song>())
        private set

    var albumNote by mutableStateOf("")
        private set

    var loadingAlbum by mutableStateOf(false)
        private set

    var controller by mutableStateOf<MediaController?>(null)
        private set

    var revision by mutableIntStateOf(0)
        private set

    val work = WorkManager.getInstance(app).getWorkInfosByTagFlow(Offline.TAG)
    val pendingIds = combine(work, dao.heldSongs()) { jobs, held ->
        held.toSet() + jobs.filterNot { it.state.isFinished }.mapNotNull(Offline::songId)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptySet())
    val downloadedIds = dao.downloads().map { rows -> rows.map { it.song }.toSet() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptySet())
    private val searchCache =
        object : LinkedHashMap<Pair<String, Kind>, List<Release>>(16, 0.75f, true) {
            override fun removeEldestEntry(
                eldest: MutableMap.MutableEntry<Pair<String, Kind>, List<Release>>?
            ) = size > 32
        }
    private var searching: Job? = null
    private var browsing: Job? = null
    private var accountJob: Job? = null
    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    private val future =
        MediaController.Builder(
                app,
                SessionToken(app, ComponentName(app, MusicService::class.java)),
            )
            .buildAsync()

    init {
        future.addListener(
            {
                runCatching { future.get() }
                    .onSuccess {
                        controller = it
                        it.addListener(this)
                    }
                    .onFailure { notice("Could not connect to the music player.") }
            },
            ContextCompat.getMainExecutor(app),
        )
        task { Offline.reconcile(app) }
        refreshAccountPlaylists()
        refreshHome()
    }

    fun notice(text: String) {
        message = text
    }

    fun task(block: suspend () -> Unit) =
        viewModelScope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                notice(e.message?.take(220) ?: "Operation failed. Try again.")
            }
        }

    fun refreshHome() {
        if (homeLoading) return
        task {
            homeLoading = true
            homeError = ""
            try {
                home = app.catalog.search("", Kind.Albums)
            } catch (e: Exception) {
                homeError =
                    "Could not load Internet Archive releases. Check your connection and retry."
            } finally {
                homeLoading = false
            }
        }
    }

    fun search(query: String, source: String, filter: String, parent: VideoEntry? = null) {
        searching?.cancel()
        releases = emptyList()
        matches = emptyList()
        videos = emptyList()
        resultNote = ""
        if (query.isBlank() && parent == null) {
            busy = false
            return
        }
        searching = task {
            busy = true
            try {
                if (parent == null) {
                    dao.put(SearchTerm(query.trim().take(200), System.currentTimeMillis()))
                    dao.trimHistory()
                }
                if (source == "YouTube") {
                    val direct = videoId(query)
                    videos =
                        if (direct != null && parent == null) listOf(youtube.linkedVideo(direct))
                        else activeAccount?.let { current ->
                            authorized(current) { youtube.list(query, filter, parent, "", it) }
                        } ?: youtube.list(query, filter, parent, searchKey(), "")
                } else {
                    val kind = Kind.valueOf(filter)
                    val key = query to kind
                    if (kind == Kind.Songs)
                        matches =
                            dao.allSongs().filter {
                                it.title.contains(query, true) || it.artist.contains(query, true)
                            }
                    val results =
                        searchCache[key]
                            ?: app.catalog.search(query, kind).also { searchCache[key] = it }
                    if (kind == Kind.Songs) {
                        val local = matches
                        val found = coroutineScope {
                            results
                                .take(6)
                                .map { async { app.catalog.tracks(it.item) } }
                                .awaitAll()
                                .flatten()
                        }
                        matches =
                            (local +
                                    found.filter {
                                        it.title.contains(query, true) ||
                                            it.artist.contains(query, true)
                                    })
                                .distinctBy { it.id }
                        releases = results
                    } else releases = results
                }
                if (matches.isEmpty() && releases.isEmpty() && videos.isEmpty())
                    resultNote = "No matches found."
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                resultNote =
                    when {
                        source == "YouTube" && e.message?.contains("401") == true ->
                            "YouTube access expired. Reconnect your Google account in Settings."
                        source == "YouTube" && e.message?.contains("403") == true ->
                            "YouTube denied this request. Check the API key, app restrictions, and project quota."
                        else -> e.message?.take(200) ?: "Search failed. Try again."
                    }
            } finally {
                if (currentCoroutineContext().isActive) busy = false
            }
        }
    }

    fun browse(release: Release?) {
        browsing?.cancel()
        album = release
        albumSongs = emptyList()
        albumNote = ""
        if (release == null) {
            loadingAlbum = false
            return
        }
        browsing = task {
            loadingAlbum = true
            try {
                albumSongs = app.catalog.tracks(release.item)
                if (albumSongs.isEmpty())
                    albumNote = "This release has no playable, licensed audio files."
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                albumNote = "Could not load this release. Check your connection."
            } finally {
                if (currentCoroutineContext().isActive) loadingAlbum = false
            }
        }
    }

    fun access(name: String, value: String) {
        rememberedAccount.save(name)
        if (activeAccount?.name != name) {
            searching?.cancel()
            busy = false
            videos = emptyList()
            clearAccountQueue()
            accountSession = java.util.UUID.randomUUID().toString()
            playlists = emptyList()
        }
        activeAccount = session(name, value)
        reconnectNeeded = false
        notice("Google account connected.")
        refreshAccountPlaylists()
    }

    fun refreshAccountPlaylists() {
        val current = activeAccount ?: return
        accountJob?.cancel()
        playlistsLoading = true
        playlistsError = ""
        accountJob = task {
            try {
                playlists = authorized(current, account::playlists)
                reconnectNeeded = false
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                playlistsError = playlistFailureMessage(e)
                if (e is ServiceFailure && e.status == 401 && "youtubeSignupRequired" !in e.reasons)
                    reconnectNeeded = true
            } finally {
                if (currentCoroutineContext().isActive) playlistsLoading = false
            }
        }
    }

    fun signOut() {
        rememberedAccount.save("")
        clearAccountQueue()
        activeAccount?.token?.let { previous -> task { account.invalidate(app, previous) } }
        accountJob?.cancel()
        searching?.cancel()
        activeAccount = null
        accountSession = ""
        playlistsLoading = false
        playlistsError = ""
        reconnectNeeded = false
        playlists = emptyList()
        videos = emptyList()
        busy = false
    }

    // Keys identify playlist occurrences and stay stable across edits, including duplicate videos.
    var videoQueue by mutableStateOf(emptyMap<Int, VideoEntry>())
        private set
    var videoOrder by mutableStateOf(emptyList<Int>())
        private set
    private var originalOrder by mutableStateOf<List<Int>?>(null)
    val videoShuffled get() = originalOrder != null
    private var nextVideoKey = 0

    var showVideo by mutableIntStateOf(0)
        private set

    var videoPosition by mutableIntStateOf(0)
        private set
    val video: VideoEntry? get() = videoQueue[videoPosition]

    private var accountQueue = false
    private val videoListening = VideoListening(SystemClock::elapsedRealtime) { entry, ms, start ->
        val song = Song("yt-" + entry.id, entry.title, entry.artist)
        task { store.listened(song.id, ms, start, song) }
    }

    fun videoPlaying(session: Int, key: Int, replay: Boolean) {
        if (session == showVideo) videoQueue[key]?.let { videoListening.play(key, it, replay) }
    }

    fun videoStopped(session: Int) {
        if (session == showVideo) videoListening.stop()
    }

    fun checkpointVideo(session: Int) {
        if (session == showVideo) videoListening.checkpoint()
    }

    private fun clearAccountQueue() {
        if (accountQueue) closeVideo()
    }

    fun closeVideo() {
        videoListening.reset()
        videoQueue = emptyMap()
        videoOrder = emptyList()
        originalOrder = null
        accountQueue = false
    }

    fun updateVideoPosition(session: Int, index: Int) {
        if (session == showVideo && index in videoQueue) videoPosition = index
    }

    fun openVideoQueue(
        entries: List<VideoEntry>,
        start: Int,
        shuffle: Boolean = false,
        fromAccount: Boolean = false,
    ) {
        if (start !in entries.indices) return
        videoListening.reset()
        accountQueue = fromAccount
        videoQueue = entries.indices.associateWith { entries[it] }
        nextVideoKey = entries.size
        val ordered = entries.indices.toList()
        originalOrder = if (shuffle) ordered else null
        videoOrder = if (shuffle) listOf(start) + ordered.filter { it != start }.shuffled() else ordered
        videoPosition = start
        showVideo++
        controller?.pause()
    }

    fun toggleVideoShuffle() {
        val original = originalOrder
        if (original == null) {
            originalOrder = videoOrder
            videoOrder = videoOrder.shuffled()
        } else {
            originalOrder = null
            videoOrder = original
        }
    }

    fun moveVideo(key: Int, target: Int) {
        if (videoShuffled || key == target) return
        val to = videoOrder.indexOf(target)
        if (to < 0 || key !in videoQueue) return
        videoOrder = videoOrder.toMutableList().apply {
            remove(key)
            add(to, key)
        }
    }

    fun removeVideo(key: Int) {
        val at = videoOrder.indexOf(key)
        if (at < 0) return
        if (videoOrder.size == 1) return closeVideo()
        val remaining = videoOrder - key
        if (videoPosition == key) {
            videoListening.stop()
            videoPosition = remaining[at.coerceAtMost(remaining.lastIndex)]
        }
        videoQueue = videoQueue - key
        videoOrder = remaining
        originalOrder = originalOrder?.minus(key)
    }

    fun addToQueue(song: Song, fromAccount: Boolean) {
        val id = song.youtubeId ?: return play(listOf(song), append = true)
        val entry = VideoEntry(id, "video", song.title, song.artist)
        if (videoQueue.isEmpty()) {
            openVideoQueue(listOf(entry), 0, fromAccount = fromAccount)
        } else {
            val key = nextVideoKey++
            videoQueue = videoQueue + (key to entry)
            videoOrder = videoOrder + key
            originalOrder = originalOrder?.plus(key)
            accountQueue = accountQueue || fromAccount
        }
        notice("Added to YouTube queue")
    }

    fun play(list: List<Song>, at: Int = 0, shuffle: Boolean = false, append: Boolean = false) {
        if (list.isEmpty()) return
        val index = if (shuffle) list.indices.random() else at.coerceIn(list.indices)
        val selected = list[index]
        val preceding = list.subList(0, index)
        if (selected.youtubeId != null) {
            val entries = list.mapNotNull { song ->
                song.youtubeId?.let { VideoEntry(it, "video", song.title, song.artist) }
            }
            openVideoQueue(entries, preceding.count { it.youtubeId != null }, shuffle)
            if (entries.size != list.size)
                notice("Playing the YouTube entries in this list.")
            return
        }
        val audio = list.filter { it.youtubeId == null }
        if (audio.size != list.size) notice("Playing the audio entries in this list.")
        val player = controller ?: return notice("Player is connecting. Try again.")
        task {
            store.db.withTransaction { list.forEach { dao.put(it) } }
            val items = audio.map { it.media() }
            if (append) {
                player.addMediaItems(items)
                notice("Added to queue")
            } else {
                closeVideo()
                player.setMediaItems(items, preceding.count { it.youtubeId == null }, 0)
                player.shuffleModeEnabled = shuffle
                player.prepare()
                player.play()
            }
        }
    }

    fun toggle() {
        controller?.let {
            if (it.playWhenReady && it.playbackState != Player.STATE_ENDED) it.pause()
            else {
                if (it.playbackState == Player.STATE_ENDED) it.seekToDefaultPosition(0)
                it.prepare()
                it.play()
            }
        }
    }

    private suspend fun importAudioFiles(uris: List<Uri>): List<Song> {
        val songs = mutableListOf<Song>()
        for (uri in uris) {
            try {
                app.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                songs += store.import(uri)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Other selected documents may still be available.
            }
        }
        return songs
    }

    fun playAudioFiles(uris: List<Uri>) = task {
        val songs = importAudioFiles(uris)
        if (songs.isNotEmpty()) play(songs)
        if (songs.size < uris.size) notice("Could read ${songs.size} of ${uris.size} audio files.")
    }

    fun import(uris: List<Uri>) = task {
        val success = importAudioFiles(uris).size
        notice("Imported $success of ${uris.size} audio files")
    }

    fun download(songs: List<Song>) = task {
        val count = Offline.enqueue(app, songs)
        notice(when (count) {
            0 -> "No new recordings to download."
            1 -> "Queued 1 recording"
            else -> "Queued $count recordings"
        })
    }

    override fun onEvents(player: Player, events: Player.Events) {
        revision++
    }

    override fun onPlayerError(error: PlaybackException) {
        notice("Playback failed. Check your connection or reselect the local audio file.")
    }

    override fun onCleared() {
        controller?.removeListener(this)
        MediaController.releaseFuture(future)
        super.onCleared()
    }
}
