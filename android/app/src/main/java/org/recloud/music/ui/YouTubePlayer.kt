package org.recloud.music.ui

import android.annotation.SuppressLint
import android.util.Log
import android.webkit.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.foundation.lazy.LazyColumn
import androidx.media3.common.Player
import kotlin.random.Random
import kotlinx.coroutines.delay
import org.json.JSONArray
import org.recloud.music.R
import org.recloud.music.network.VideoEntry


private data class TransportState(val state: Int, val elapsed: Long, val duration: Long)

@SuppressLint("SetJavaScriptEnabled")
@Composable
internal fun YouTubePlayer(
    queue: List<VideoEntry>,
    position: Int,
    onPosition: (Int) -> Unit,
    repeatMode: Int,
    onRepeatModeChange: (Int) -> Unit,
    details: @Composable () -> Unit,
) {
    if (queue.isEmpty()) return
    val lifecycle by
        LocalLifecycleOwner.current.lifecycle.currentStateFlow.collectAsStateWithLifecycle(
            minActiveState = Lifecycle.State.CREATED
        )
    var attempt by remember { mutableIntStateOf(0) }
    var shuffleSeed by rememberSaveable { mutableStateOf<Int?>(null) }
    val order = remember(queue, shuffleSeed) {
        val indices = queue.indices.toList()
        shuffleSeed?.let { indices.shuffled(Random(it)) } ?: indices
    }
    val playOrderIndex = remember(order, position) { order.indexOf(position) }
    val open = openLink()
    key(attempt, lifecycle.isAtLeast(Lifecycle.State.RESUMED)) {
        var web by remember { mutableStateOf<WebView?>(null) }
        var transport by remember { mutableStateOf<TransportState?>(null) }
        var error by remember { mutableStateOf("") }
        var timedOut by remember { mutableStateOf(false) }
        var showQueue by remember { mutableStateOf(false) }
        var drag by remember(position) { mutableStateOf<Float?>(null) }
        val updateIndex by rememberUpdatedState(onPosition)
        if (lifecycle.isAtLeast(Lifecycle.State.RESUMED)) {
            Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).heightIn(min = 200.dp)) {
                AndroidView(
                    modifier = Modifier.matchParentSize(),
                    factory = { context ->
                        WebView(context).apply {
                            settings.javaScriptEnabled = true
                            settings.domStorageEnabled = true
                            settings.allowFileAccess = false
                            settings.allowContentAccess = false
                            // Compose transport buttons deliver the user's play gesture outside
                            // WebView.
                            settings.mediaPlaybackRequiresUserGesture = false
                            webChromeClient =
                                object : WebChromeClient() {
                                    override fun onReceivedTitle(view: WebView, title: String?) {
                                        if (title?.startsWith("queue:") == true) {
                                            title
                                                .substringAfter(':')
                                                .toIntOrNull()
                                                ?.takeIf { it in queue.indices }
                                            ?.let {
                                                error = ""
                                                updateIndex(it)
                                            }
                                        } else if (title?.startsWith("error:") == true) {
                                            val code =
                                                title.substringAfter(':').toIntOrNull() ?: return
                                            error =
                                                when (code) {
                                                    101,
                                                    150 ->
                                                        "The owner allows playback on YouTube only."
                                                    100 -> "This video is private or unavailable."
                                                    153 ->
                                                        "YouTube could not identify this app's player."
                                                    else ->
                                                        "YouTube could not play this video (error $code)."
                                                }
                                            Log.w("ReCloudPlayer", "YouTube player error $code")
                                        }
                                    }
                                }
                            webViewClient =
                                object : WebViewClient() {
                                    override fun shouldOverrideUrlLoading(
                                        view: WebView,
                                        request: WebResourceRequest,
                                    ): Boolean {
                                        if (!request.isForMainFrame) return false
                                        if (request.url.scheme == "https")
                                            open(request.url.toString())
                                        return true
                                    }

                                    override fun onReceivedError(
                                        view: WebView,
                                        request: WebResourceRequest,
                                        failure: WebResourceError,
                                    ) {
                                        if (
                                            request.isForMainFrame ||
                                                request.url.toString() ==
                                                    "https://www.youtube.com/iframe_api"
                                        ) {
                                            error =
                                                "Could not load the YouTube player. Check your connection and retry."
                                            Log.w(
                                                "ReCloudPlayer",
                                                "WebView load error ${failure.errorCode}",
                                            )
                                        }
                                    }
                                }
                            loadDataWithBaseURL(
                                "https://${context.packageName}/",
                                videoQueuePage(queue, position, order, repeatMode),
                                "text/html",
                                "UTF-8",
                                null,
                            )
                            web = this
                        }
                    },
                    onRelease = {
                        web = null
                        it.stopLoading()
                        it.destroy()
                    },
                )
            }
        }
        LaunchedEffect(web) {
            val view = web ?: return@LaunchedEffect
            while (true) {
                view.evaluateJavascript("window.transportSnapshot ? transportSnapshot() : null") {
                    value ->
                    if (web !== view || value == null || !value.startsWith("["))
                        return@evaluateJavascript
                    val data = JSONArray(value)
                    transport = TransportState(data.optInt(0), data.optLong(1), data.optLong(2))
                }
                delay(500)
            }
        }
        LaunchedEffect(web, transport != null) {
            timedOut = false
            if (web != null && transport == null) {
                delay(15000)
                timedOut = true
            }
        }
        details()
        if (error.isNotEmpty() || timedOut) {
            Text(error.ifEmpty { "YouTube did not finish loading. Check your connection and retry." })
            TextButton({ attempt++ }) { Text("Retry player") }
        }
        val duration = transport?.duration ?: 0L
        val elapsed = transport?.elapsed?.coerceIn(0L, duration) ?: 0L
        val ready = transport != null
        val playing = transport?.state in listOf(1, 3)
        fun command(script: String) {
            web?.evaluateJavascript(script, null)
        }
        LaunchedEffect(web, ready, order) {
            if (ready) command("replaceOrder([${order.joinToString(",")}])")
        }
        LaunchedEffect(web, ready, repeatMode) {
            if (ready) command("repeatMode = $repeatMode")
        }
        val wrap = repeatMode == Player.REPEAT_MODE_ALL && queue.size > 1
        Slider(
            value = drag ?: elapsed.toFloat(),
            onValueChange = { drag = it },
            onValueChangeFinished = {
                drag?.let { command("player.seekTo(${it.toLong()} / 1000, true)") }
                drag = null
            },
            valueRange = 0f..duration.coerceAtLeast(1).toFloat(),
            enabled = ready && duration > 0,
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(time(drag?.toLong() ?: elapsed))
            Text(time(duration))
        }
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Transport(
                R.drawable.shuffle,
                if (shuffleSeed != null) "Turn shuffle off" else "Turn shuffle on",
                shuffleSeed != null,
                ready && queue.size > 1 && playOrderIndex >= 0,
            ) {
                shuffleSeed = if (shuffleSeed == null) Random.nextInt() else null
            }
            Transport(
                icon = R.drawable.prev,
                label = "Previous",
                enabled = ready && (playOrderIndex > 0 || wrap),
            ) {
                command("skip(-1)")
            }
            Transport(
                if (playing) R.drawable.pause else R.drawable.play,
                if (playing) "Pause" else "Play",
                selected = true,
                enabled = ready,
            ) {
                command(if (playing) "player.pauseVideo()" else "player.playVideo()")
            }
            Transport(R.drawable.next, "Next", enabled = ready && (playOrderIndex in 0 until queue.lastIndex || wrap)) {
                command("skip(1)")
            }
            RepeatButton(repeatMode, enabled = ready, change = onRepeatModeChange)
        }
        TextButton(
            onClick = {
                showQueue = !showQueue
            },
            enabled = ready,
        ) {
            Text(if (showQueue) "Hide song queue" else "View song queue")
        }

        if (showQueue) {
            Text("Up next", style = MaterialTheme.typography.titleMedium)

            val upcomingCount = when (repeatMode) {
                Player.REPEAT_MODE_ONE -> 1
                Player.REPEAT_MODE_ALL -> order.size
                else -> order.lastIndex - playOrderIndex
            }
            if (upcomingCount == 0) Text("No more songs in this queue.")
            else LazyColumn(Modifier.fillMaxWidth().heightIn(max = 280.dp)) {
                items(upcomingCount) { offset ->
                    val next = if (repeatMode == Player.REPEAT_MODE_ONE) position
                        else order[(playOrderIndex + 1 + offset) % order.size]
                    val song = queue[next]
                    ListItem(
                        headlineContent = { Text(song.title) },
                        supportingContent = { Text(song.artist) },
                        leadingContent = { Text("${offset + 1}") },
                    )
                }
            }
        }
    }
}

internal fun videoQueuePage(
    queue: List<VideoEntry>,
    position: Int = 0,
    order: List<Int> = queue.indices.toList(),
    repeatMode: Int = Player.REPEAT_MODE_OFF,
): String {
    val ids =
        queue.joinToString(",") {
            require(it.id.matches(Regex("[A-Za-z0-9_-]{11}")))
            "'${it.id}'"
        }
    val index = position.coerceIn(queue.indices)
    return """
        <!doctype html><html><head>
        <meta name="viewport" content="width=device-width, initial-scale=1">
        <title>queue:$index</title>
        <style>html,body{margin:0;background:#000;overflow:hidden}#player{display:block;border:0}</style>
        </head><body><div id="player"></div><script>
        var player, ready = false;
        const videos = [$ids];
        let order = [${order.joinToString(",")}];
        let cursor = order.indexOf($index);
        let repeatMode = $repeatMode;
        let hasPlayed = false;
        function replaceOrder(newOrder) {
          const current = order[cursor];
          order = newOrder;
          cursor = order.indexOf(current);
        }
        function playAt(next) {
          const current = order[cursor];
          hasPlayed = false;
          cursor = next;
          document.title = 'queue:' + order[cursor];
          if (order[cursor] === current) {
            player.seekTo(0, true);
            player.playVideo();
          } else {
            player.loadVideoById(videos[order[cursor]]);
          }
        }
        function skip(delta) {
          let next = cursor + delta;
          if (repeatMode === ${Player.REPEAT_MODE_ALL}) next = (next + order.length) % order.length;
          if (next >= 0 && next < order.length) playAt(next);
        }
        function sizePlayer() {
          const frame = document.getElementById('player');
          if (frame && innerHeight > 0) {
            frame.style.width = innerWidth + 'px';
            frame.style.height = innerHeight + 'px';
          }
        }
        window.addEventListener('resize', sizePlayer);
        if (window.visualViewport) visualViewport.addEventListener('resize', sizePlayer);
        function transportSnapshot() {
          if (!ready) return null;
          return [player.getPlayerState(), Math.round(player.getCurrentTime()*1000) || 0,
                  Math.round(player.getDuration()*1000) || 0];
        }
        function onYouTubeIframeAPIReady() {
          player = new YT.Player('player', {
            width: innerWidth, height: Math.max(200, innerHeight), videoId: '${queue[index].id}',
            playerVars: {playsinline: 1, origin: location.origin},
            events: {
              onReady: function(event) {
                sizePlayer();
                ready = true;
              },
              onStateChange: function(event) {
                if (event.data === YT.PlayerState.PLAYING) hasPlayed = true;
                if (event.data === YT.PlayerState.ENDED && hasPlayed) {
                  hasPlayed = false;
                  if (repeatMode === ${Player.REPEAT_MODE_ONE}) playAt(cursor);
                  else skip(1);
                }
              },
              onError: function(event) {
                hasPlayed = false;
                document.title = 'error:' + event.data;
              }
            }
          });
          sizePlayer();
        }
        </script><script src="https://www.youtube.com/iframe_api"></script></body></html>
    """
        .trimIndent()
}
