package org.recloud.music

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import org.recloud.music.auth.AccountPlaylistReader
import org.recloud.music.auth.PlaylistPage
import org.recloud.music.auth.YouTubeAccount
import org.recloud.music.network.ServiceFailure
import org.recloud.music.ui.videoQueuePage

class AccountPlaylistTest {
    private fun item(
        position: Int,
        title: String = "Track $position",
        video: Int = position,
    ): String {
        val id = video.toString().padStart(11, '0')
        return """{"id":"item$position","snippet":{"title":"$title","videoOwnerChannelTitle":"Artist","resourceId":{"videoId":"$id"}}}"""
    }

    @Test
    fun retryResumesAtSecondPageAndQueuesFromSelectedOccurrence() = runBlocking {
        val cursors = mutableListOf<String?>()
        val http =
            OkHttpClient.Builder()
                .addInterceptor { chain ->
                    val request = chain.request()
                    val cursor = request.url.queryParameter("pageToken")
                    cursors += cursor
                    check(request.url.encodedPath.endsWith("/playlistItems"))
                    check(request.url.queryParameter("playlistId") == "owned")
                    check(request.header("Authorization") == "Bearer fixture")
                    val rows =
                        if (cursor == null) (1..50).map { item(it) }
                        else
                            listOf(
                                item(50),
                                item(51, video = 1),
                                item(52),
                                item(53, "Private video"),
                                item(54, "Deleted video"),
                            )
                    val token = if (cursor == null) ""","nextPageToken":"next"""" else ""
                    val body = """{"items":[${rows.joinToString(",")}]$token}"""
                    Response.Builder()
                        .request(request)
                        .protocol(Protocol.HTTP_1_1)
                        .code(if (cursors.size == 2) 503 else 200)
                        .message("fixture")
                        .body(body.toResponseBody())
                        .build()
                }
                .build()
        val account = YouTubeAccount(http)
        val reader = AccountPlaylistReader { account.playlistPage("owned", it, "fixture") }
        reader.loadNext()
        assertEquals(50, reader.entries.size)
        assertTrue(runCatching { reader.loadAll() }.exceptionOrNull() is ServiceFailure)
        assertEquals(50, reader.entries.size)
        val all = reader.loadAll()
        assertEquals((1..52).map { "Track $it" }, all.map { it.title })
        assertEquals(all[0].id, all[50].id)
        assertEquals("Artist", all[0].artist)
        assertEquals(all, reader.loadAll())
        val page = videoQueuePage(all.indices.associateWith { all[it] }, position = 50)
        assertTrue(page.contains("50:'${all[50].id}'"))
        assertTrue(page.contains("<title>queue:50</title>"))
        assertEquals(listOf(null, "next", "next"), cursors)
    }

    @Test
    fun emptyPagesAndRepeatedTokensTerminate() = runBlocking {
        val empty = AccountPlaylistReader { PlaylistPage(emptyList(), null) }
        assertTrue(empty.loadAll().isEmpty())
        assertNull(empty.next)
        val repeated = AccountPlaylistReader { PlaylistPage(emptyList(), "same") }
        assertTrue(
            runCatching { repeated.loadAll() }.exceptionOrNull() is IllegalStateException
        )
    }
}
