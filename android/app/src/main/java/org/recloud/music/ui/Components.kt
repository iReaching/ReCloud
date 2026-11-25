package org.recloud.music.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.Player
import androidx.room.withTransaction
import coil.compose.AsyncImage
import org.recloud.music.R
import org.recloud.music.data.*
import org.recloud.music.network.Release
import org.recloud.music.network.archiveUrl
import org.recloud.music.viewmodel.MusicModel

@Composable
fun openLink(): (String) -> Unit {
    val ctx = LocalContext.current
    return { url ->
        runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
            .onFailure {
                android.widget.Toast.makeText(
                        ctx,
                        "No application can open this link.",
                        android.widget.Toast.LENGTH_SHORT,
                    )
                    .show()
            }
    }
}

@Composable
fun Heading(title: String) {
    Text(
        title,
        Modifier.padding(top = 16.dp, bottom = 8.dp),
        style = MaterialTheme.typography.headlineMedium,
        fontWeight = FontWeight.Bold,
    )
}

@Composable
fun Cover(url: String?, modifier: Modifier) {
    val shape = RoundedCornerShape(10.dp)
    Box(
        modifier.clip(shape).background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        if (url.isNullOrBlank())
            Text("♫", fontSize = 32.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        else
            AsyncImage(
                model = url,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
    }
}

@Composable
fun Transport(icon: Int, label: String, selected: Boolean = false, enabled: Boolean = true, click: () -> Unit) {
    IconButton(onClick = click, enabled = enabled) {
        Icon(
            painterResource(icon),
            label,
            Modifier.size(26.dp),
            tint = if (selected && enabled) MaterialTheme.colorScheme.primary else LocalContentColor.current,
        )
    }
}

@Composable
internal fun RepeatButton(mode: Int, enabled: Boolean = true, change: (Int) -> Unit) {
    val next = when (mode) {
        Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
        Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
        else -> Player.REPEAT_MODE_OFF
    }
    val label = when (mode) {
        Player.REPEAT_MODE_ALL -> "Repeating queue. Switch to repeat song"
        Player.REPEAT_MODE_ONE -> "Repeating song. Turn repeat off"
        else -> "Repeat off. Switch to repeat queue"
    }
    Transport(
        if (mode == Player.REPEAT_MODE_ONE) R.drawable.repeatone else R.drawable.repeat,
        label, selected = mode != Player.REPEAT_MODE_OFF, enabled = enabled,
    ) { change(next) }
}

@Composable
fun Entry(title: String, subtitle: String = "", click: () -> Unit) {
    Column(Modifier.fillMaxWidth().clickable(onClick = click).padding(vertical = 12.dp)) {
        Text(title, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
        if (subtitle.isNotBlank())
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
            )
    }
}

@Composable
fun Shelf(title: String, list: List<Release>, size: Int = 132, click: (Release) -> Unit) {
    if (list.isEmpty()) return
    Heading(title)
    LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        items(list) { item ->
            Column(Modifier.width(size.dp).clickable { click(item) }) {
                Cover(item.art, Modifier.size(size.dp))
                Text(
                    item.title,
                    Modifier.padding(top = 5.dp),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    fontWeight = FontWeight.Medium,
                )
                Text(
                    item.artist,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@Composable
fun SongRow(song: Song, vm: MusicModel, play: () -> Unit, remove: (() -> Unit)? = null) {
    var menu by remember(song.id) { mutableStateOf(false) }
    var picker by remember(song.id) { mutableStateOf(false) }
    val open = openLink()
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Row(
            Modifier.weight(1f).clickable(onClick = play).padding(vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Cover(song.art, Modifier.size(48.dp))
            Column(Modifier.padding(start = 12.dp)) {
                Text(
                    song.title,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    fontWeight = FontWeight.Medium,
                )
                if (song.artist.isNotBlank())
                    Text(
                        song.artist,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodySmall,
                    )
            }
        }
        Box {
            TextButton(
                onClick = { menu = true },
                contentPadding = PaddingValues(4.dp),
                modifier = Modifier.width(48.dp),
            ) {
                Text("•••")
            }
            DropdownMenu(menu, { menu = false }) {
                fun action(block: () -> Unit) {
                    menu = false
                    block()
                }
                if (song.youtubeId == null)
                    DropdownMenuItem(
                        { Text("Add to queue") },
                        { action { vm.play(listOf(song), append = true) } },
                    )
                val stars by vm.dao.stars().collectAsStateWithLifecycle(emptyList())
                DropdownMenuItem(
                    {
                        Text(
                            if (stars.any { it.song == song.id }) "Remove favorite" else "Favorite"
                        )
                    },
                    { action { vm.task { vm.store.favorite(song) } } },
                )
                DropdownMenuItem({ Text("Add to playlist") }, { action { picker = true } })
                if (song.archived) {
                    DropdownMenuItem({ Text("Download") }, { action { vm.download(song) } })
                    DropdownMenuItem(
                        { Text("Recording and credits") },
                        { action { open(archiveUrl("details", song.item)) } },
                    )
                    DropdownMenuItem(
                        { Text("Creative Commons license") },
                        { action { open(song.license.replace("http://", "https://")) } },
                    )
                }
                if (remove != null)
                    DropdownMenuItem({ Text("Remove from playlist") }, { action(remove) })
            }
        }
    }
    if (picker) {
        val mixes by vm.dao.mixes().collectAsStateWithLifecycle(emptyList())
        var name by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { picker = false },
            title = { Text("Add to playlist") },
            text = {
                LazyColumn {
                    items(mixes) { mix ->
                        Entry(mix.name) {
                            vm.task { vm.store.member(mix.id, song, true) }
                            picker = false
                        }
                    }
                    item {
                        OutlinedTextField(
                            name,
                            { name = it },
                            label = { Text("New playlist") },
                            singleLine = true,
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = name.isNotBlank(),
                    onClick = {
                        vm.task {
                            val id = java.util.UUID.randomUUID().toString()
                            vm.store.db.withTransaction {
                                vm.dao.put(song)
                                vm.dao.put(
                                    Mix(
                                        id,
                                        name.trim().take(120),
                                        codec.encodeToString(listOf(song.id)),
                                        System.currentTimeMillis(),
                                    )
                                )
                            }
                        }
                        picker = false
                    },
                ) {
                    Text("Create and add")
                }
            },
            dismissButton = { TextButton({ picker = false }) { Text("Close") } },
        )
    }
}

@Composable
fun PlayActions(songs: List<Song>, vm: MusicModel) {
    if (songs.isNotEmpty())
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button({ vm.play(songs) }) { Text("Play all") }
            OutlinedButton({ vm.play(songs, shuffle = true) }) { Text("Shuffle all") }
        }
}

fun time(ms: Long): String {
    val s = ms.coerceAtLeast(0) / 1000
    return "%d:%02d".format(s / 60, s % 60)
}
