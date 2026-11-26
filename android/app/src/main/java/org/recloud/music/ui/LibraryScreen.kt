package org.recloud.music.ui

import androidx.activity.compose.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.recloud.music.data.LibraryGroupKey
import org.recloud.music.data.groupLibrarySongs
import org.recloud.music.viewmodel.MusicModel

private const val RANKING_PREVIEW = 5

@Composable
fun LibraryScreen(vm: MusicModel, selectPlaylist: (String) -> Unit) {
    val songs by remember { vm.dao.songs() }.collectAsStateWithLifecycle(emptyList())
    val mixes by remember { vm.dao.mixes() }.collectAsStateWithLifecycle(emptyList())
    val stars by remember { vm.dao.stars() }.collectAsStateWithLifecycle(emptyList())
    val stats by remember { vm.dao.stats() }.collectAsStateWithLifecycle(emptyList())
    val downloads by remember { vm.dao.downloads() }.collectAsStateWithLifecycle(emptyList())
    val picker =
        rememberLauncherForActivityResult(
            ActivityResultContracts.OpenMultipleDocuments(),
            vm::import,
        )
    var section by rememberSaveable { mutableStateOf("Songs") }
    var selected by rememberSaveable { mutableStateOf("") }
    var ranking by rememberSaveable { mutableStateOf("") }
    var coverTarget by rememberSaveable { mutableStateOf("") }
    val coverPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        val id = coverTarget
        coverTarget = ""
        if (uri != null && id.isNotEmpty()) vm.task { vm.store.setPlaylistCover(id, uri) }
    }
    var selectedGroup by rememberSaveable { mutableStateOf<LibraryGroupKey?>(null) }
    var dialog by rememberSaveable { mutableStateOf("") }
    var name by rememberSaveable { mutableStateOf("") }
    val mix = mixes.find { it.id == selected }
    val grouped = section == "Artists" || section == "Albums"
    val groups = remember(section, songs) {
        if (grouped) groupLibrarySongs(songs, byAlbum = section == "Albums") else emptyList()
    }
    val group = selectedGroup?.let { key -> groups.find { it.key == key } }
    val songMap = remember(songs) { songs.associateBy { it.id } }
    val ranked =
        remember(stats) {
            stats
                .groupBy { it.song }
                .mapValues { (_, v) -> v.sumOf { it.starts } }
                .filterValues { it > 0 }
                .toList()
                .sortedByDescending { it.second }
        }
    val list =
        when {
            mix != null -> mix.ids().mapNotNull(songMap::get)
            group != null -> group.tracks
            section == "Favorites" -> stars.mapNotNull { songMap[it.song] }
            section == "Recents" -> stats.distinctBy { it.song }.mapNotNull { songMap[it.song] }
            section == "Offline" -> downloads.mapNotNull { songMap[it.song] }
            section == "Songs" -> songs
            else -> emptyList()
        }
    fun backToSection() {
        selected = ""
        selectedGroup = null
        ranking = ""
    }
    val inDetail = mix != null || group != null || ranking.isNotEmpty()
    BackHandler(inDetail) { backToSection() }
    val overviewScroll = key(section) { rememberLazyListState() }
    LazyColumn(
        state = if (inDetail) key(selected, selectedGroup, ranking) { rememberLazyListState() } else overviewScroll,
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        item {
            if (inDetail) TextButton({ backToSection() }) { Text("‹ $section") }
            Heading(mix?.name ?: group?.title ?: ranking.ifEmpty { "Library" })
            if (group != null && section == "Albums") Text(group.artist)
            if (!inDetail) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button({ picker.launch(arrayOf("audio/*")) }) { Text("Import audio") }
                    OutlinedButton({
                        dialog = "Create"
                        name = ""
                    }) {
                        Text("New playlist")
                    }
                }
                Row(
                    Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    listOf("Songs", "Playlists", "Artists", "Albums", "Favorites", "Recents", "Offline", "Stats")
                        .forEach { s ->
                            FilterChip(section == s, { selectedGroup = null; section = s }, label = { Text(s) })
                        }
                }
            } else if (mix != null) {
                val custom by remember(mix.id) { vm.dao.cover(mix.id) }.collectAsStateWithLifecycle(null)
                Cover(custom ?: list.firstOrNull()?.art, Modifier.size(112.dp))
                Row {
                    TextButton({
                        coverTarget = mix.id
                        coverPicker.launch("image/*")
                    }) { Text(if (custom == null) "Choose cover" else "Change cover") }
                    if (custom != null) TextButton({ vm.task { vm.dao.removeCover(mix.id) } }) {
                        Text("Remove cover")
                    }
                }
                Row {
                    TextButton({
                        name = mix.name
                        dialog = "Rename"
                    }) {
                        Text("Rename")
                    }
                    TextButton({ dialog = "Delete" }) { Text("Delete") }
                }
            }
            PlayActions(list, vm)
            if (mix != null || group != null && section == "Albums") DownloadActions(list, vm)
        }
        if (grouped && !inDetail) {
            if (groups.isEmpty()) item { Text("Add music using Import audio or a song menu.") }
            items(groups, key = { it.key }) { entry ->
                val count = entry.tracks.size
                val trackCount = if (count == 1) "1 track" else "$count tracks"
                val subtitle = if (section == "Albums") "${entry.artist} · $trackCount" else trackCount
                Entry(entry.title, subtitle) { selectedGroup = entry.key }
            }
        } else if (section == "Playlists" && mix == null) {
            if (mixes.isEmpty())
                item { Text("Create a playlist, then add tracks from their song menus.") }
            items(mixes, key = { it.id }) { p ->
                val custom by remember(p.id) { vm.dao.cover(p.id) }.collectAsStateWithLifecycle(null)
                val ids = remember(p.members) { p.ids() }
                Row(Modifier.fillMaxWidth().clickable { selected = p.id }.padding(vertical = 6.dp),
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Cover(custom ?: songMap[ids.firstOrNull()]?.art, Modifier.size(52.dp))
                    Column(Modifier.padding(start = 12.dp)) {
                        Text(p.name)
                        Text("${ids.size} tracks", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            accountPlaylistRows(vm, selectPlaylist)
        } else if (section == "Stats" && mix == null) {
            if (ranking.isEmpty()) item {
                Heading("Listening time")
                Text(time(stats.sumOf { it.millis }) + " · " + stats.sumOf { it.starts } + " plays")
                Text("${ranked.size} unique " + if (ranked.size == 1) "song" else "songs")
                Heading("Most played")
            }
            val tracks = when (ranking) {
                "Most played" -> ranked
                "Top artists" -> emptyList()
                else -> ranked.take(RANKING_PREVIEW)
            }
            items(tracks) { (id, count) ->
                songMap[id]?.let { s ->
                    Entry(s.title, "$count plays · ${s.artist}") { vm.play(listOf(s)) }
                }
            }
            if (ranking.isEmpty()) {
                if (ranked.size > RANKING_PREVIEW) item {
                    TextButton({ ranking = "Most played" }) { Text("See all songs") }
                }
                item { Heading("Top artists") }
            }
            val artists =
                ranked
                    .groupBy { songMap[it.first]?.artist.orEmpty() }
                    .mapValues { it.value.sumOf { p -> p.second } }
                    .toList()
                    .sortedByDescending { it.second }
            val visibleArtists = when (ranking) {
                "Top artists" -> artists
                "Most played" -> emptyList()
                else -> artists.take(RANKING_PREVIEW)
            }
            items(visibleArtists) { (artist, count) ->
                Text("${artist.ifBlank{"Unknown artist"}} · $count plays")
            }
            if (ranking.isEmpty() && artists.size > RANKING_PREVIEW) item {
                TextButton({ ranking = "Top artists" }) { Text("See all artists") }
            }
        } else {
            if (list.isEmpty())
                item {
                    Text(
                        when (section) {
                            "Offline" -> "Download a licensed recording from its song menu."
                            "Favorites" -> "Favorite a song from its menu to save it here."
                            "Recents" -> "Your listening history will appear here."
                            else -> "Add music using Import audio or a song menu."
                        }
                    )
                }
            itemsIndexed(list) { i, song ->
                SongRow(
                    song,
                    vm,
                    { vm.play(list, i) },
                    if (mix == null) null
                    else {
                        {
                            vm.task { vm.store.member(mix.id, song, false) }
                            Unit
                        }
                    },
                )
            }
        }
    }
    if (dialog != "")
        AlertDialog(
            onDismissRequest = { dialog = "" },
            title = { Text("$dialog playlist") },
            text = {
                if (dialog == "Delete") Text("Delete “${mix?.name.orEmpty()}”?")
                else
                    OutlinedTextField(
                        name,
                        { name = it },
                        singleLine = true,
                        label = { Text("Playlist name") },
                    )
            },
            confirmButton = {
                TextButton(
                    enabled = dialog == "Delete" || name.isNotBlank(),
                    onClick = {
                        when (dialog) {
                            "Create" -> vm.task { vm.store.changeMix(null, name) }
                            "Rename" ->
                                mix?.let {
                                    vm.task {
                                        vm.store.changeMix(it.id) { p -> p.copy(name = name) }
                                    }
                                }
                            "Delete" ->
                                mix?.let {
                                    vm.task {
                                        vm.store.changeMix(it.id) { p -> p.copy(deleted = true) }
                                    }
                                    selected = ""
                                }
                        }
                        dialog = ""
                    },
                ) {
                    Text(dialog)
                }
            },
            dismissButton = { TextButton({ dialog = "" }) { Text("Cancel") } },
        )
}
