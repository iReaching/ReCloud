package org.recloud.music.playback

import org.recloud.music.network.VideoEntry

/** One queue session. Only confirmed PLAYING intervals contribute wall-clock time. */
internal class VideoListening(
    private val clock: () -> Long,
    private val record: (VideoEntry, Long, Boolean) -> Unit,
) {
    private var countedKey: Int? = null
    private var playing: VideoEntry? = null
    private var since = 0L

    fun play(key: Int, entry: VideoEntry, replay: Boolean) {
        if (key == countedKey && playing != null && !replay) return
        stop()
        if (replay || key != countedKey) record(entry, 0, true)
        countedKey = key
        playing = entry
        since = clock()
    }

    fun checkpoint() {
        val entry = playing ?: return
        val now = clock()
        if (now > since) record(entry, now - since, false)
        since = now
    }

    fun stop() {
        checkpoint()
        playing = null
    }

    fun reset() {
        stop()
        countedKey = null
    }
}
