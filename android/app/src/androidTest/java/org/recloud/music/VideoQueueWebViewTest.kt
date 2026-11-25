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
            replaceOrder([0, 51, 1, ...Array.from({length: 49}, (_, i) => i + 2)]);
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
    private fun checkPlayer(entries: List<VideoEntry>, assertions: String) {
        val instrument = InstrumentationRegistry.getInstrumentation()
        val changed = CountDownLatch(1)
        var result = ""
        val script =
            """
                let loads = 0, replays = 0, active, signal;
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
                videoQueuePage(entries),
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
