package org.recloud.music.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.*
import androidx.compose.ui.draw.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.Player
import coil.compose.AsyncImage
import coil.request.ImageRequest
import kotlinx.coroutines.delay
import org.recloud.music.R
import org.recloud.music.viewmodel.MusicModel

@Composable
fun MiniPlayer(vm: MusicModel, expand: () -> Unit) {
    vm.revision
    val p = vm.controller ?: return
    val media = p.currentMediaItem?.mediaMetadata ?: return
    Surface(color = MaterialTheme.colorScheme.surfaceVariant) {
        Row(
            Modifier.fillMaxWidth()
                .clickable(onClick = expand)
                .padding(horizontal = 12.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Cover(media.artworkUri?.toString(), Modifier.size(44.dp))
            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Text(
                    media.title?.toString().orEmpty(),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    fontWeight = FontWeight.Medium,
                )
                Text(
                    media.artist?.toString().orEmpty(),
                    maxLines = 1,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Transport(
                if (p.isPlaying) R.drawable.pause else R.drawable.play,
                if (p.isPlaying) "Pause" else "Play",
                click = vm::toggle,
            )
            Transport(R.drawable.next, "Next") { p.seekToNextMediaItem() }
        }
    }
}

@Composable
fun PlayerScreen(vm: MusicModel, opts: Map<String, String>) {
    vm.revision
    var videoRepeatMode by rememberSaveable { mutableIntStateOf(Player.REPEAT_MODE_OFF) }
    val video = vm.video
    if (video != null) {
        val open = openLink()
        val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
            if (uris.isNotEmpty()) vm.playAudioFiles(uris)
        }
        var position by rememberSaveable(vm.showVideo) { mutableIntStateOf(0) }
        val current = vm.videoQueue.getOrNull(position) ?: video
        Column(Modifier.padding(16.dp).verticalScroll(rememberScrollState())) {
            Heading("Now playing")
            key(vm.showVideo) {
                YouTubePlayer(vm.videoQueue, position, { position = it }, videoRepeatMode, { videoRepeatMode = it }) {
                    Heading(current.title)
                    Text(current.artist)
                }
            }
            OutlinedButton({ picker.launch(arrayOf("audio/*")) }) { Text("Play audio files…") }
            TextButton({ open("https://www.youtube.com/watch?v=" + current.id) }) {
                Text("Open in YouTube")
            }
            TextButton({ vm.video = null }) { Text("Return to audio queue") }
        }
        return
    }
    val p = vm.controller
    val m = p?.currentMediaItem?.mediaMetadata
    var queue by rememberSaveable { mutableStateOf(false) }
    BackHandler(queue) { queue = false }
    if (queue && p != null) {
        LazyColumn(contentPadding = PaddingValues(16.dp)) {
            item {
                TextButton({ queue = false }) { Text("‹ Now playing") }
                Heading("Queue")
            }
            items(p.mediaItemCount) { i ->
                val item = p.getMediaItemAt(i)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f)) {
                        Entry(
                            item.mediaMetadata.title.toString(),
                            if (i == p.currentMediaItemIndex) "Now playing"
                            else item.mediaMetadata.artist.toString(),
                        ) {
                            p.seekTo(i, 0)
                            p.prepare()
                            p.play()
                        }
                    }
                    TextButton({ if (i > 0) p.moveMediaItem(i, i - 1) }, enabled = i > 0) {
                        Text("Up")
                    }
                    TextButton({ p.removeMediaItem(i) }) { Text("Remove") }
                }
            }
        }
        return
    }
    if (p == null || m == null) {
        Column(Modifier.padding(16.dp)) {
            Heading("Now playing")
            Text("Play a song from Home, Search, or Library.")
        }
        return
    }
    var pos by
        remember(p.currentMediaItemIndex) { mutableLongStateOf(p.currentPosition.coerceAtLeast(0)) }
    var drag by remember { mutableStateOf<Float?>(null) }
    val playing = p.isPlaying
    LaunchedEffect(p, playing, p.currentMediaItemIndex, vm.revision) {
        do {
            pos = p.currentPosition.coerceAtLeast(0)
            delay(600)
        } while (playing)
    }
    val duration = p.duration.coerceAtLeast(0)
    val songs by remember { vm.dao.songs() }.collectAsStateWithLifecycle(emptyList())
    val song = songs.find { it.id == p.currentMediaItem?.mediaId }
    val art = m.artworkUri?.toString()
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wide = maxWidth > maxHeight && maxWidth >= 540.dp
        if (opts["art"] != "Off" && !art.isNullOrEmpty()) {
            val bg = ImageRequest.Builder(LocalContext.current).data(art).size(32, 32).build()
            AsyncImage(
                bg,
                null,
                Modifier.fillMaxSize().alpha(.22f).blur(30.dp),
                contentScale = ContentScale.Crop,
            )
        }
        Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
            if (wide) Cover(art, Modifier.weight(.8f).padding(16.dp).aspectRatio(1f))
            Column(
                Modifier.weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(if (wide) 8.dp else 20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    "Now playing",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.align(Alignment.Start).padding(bottom = 16.dp),
                )
                if (!wide) Cover(art, Modifier.widthIn(max = 360.dp).fillMaxWidth().aspectRatio(1f))
                Heading(m.title?.toString().orEmpty())
                Text(
                    m.artist?.toString().orEmpty(),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (opts["bitrate"] == "On" && (song?.bitrate ?: 0) > 0)
                    Text("${song?.bitrate} kbps", style = MaterialTheme.typography.labelSmall)
                Slider(
                    value = drag ?: pos.coerceIn(0, duration).toFloat(),
                    onValueChange = { drag = it },
                    onValueChangeFinished = {
                        drag?.let {
                            pos = it.toLong()
                            p.seekTo(pos)
                        }
                        drag = null
                    },
                    valueRange = 0f..duration.coerceAtLeast(1).toFloat(),
                    enabled = duration > 0,
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(time(drag?.toLong() ?: pos))
                    Text(time(duration))
                }
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Transport(R.drawable.shuffle, "Shuffle", p.shuffleModeEnabled) {
                        p.shuffleModeEnabled = !p.shuffleModeEnabled
                    }
                    Transport(R.drawable.prev, "Previous") { p.seekToPrevious() }
                    Transport(
                        if (playing) R.drawable.pause else R.drawable.play,
                        if (playing) "Pause" else "Play",
                        true,
                        click = vm::toggle,
                    )
                    Transport(R.drawable.next, "Next") { p.seekToNextMediaItem() }
                    RepeatButton(p.repeatMode) { p.repeatMode = it }
                }
                TextButton({ queue = true }) {
                    Icon(
                        androidx.compose.ui.res.painterResource(R.drawable.queue),
                        null,
                        Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("View queue · ${p.mediaItemCount}")
                }
                if (song?.archived == true) {
                    val open = openLink()
                    TextButton({ open(song.license.replace("http://", "https://")) }) {
                        Text("Creative Commons license")
                    }
                }
            }
        }
    }
}
