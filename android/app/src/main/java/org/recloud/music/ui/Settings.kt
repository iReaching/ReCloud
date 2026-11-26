package org.recloud.music.ui

import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.imageLoader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.recloud.music.downloads.Offline
import org.recloud.music.sync.PeerSync
import org.recloud.music.viewmodel.MusicModel

@Composable
private fun Choice(label: String, value: String, choices: List<String>, change: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        Entry(label, value) { open = true }
        DropdownMenu(open, { open = false }) {
            choices.forEach { v ->
                DropdownMenuItem(
                    { Text(v) },
                    {
                        open = false
                        change(v)
                    },
                )
            }
        }
    }
}

@OptIn(coil.annotation.ExperimentalCoilApi::class)
@Composable
fun Settings(vm: MusicModel, opts: Map<String, String>) {
    var panel by rememberSaveable { mutableStateOf("") }
    var key by remember { mutableStateOf("") }
    var clear by rememberSaveable { mutableStateOf("") }
    val context = LocalContext.current
    val open = openLink()
    val license =
        remember(panel) {
            if (panel == "License")
                context.assets.open("LICENSE").bufferedReader().use { it.readText() }
            else ""
        }
    val activity = requireNotNull(androidx.activity.compose.LocalActivity.current)
    val importLists =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null)
                vm.task {
                    val count =
                        withContext(Dispatchers.IO) {
                            requireNotNull(context.contentResolver.openInputStream(uri)).use {
                                org.recloud.music.sync.LegacyPlaylists.import(it, vm.store)
                            }
                        }
                    vm.notice("Read $count playlists")
                }
        }
    val exportLists =
        rememberLauncherForActivityResult(
            ActivityResultContracts.CreateDocument("application/json")
        ) { uri ->
            if (uri != null)
                vm.task {
                    val count =
                        withContext(Dispatchers.IO) {
                            requireNotNull(context.contentResolver.openOutputStream(uri, "wt"))
                                .use { org.recloud.music.sync.LegacyPlaylists.export(it, vm.store) }
                        }
                    vm.notice("Exported $count YouTube entries")
                }
        }
    var pendingAccount by rememberSaveable { mutableStateOf("") }
    val consent =
        rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) {
            result ->
            val name = pendingAccount
            pendingAccount = ""
            if (name.isNotEmpty())
                vm.account.finish(activity, result.data, { vm.access(name, it) }, vm::notice)
        }
    val chooseAccount =
        rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result
            ->
            val name = result.data?.getStringExtra(android.accounts.AccountManager.KEY_ACCOUNT_NAME)
            if (result.resultCode == android.app.Activity.RESULT_OK && !name.isNullOrBlank()) {
                vm.account.authorize(
                    activity,
                    android.accounts.Account(name, "com.google"),
                    { pendingAccount = name; consent.launch(it) },
                    { vm.access(name, it) },
                    vm::notice,
                )
            } else vm.notice("Google account selection canceled.")
        }
    val download by remember { vm.dao.downloads() }.collectAsStateWithLifecycle(emptyList())
    val songs by remember { vm.dao.songs() }.collectAsStateWithLifecycle(emptyList())
    val work by vm.work.collectAsStateWithLifecycle(emptyList())
    val held by remember { vm.dao.heldSongs() }.collectAsStateWithLifecycle(emptyList())
    val sync = remember { PeerSync(context.applicationContext, vm.store) }
    DisposableEffect(sync) { onDispose { sync.close() } }
    var pending by remember { mutableStateOf("") }
    val permission =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { allowed ->
            if (allowed) {
                if (pending == "Host") sync.host() else sync.discover()
            } else vm.notice("Allow nearby network access to find and pair phones.")
        }
    fun pair(action: String) {
        pending = action
        if (Build.VERSION.SDK_INT >= 37)
            permission.launch("android.permission.ACCESS_LOCAL_NETWORK")
        else if (action == "Host") sync.host() else sync.discover()
    }
    val state by sync.state.collectAsStateWithLifecycle()
    androidx.activity.compose.BackHandler(panel != "") {
        sync.stop()
        panel = ""
    }
    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        if (panel != "")
            item {
                TextButton({
                    sync.stop()
                    panel = ""
                }) {
                    Text("‹ Settings")
                }
            }
        item { Heading(panel.ifBlank { "Settings" }) }
        if (panel == "License") {
            item {
                Text("ReCloud Android · Recilia/ReCloud and iReaching/ReCloud")
                TextButton({ open("https://github.com/iReaching/ReCloud") }) {
                    Text("Project source")
                }
            }
            items(license.split("\n\n")) { paragraph ->
                Text(paragraph, style = MaterialTheme.typography.bodySmall)
            }
        } else if (panel == "Downloads") {
            val paused = opts[Offline.PAUSED] == "true"
            val pending = work.filterNot { it.state.isFinished }
            val pendingIds = pending.mapNotNull(Offline::songId).toSet()
            val hasPending = held.isNotEmpty() || pending.isNotEmpty()
            item {
                Text("${download.sumOf{it.bytes}/1048576} MB stored")
                Choice(
                    "Offline storage limit",
                    opts["storage"] ?: "512",
                    listOf("128", "512", "1024", "4096"),
                ) {
                    vm.task { vm.app.options.set("storage", it) }
                }
                if (hasPending || paused) {
                    TextButton({ vm.task { Offline.setPaused(vm.app, !paused) } }) {
                        Text(if (paused) "Resume downloads" else "Pause downloads")
                    }
                    if (paused) Text("Downloads paused. Interrupted recordings restart from the beginning.")
                }
                if (hasPending) TextButton({ clear = "pending" }) { Text("Clear pending downloads") }
            }
            items(held.filterNot { it in pendingIds }) { id ->
                Text(songs.find { it.id == id }?.title ?: "Recording")
                Text(if (paused) "Paused" else "Queued")
                TextButton({ vm.task { Offline.cancel(vm.app, id) } }) { Text("Cancel transfer") }
            }
            val active =
                work.filter {
                    !it.state.isFinished || (it.state == androidx.work.WorkInfo.State.FAILED &&
                        Offline.songId(it) !in held && Offline.songId(it) !in pendingIds)
                }
            items(active) { job ->
                val id = Offline.songId(job)
                val total = job.progress.getLong("total", -1)
                val bytes = job.progress.getLong("bytes", 0)
                Text(songs.find { it.id == id }?.title ?: "Recording")
                Text(
                    job.outputData.getString("error")
                        ?: "${job.state.name.lowercase()} · ${bytes/1024} KB" +
                            if (total > 0) " / ${total/1024} KB" else ""
                )
                if (!job.state.isFinished)
                    TextButton({ if (id != null) vm.task { Offline.cancel(vm.app, id) } }) {
                        Text("Cancel transfer")
                    }
                else
                    songs
                        .find { it.id == id }
                        ?.let { s -> TextButton({ vm.download(listOf(s)) }) { Text("Retry") } }
            }
            items(download) { entry ->
                val s = songs.find { it.id == entry.song }
                Entry(s?.title ?: "Recording", "${entry.bytes/1024} KB") {
                    if (s != null) vm.play(listOf(s))
                }
                TextButton({ vm.task { Offline.remove(vm.app, entry.song) } }) {
                    Text("Remove download")
                }
            }
            if (download.isEmpty() && active.isEmpty() && held.isEmpty())
                item { Text("Downloads from licensed recordings appear here.") }
            if (download.isNotEmpty())
                item { TextButton({ clear = "all" }) { Text("Clear offline content") } }
        } else if (panel == "Library sync") {
            item {
                Text(
                    "Connect both Android phones to the same Wi-Fi network. Host on one phone and find it on the other."
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button({ pair("Host") }, enabled = !state.active) { Text("Host") }
                    OutlinedButton({ pair("Find") }, enabled = !state.active) { Text("Find phone") }
                }
                if (state.status.isNotBlank())
                    Text(state.status, Modifier.padding(vertical = 12.dp))
                if (state.code.isNotBlank()) {
                    Text(state.code, style = MaterialTheme.typography.titleMedium)
                    Button(sync::confirm) { Text("Codes match") }
                }
                if (state.active) TextButton({ sync.stop() }) { Text("Cancel sync") }
            }
            items(state.peers) { peer -> Entry(peer.name) { sync.join(peer) } }
        } else {
            item { Heading("Appearance") }
            val options =
                listOf(
                    Triple("theme", "Theme", listOf("Dark", "Light", "System")),
                    Triple("art", "Artwork background", listOf("On", "Off")),
                    Triple("animations", "Animations", listOf("On", "Off")),
                    Triple("speed", "Transition speed", listOf("Normal", "Fast", "Slow")),
                    Triple("tiles", "Recent artwork size", listOf("Medium", "Small", "Large")),
                    Triple("awake", "Keep screen awake", listOf("Off", "On")),
                    Triple("bitrate", "Show audio bitrate", listOf("Off", "On")),
                    Triple(
                        "quality",
                        "Archive audio quality",
                        listOf("Balanced", "Higher", "Smaller"),
                    ),
                )
            items(options) { (k, label, choices) ->
                Choice(label, opts[k] ?: choices.first(), choices) {
                    vm.task { vm.app.options.set(k, it) }
                }
            }
            item {
                Heading("Library")
                Entry("Offline content", "${download.size} recordings") { panel = "Downloads" }
                Entry("Sync with another phone") { panel = "Library sync" }
                Entry("Import iOS playlists", "ReinMusic playlist JSON") {
                    importLists.launch(
                        arrayOf("application/json", "text/plain", "application/octet-stream")
                    )
                }
                Entry("Export iOS playlists", "Playlist names and YouTube entries") {
                    exportLists.launch("ReCloud-playlists.json")
                }
                Entry("Refresh metadata and artwork") {
                    vm.task {
                        withContext(Dispatchers.IO) {
                            vm.app.client.cache?.evictAll()
                            context.imageLoader.diskCache?.clear()
                        }
                        context.imageLoader.memoryCache?.clear()
                        val archived = vm.dao.allSongs().filter { it.archived }.groupBy { it.item }
                        for ((item, existing) in archived) {
                            val fresh = vm.app.catalog.tracks(item).associateBy { it.id }
                            existing.forEach { s -> fresh[s.id]?.let { vm.dao.put(it) } }
                        }
                        vm.refreshHome()
                        vm.notice("Catalog and artwork refreshed.")
                    }
                }
                Entry("License and source", "ReCloud · GPL-3.0-or-later") { panel = "License" }
                Heading("YouTube")
                Entry("Copy OAuth registration details", "Package and signing SHA-1 for this installation") {
                    val clipboard = context.getSystemService(android.content.ClipboardManager::class.java)
                    val details = context.packageName + "\nSHA-1 " + vm.youtube.signingFingerprint()
                    clipboard.setPrimaryClip(android.content.ClipData.newPlainText("Android OAuth registration", details))
                    vm.notice("Copied this installation's OAuth registration details.")
                }
                Entry("YouTube Data API key", "Used for public catalog search") {
                    key = ""
                    panel = "API key"
                }
                if (vm.signedIn) Entry("Refresh my playlists") { vm.refreshAccountPlaylists() }
                if (vm.signedIn)
                    Entry("Sign out") { vm.signOut() }
                if (!vm.signedIn || vm.reconnectNeeded || vm.playlistsError.isNotEmpty())
                    Entry(if (vm.signedIn) "Reconnect Google account" else "Connect Google account", "Read your YouTube playlists") {
                        val services =
                            com.google.android.gms.common.GoogleApiAvailability.getInstance()
                        val status = services.isGooglePlayServicesAvailable(activity)
                        if (status == com.google.android.gms.common.ConnectionResult.SUCCESS) {
                            val intent =
                                android.accounts.AccountManager.newChooseAccountIntent(
                                    null,
                                    null,
                                    arrayOf("com.google"),
                                    null,
                                    null,
                                    null,
                                    null,
                                )
                            chooseAccount.launch(intent)
                        } else if (services.isUserResolvableError(status)) {
                            services.getErrorDialog(activity, status, 0)?.show()
                        } else vm.notice("Google Play services is unavailable on this device.")
                    }
            }
        }
    }
    if (panel == "API key")
        AlertDialog(
            onDismissRequest = { panel = "" },
            title = { Text("YouTube Data API key") },
            text = {
                Column {
                    Text("Use a key from a Google Cloud project with YouTube Data API v3 enabled.")
                    OutlinedTextField(
                        key,
                        { key = it },
                        singleLine = true,
                        label = { Text("API key") },
                        visualTransformation = PasswordVisualTransformation(),
                    )
                }
            },
            confirmButton = {
                TextButton(
                    {
                        vm.task {
                            vm.apiKey.save(key)
                            key = ""
                            vm.notice("API key saved")
                        }
                        panel = ""
                    },
                    enabled = key.isNotBlank(),
                ) {
                    Text("Save")
                }
            },
            dismissButton = {
                TextButton({
                    vm.task {
                        vm.apiKey.save("")
                        vm.notice("API key removed")
                    }
                    key = ""
                    panel = ""
                }) {
                    Text("Remove key")
                }
            },
        )
    if (clear.isNotEmpty())
        AlertDialog(
            onDismissRequest = { clear = "" },
            title = { Text(if (clear == "all") "Clear offline content?" else "Clear pending downloads?") },
            text = { Text(if (clear == "all") "Cancel transfers and remove downloaded recordings from this phone."
                else "Cancel queued and active transfers. Completed recordings stay on this phone.") },
            confirmButton = {
                TextButton({
                    val all = clear == "all"
                    clear = ""
                    vm.task { Offline.clear(vm.app, includeCompleted = all) }
                }) {
                    Text("Clear")
                }
            },
            dismissButton = { TextButton({ clear = "" }) { Text("Cancel") } },
        )
}
