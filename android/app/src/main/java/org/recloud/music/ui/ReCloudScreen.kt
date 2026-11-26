package org.recloud.music.ui

import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.recloud.music.data.*
import org.recloud.music.network.*
import org.recloud.music.viewmodel.MusicModel

@Composable
fun ReCloudScreen(vm: MusicModel) {
    val opts by remember(vm) { vm.app.options.values }.collectAsStateWithLifecycle(emptyMap())
    val dark =
        when (opts["theme"]) {
            "Light" -> false
            "System" -> isSystemInDarkTheme()
            else -> true
        }
    val colors =
        if (dark)
            darkColorScheme(
                primary = Color(0xFFFF6268),
                background = Color(0xFF0F0F0F),
                surface = Color(0xFF171717),
                surfaceVariant = Color(0xFF2A2728),
                primaryContainer = Color(0xFF65272C),
            )
        else
            lightColorScheme(
                primary = Color(0xFFB61732),
                surface = Color.White,
                background = Color.White,
                primaryContainer = Color(0xFFFFDADE),
            )
    val activity = requireNotNull(androidx.activity.compose.LocalActivity.current)
    SideEffect {
        androidx.core.view.WindowCompat.getInsetsController(
                activity.window,
                activity.window.decorView,
            )
            .apply {
                isAppearanceLightStatusBars = !dark
                isAppearanceLightNavigationBars = !dark
            }
    }
    DisposableEffect(opts["awake"]) {
        if (opts["awake"] == "On")
            activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose { activity.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }
    MaterialTheme(colorScheme = colors) {
        var tab by rememberSaveable { mutableStateOf("Home") }
        LaunchedEffect(vm.showVideo) { if (vm.showVideo > 0 && vm.video != null) tab = "Player" }
        val holder = rememberSaveableStateHolder()
        val snack = remember { SnackbarHostState() }
        LaunchedEffect(vm.message) {
            if (vm.message.isNotBlank()) {
                val m = vm.message
                snack.currentSnackbarData?.dismiss()
                snack.showSnackbar(m)
                vm.notice("")
            }
        }
        BackHandler(enabled = tab != "Home" || vm.album != null) {
            if (vm.album != null) vm.browse(null) else tab = "Home"
        }
        val go: (String) -> Unit = {
            vm.browse(null)
            tab = it
        }
        Scaffold(
            snackbarHost = { SnackbarHost(snack) },
            bottomBar = {
                Column {
                    if (tab != "Player" && vm.video == null) MiniPlayer(vm) { go("Player") }
                    NavigationBar(
                        containerColor = MaterialTheme.colorScheme.surface,
                        tonalElevation = 0.dp,
                    ) {
                        listOf(
                                "Home" to "⌂",
                                "Search" to "⌕",
                                "Library" to "▤",
                                "Player" to "♫",
                                "Settings" to "⚙",
                            )
                            .forEach { (name, icon) ->
                                NavigationBarItem(
                                    tab == name,
                                    { go(name) },
                                    colors =
                                        NavigationBarItemDefaults.colors(
                                            selectedIconColor = colors.primary,
                                            selectedTextColor = colors.primary,
                                            indicatorColor = colors.primaryContainer,
                                        ),
                                    icon = { Text(icon, fontSize = 23.sp) },
                                    label = { Text(name, maxLines = 1, fontSize = 10.sp) },
                                )
                            }
                    }
                }
            },
        ) { inset ->
            val speed =
                if (opts["animations"] == "Off") 0
                else
                    when (opts["speed"]) {
                        "Fast" -> 100
                        "Slow" -> 350
                        else -> 200
                    }
            Crossfade(
                tab,
                Modifier.padding(inset),
                animationSpec = tween(if (tab == "Player") 0 else speed),
                label = "destination",
            ) { dest ->
                holder.SaveableStateProvider(dest) {
                    when (dest) {
                        "Home",
                        "Search" ->
                            if (vm.album != null) Album(vm)
                            else if (dest == "Home") AccountPlaylistDestination(vm, "Home") { select ->
                                Home(vm, opts, select)
                            }
                            else Search(vm) { go("Player") }
                        "Library" -> AccountPlaylistDestination(vm, "Library") { select ->
                            LibraryScreen(vm, select)
                        }
                        "Player" -> PlayerScreen(vm, opts)
                        else -> Settings(vm, opts)
                    }
                }
            }
        }
    }
}

@Composable
fun Home(vm: MusicModel, opts: Map<String, String>, selectPlaylist: (String) -> Unit) {
    val songs by remember { vm.dao.songs() }.collectAsStateWithLifecycle(emptyList())
    val stats by remember { vm.dao.stats() }.collectAsStateWithLifecycle(emptyList())
    val recent =
        remember(songs, stats) {
            val map = songs.associateBy { it.id }
            stats.distinctBy { it.song }.mapNotNull { map[it.song] }.take(20)
        }
    val artist = recent.firstOrNull()?.artist.orEmpty()
    val recommendations by
        produceState(emptyList<Release>(), artist) {
            value = emptyList()
            if (artist.isNotBlank())
                try {
                    value =
                        vm.app.catalog
                            .search(artist, Kind.Artists)
                            .filter { r -> recent.none { it.item == r.item } }
                            .take(12)
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (_: Exception) {}
        }
    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        item {
            Text(
                "ReCloud",
                style = MaterialTheme.typography.displaySmall,
                fontWeight = FontWeight.Bold,
            )
        }
        if (recent.isNotEmpty())
            item {
                Heading("Listen again")
                LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(recent) { song ->
                        val size =
                            when (opts["tiles"]) {
                                "Small" -> 84
                                "Large" -> 150
                                else -> 112
                            }
                        Column(Modifier.width(size.dp).clickable { vm.play(listOf(song)) }) {
                            Cover(song.art, Modifier.size(size.dp))
                            Text(song.title, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        item { Shelf("More from $artist", recommendations, click = vm::browse) }
        item { Shelf("Popular on Internet Archive", vm.home, click = vm::browse) }
        if (vm.homeLoading) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
        if (vm.homeError.isNotBlank())
            item {
                Text(vm.homeError)
                TextButton(vm::refreshHome) { Text("Retry") }
            }
        accountPlaylistRows(vm, selectPlaylist)
        if (recent.isEmpty())
            item {
                Heading("Your music")
                Text(
                    "Import audio in Library or open a recording from an album to start your listening history."
                )
            }
    }
}

@Composable
fun Album(vm: MusicModel) {
    val album = vm.album ?: return
    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        item {
            TextButton({ vm.browse(null) }) { Text("‹ Back") }
            Cover(album.art, Modifier.size(150.dp))
            Heading(album.title)
            Text(album.artist)
        }
        if (vm.loadingAlbum) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
        if (vm.albumNote.isNotBlank())
            item {
                Text(vm.albumNote)
                TextButton({ vm.browse(album) }) { Text("Retry") }
            }
        item { PlayActions(vm.albumSongs, vm) }
        item { DownloadActions(vm.albumSongs, vm) }
        itemsIndexed(vm.albumSongs) { i, s -> SongRow(s, vm, { vm.play(vm.albumSongs, i) }) }
    }
}

@Composable
fun Search(vm: MusicModel, player: () -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    var source by rememberSaveable { mutableStateOf("YouTube") }
    var filter by rememberSaveable { mutableStateOf("video") }
    var group by rememberSaveable { mutableStateOf("") }
    val results = vm.videos
    val matches = vm.matches
    val history by remember { vm.dao.history() }.collectAsStateWithLifecycle(emptyList())
    val open = openLink()
    fun submit(q: String = query) {
        query = q
        group = ""
        vm.search(q, source, filter)
    }
    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        item {
            Heading("Search")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("YouTube", "Archive").forEach { s ->
                    FilterChip(
                        source == s,
                        {
                            source = s
                            filter = if (s == "YouTube") "video" else "Songs"
                            submit()
                        },
                        label = { Text(s) },
                    )
                }
            }
            OutlinedTextField(
                query,
                { query = it },
                singleLine = true,
                label = {
                    Text(
                        if (source == "YouTube") "Search or paste a YouTube link"
                        else "Search the music catalog"
                    )
                },
                modifier = Modifier.fillMaxWidth(),
                keyboardOptions =
                    androidx.compose.foundation.text.KeyboardOptions(
                        imeAction = androidx.compose.ui.text.input.ImeAction.Search
                    ),
                keyboardActions =
                    androidx.compose.foundation.text.KeyboardActions(onSearch = { submit() }),
            )
            Row(
                Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                val filters =
                    if (source == "YouTube") listOf("video", "playlist", "channel")
                    else Kind.entries.map { it.name }
                filters.forEach { f ->
                    FilterChip(
                        filter == f,
                        {
                            filter = f
                            submit()
                        },
                        label = { Text(f.replaceFirstChar { it.uppercase() }) },
                    )
                }
            }
            Button({ submit() }, enabled = query.isNotBlank()) { Text("Search") }
        }
        if (vm.busy) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
        if (vm.resultNote.isNotBlank())
            item {
                Text(vm.resultNote)
                if (source == "YouTube" && query.isNotBlank())
                    TextButton({
                        open(
                            "https://www.youtube.com/results?search_query=" +
                                android.net.Uri.encode(query)
                        )
                    }) {
                        Text("Search on YouTube")
                    }
            }
        if (group.isNotBlank())
            item {
                TextButton({ submit() }) { Text("‹ Search results") }
                Heading(group)
            }
        itemsIndexed(results) { index, v ->
            if (v.type == "video")
                SongRow(
                    Song("yt-" + v.id, v.title, v.artist),
                    vm,
                    {
                        val playable = results.filter { it.type == "video" }
                        val start = results.subList(0, index).count { it.type == "video" }
                        vm.openVideoQueue(playable, start)
                        player()
                    },
                )
            else
                Entry(v.title, v.artist) {
                    group = v.title
                    vm.search(query, source, filter, v)
                }
        }
        itemsIndexed(matches) { index, song -> SongRow(song, vm, { vm.play(matches, index) }) }
        if (source == "Archive" && filter == "Artists") {
            val groups = vm.releases.groupBy { it.artist.ifBlank { "Unknown artist" } }
            if (group.isBlank())
                items(groups.keys.toList()) { artist ->
                    Entry(artist, "${groups[artist]?.size} releases") { group = artist }
                }
            else items(groups[group].orEmpty()) { r -> Entry(r.title, r.artist) { vm.browse(r) } }
        } else
            items(vm.releases) { r ->
                Entry(r.title, r.artist) {
                    if (r.video) open(archiveUrl("details", r.item)) else vm.browse(r)
                }
            }
        if (history.isNotEmpty())
            item {
                Heading("Recent searches")
                TextButton({ vm.task { vm.dao.clearHistory() } }) { Text("Clear history") }
            }
        items(history) { term -> Entry(term.term) { submit(term.term) } }
    }
}
