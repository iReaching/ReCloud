package org.recloud.music

import android.annotation.SuppressLint
import android.view.ViewGroup
import android.webkit.*
import androidx.media3.common.Player
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.recloud.music.network.VideoEntry
import org.recloud.music.ui.videoQueuePage

@RunWith(AndroidJUnit4::class)
class VideoQueueWebViewTest {
    @Test
    fun repeatModesFollowShuffledOrderAndManualSkips() {
        val entries = (1..52).map {
            val videoId = (if (it == 2) 52 else it).toString().padStart(11, '0')
            VideoEntry(videoId, "video", "Track $it")
        }
        checkPlayer(entries, """
            check(document.getElementById('player').clientHeight > 0, 'visible player');
            check(active === videos[0], 'initial video');
            // Two consecutive entries in shuffled order reference the same video.
            replaceQueue(videos, [0, 51, 1, ...Array.from({length: 49}, (_, i) => i + 2)], 0);
            finish();
            check(active === videos[51] && document.title === 'queue:51', 'advance in shuffled order');
            repeatMode = ${Player.REPEAT_MODE_ONE};
            finish();
            check(replays === 1 && document.title === 'queue:51', 'repeat song');
            signal(0);
            check(replays === 1, 'duplicate ended event');
            skip(1);
            check(document.title === 'queue:1', 'manual next during repeat song');
            skip(-1);
            check(document.title === 'queue:51', 'manual previous during repeat song');
            playAt(order.length - 1);
            repeatMode = ${Player.REPEAT_MODE_OFF};
            const loadsBeforeEnd = loads;
            finish();
            check(loads === loadsBeforeEnd && replays === 1, 'end with repeat off');
            repeatMode = ${Player.REPEAT_MODE_ALL};
            finish();
            check(cursor === 0 && active === videos[0], 'wrap queue');
            skip(-1);
            check(cursor === order.length - 1, 'manual wrap');
            repeatMode = ${Player.REPEAT_MODE_ONE};
            finish();
            check(replays === 2 && cursor === order.length - 1, 'repeat last song');
        """)
    }

    @Test
    fun previousAndRepeatReachTracksBeforeTheSelectedDuplicate() {
        val entries = (1..52).map { number ->
            val id = if (number == 51) 1 else number
            VideoEntry(id.toString().padStart(11, '0'), "video", "Track $number")
        }
        checkPlayer(entries, """
            check(active === videos[0] && cursor === 50, 'start at second occurrence');
            skip(-1);
            check(active === videos[49] && cursor === 49, 'previous reaches track 50');
            skip(1);
            check(document.title === 'queue:50', 'next returns to second occurrence');
            finish();
            check(active === videos[51] && cursor === 51, 'advance to track 52');
            repeatMode = ${Player.REPEAT_MODE_ALL};
            finish();
            check(active === videos[0] && cursor === 0, 'wrap to first occurrence');
        """, start = 50)
    }

    @Test
    fun editingOccurrencesKeepsPlaybackAndCanJumpToAnAppendedSong() {
        val entries = listOf(
            VideoEntry("01234567890", "video", "First"),
            VideoEntry("09876543210", "video", "Other"),
            VideoEntry("01234567890", "video", "Second copy"),
        )
        checkPlayer(entries, """
            const expanded = {...videos, 3: '12345678901'};
            replaceQueue(expanded, [2, 1, 3, 0], 2);
            check(cursor === 0 && loads === 0 && replays === 0, 'move current without restarting');
            jumpTo(0);
            check(cursor === 3 && document.title === 'queue:0', 'jump to other copy');
            replaceQueue({1: videos[1], 2: videos[2], 3: videos[3]}, [2, 1, 3], 3);
            check(active === expanded[3] && cues === 1 && loads === 1, 'remove current while paused');
            playbackState = 1;
            replaceQueue({1: videos[1], 2: videos[2]}, [2, 1], 1);
            check(active === expanded[1] && loads === 2, 'continue after removing playing entry');
            jumpTo(3);
            check(loads === 2 && cursor === 1, 'ignore stale row action');
        """, start = 2)
    }

    @Test
    fun historySignalsFollowActualPlayback() {
        checkPlayer(listOf(VideoEntry("01234567890", "video", "Single")), """
            const events = [];
            listeningPort = {postMessage: message => events.push(message)};
            signal(5); signal(3); signal(2);
            check(events.length === 0, 'cue, buffer and pause are not plays');
            active = '09876543210'; signal(1);
            check(events.length === 0, 'unrelated video does not enter queue history');
            active = videos[0];
            signal(1); signal(1); signal(3); signal(1); signal(2);
            check(events.join() === 'play:0,stop,play:0,stop', 'resume after buffer or seek');
            repeatMode = ${Player.REPEAT_MODE_ONE};
            finish(); signal(1);
            check(events.slice(-3).join() === 'play:0,stop,replay:0', 'confirmed repeat');
        """)
    }

    @Test
    fun singleTrackHonorsBothRepeatModes() {
        checkPlayer(listOf(VideoEntry("01234567890", "video", "Single")), """
            repeatMode = ${Player.REPEAT_MODE_ALL};
            finish();
            repeatMode = ${Player.REPEAT_MODE_ONE};
            finish();
            repeatMode = ${Player.REPEAT_MODE_OFF};
            finish();
            check(replays === 2 && loads === 0, 'single-track repeat cycle');
        """)
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun checkPlayer(entries: List<VideoEntry>, assertions: String, start: Int = 0) {
        val instrument = InstrumentationRegistry.getInstrumentation()
        val changed = CountDownLatch(1)
        var result = ""
        val script =
            """
                let loads = 0, replays = 0, cues = 0, playbackState = 2, active, signal;
                function check(ok, label) { if (!ok) throw Error(label); }
                function finish() { signal(1); signal(0); }
                window.YT = {PlayerState: {PLAYING: 1, ENDED: 0}, Player: function(element, config) {
                  const frame = document.createElement('iframe');
                  frame.id = element;
                  document.getElementById(element).replaceWith(frame);
                  document.body.style.height = '0px';
                  active = config.videoId;
                  signal = data => config.events.onStateChange({data, target: this});
                  this.loadVideoById = id => { active = id; loads++; };
                  this.getPlayerState = () => playbackState;
                  this.getVideoUrl = () => 'https://www.youtube.com/watch?v=' + active;
                  this.cueVideoById = id => { active = id; cues++; };
                  this.seekTo = seconds => check(seconds === 0, 'seek to start');
                  this.playVideo = () => replays++;
                  config.events.onReady({target: this});
                }};
                try {
                  onYouTubeIframeAPIReady();
                  check(Math.abs(document.getElementById('player').clientHeight - innerHeight) <= 1, 'frame height');
                  $assertions
                  document.title = 'test:passed';
                } catch (err) { document.title = 'test:' + err.message; }
            """
                .trimIndent()
        lateinit var view: WebView
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        scenario.onActivity { activity ->
            view = WebView(activity)
            activity.setContentView(view, ViewGroup.LayoutParams(800, 450))
            view.settings.javaScriptEnabled = true
            view.webViewClient =
                object : WebViewClient() {
                    override fun shouldInterceptRequest(
                        view: WebView,
                        request: WebResourceRequest,
                    ): WebResourceResponse? {
                        if (request.url.toString() != "https://www.youtube.com/iframe_api")
                            return null
                        return WebResourceResponse(
                            "application/javascript",
                            "utf-8",
                            script.byteInputStream(),
                        )
                    }
                }
            view.webChromeClient =
                object : WebChromeClient() {
                    override fun onReceivedTitle(view: WebView, title: String?) {
                        if (title?.startsWith("test:") == true) {
                            result = title
                            changed.countDown()
                        }
                    }
                }
            view.loadDataWithBaseURL(
                "https://org.recloud.music/",
                videoQueuePage(entries.indices.associateWith { entries[it] }, position = start),
                "text/html",
                "UTF-8",
                null,
            )
        }
        try {
            assertTrue("Timed out waiting for player", changed.await(20, TimeUnit.SECONDS))
            org.junit.Assert.assertEquals("test:passed", result)
        } finally {
            instrument.runOnMainSync {
                (view.parent as? ViewGroup)?.removeView(view)
                view.destroy()
            }
            scenario.close()
        }
    }
}
