package org.recloud.music

import org.junit.Assert.assertEquals
import org.junit.Test
import org.recloud.music.network.VideoEntry
import org.recloud.music.playback.VideoListening

class VideoListeningTest {
    @Test fun countsConfirmedStartsAndOnlyTimeSpentPlaying() {
        var now = 0L
        val events = mutableListOf<Pair<Long, Boolean>>()
        val track = VideoEntry("01234567890", "video", "Track")
        val history = VideoListening({ now }) { _, ms, start -> events.add(ms to start) }
        history.checkpoint()
        history.stop()
        assertEquals(0, events.size)
        history.play(0, track, false)
        now = 100
        history.play(0, track, false)
        history.checkpoint()
        now = 150
        history.stop()
        now = 10_150
        history.play(0, track, false)
        now += 25
        history.play(0, track, true)     // Repeat song.
        now += 50
        history.play(1, track, true)     // Same video, different queue row.
        now += 75
        history.reset()
        now += 10_000
        history.play(0, track, false)    // New queue, same key.
        assertEquals(4, events.count { it.second })
        assertEquals(300L, events.sumOf { it.first })
    }
}
