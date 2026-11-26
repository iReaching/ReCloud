package org.recloud.music.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.recloud.music.auth.YouTubeLink
import org.recloud.music.auth.playlistFailureMessage
import org.recloud.music.data.Song
import org.recloud.music.network.VideoEntry
import org.recloud.music.viewmodel.MusicModel

@Composable
internal fun AccountPlaylistDestination(
    vm: MusicModel,
    backLabel: String,
    content: @Composable ((String) -> Unit) -> Unit,
) {
    var id by rememberSaveable { mutableStateOf("") }
    var session by rememberSaveable { mutableStateOf("") }
    val parentState = rememberSaveableStateHolder()
    val link =
        if (id.isNotEmpty() && session == vm.accountSession) vm.playlists.find { it.id == id }
        else null
    if (link == null) {
        parentState.SaveableStateProvider("list") {
            content { selected ->
                id = selected
                session = vm.accountSession
            }
        }
    } else
        key(link.id, session) {
            BackHandler { id = "" }
            AccountPlaylist(vm, link, backLabel) { id = "" }
        }
}

internal fun LazyListScope.accountPlaylistRows(vm: MusicModel, select: (String) -> Unit) {
    if (!vm.signedIn) return
    item { Heading("Your YouTube playlists") }
    when {
        vm.playlistsLoading -> item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
        vm.playlistsError.isNotBlank() ->
            item {
                Text(vm.playlistsError)
                TextButton(vm::refreshAccountPlaylists) { Text("Retry account playlists") }
            }
        vm.playlists.isEmpty() -> item { Text("This YouTube account has no playlists.") }
    }
    items(vm.playlists) { link -> Entry(link.title) { select(link.id) } }
}

@Composable
private fun AccountPlaylist(
    vm: MusicModel,
    link: YouTubeLink,
    backLabel: String,
    back: () -> Unit,
) {
    val reader = remember { vm.playlistReader(link.id) }
    val scope = rememberCoroutineScope()
    var entries by remember { mutableStateOf(emptyList<VideoEntry>()) }
    var more by remember { mutableStateOf(true) }
    var busy by remember { mutableStateOf(false) }
    var loadingMore by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    var retry by remember { mutableStateOf<(suspend () -> Unit)?>(null) }

    fun request(atEnd: Boolean, action: suspend () -> Unit) {
        if (busy) return
        busy = true
        loadingMore = atEnd
        error = ""
        retry = null
        scope.launch {
            try {
                action()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = playlistFailureMessage(e)
                retry = action
            } finally {
                entries = reader.entries.toList()
                more = reader.next != null
                busy = false
            }
        }
    }
    val session = vm.accountSession
    fun play(start: Int, shuffle: Boolean) =
        request(false) {
            val queue = reader.loadAll()
            if (session == vm.accountSession && queue.isNotEmpty()) {
                val selected = if (shuffle) queue.indices.random() else start
                vm.openVideoQueue(queue, selected, shuffle, fromAccount = true)
            }
        }
    LaunchedEffect(reader) { request(false, reader::loadNext) }
    LazyColumn(contentPadding = PaddingValues(16.dp)) {
        item {
            TextButton(back) { Text("‹ $backLabel") }
            Heading(link.title)
            if (entries.isNotEmpty())
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button({ play(0, false) }, enabled = !busy) { Text("Play all") }
                    OutlinedButton({ play(0, true) }, enabled = !busy) { Text("Shuffle all") }
                }
            if (!loadingMore)
                PlaylistRequestStatus(busy, error) { retry?.let { request(false, it) } }
            if (!more && entries.isEmpty()) Text("This playlist has no available videos.")
        }
        itemsIndexed(entries) { index, entry ->
            SongRow(Song("yt-" + entry.id, entry.title, entry.artist), vm, { play(index, false) }, fromAccount = true)
        }
        item {
            if (loadingMore) PlaylistRequestStatus(busy, error) { retry?.let { request(true, it) } }
            if (more && !busy && error.isEmpty())
                TextButton({ request(true, reader::loadNext) }) { Text("Load more videos") }
        }
    }
}

@Composable
private fun PlaylistRequestStatus(busy: Boolean, error: String, retry: () -> Unit) {
    if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
    if (error.isNotEmpty()) {
        Text(error)
        TextButton(retry) { Text("Retry") }
    }
}
