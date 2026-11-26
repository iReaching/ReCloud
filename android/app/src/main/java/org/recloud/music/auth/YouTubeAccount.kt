package org.recloud.music.auth

import android.app.Activity
import android.content.Context
import android.content.Intent
import androidx.activity.result.IntentSenderRequest
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.auth.api.identity.ClearTokenRequest
import com.google.android.gms.common.api.Scope
import com.google.android.gms.tasks.Task
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.recloud.music.network.field
import org.recloud.music.network.json
import org.recloud.music.network.VideoEntry
import org.recloud.music.network.YouTubeCatalog

data class YouTubeLink(val title: String, val id: String)

internal data class PlaylistPage(val items: List<Pair<String, VideoEntry>>, val next: String?)

internal class AccountPlaylistReader(private val fetch: suspend (String) -> PlaylistPage) {
    private val itemIds = mutableSetOf<String>()
    private val visited = mutableSetOf<String>()
    private val loaded = mutableListOf<VideoEntry>()
    val entries: List<VideoEntry> get() = loaded
    var next: String? = ""
        private set

    suspend fun loadNext() {
        val cursor = next ?: return
        val page = fetch(cursor)
        check(page.next == null || (page.next != cursor && page.next !in visited)) {
            "YouTube returned a repeated playlist page."
        }
        visited += cursor
        for ((itemId, video) in page.items) {
            // A playlist can contain the same video at two distinct positions.
            if (itemIds.add(itemId)) loaded += video
        }
        next = page.next
    }

    suspend fun loadAll(): List<VideoEntry> {
        while (next != null) loadNext()
        return entries.toList()
    }
}

interface AccountProvider {
    suspend fun playlists(token: String): List<YouTubeLink>
}

class YouTubeAccount(private val client: OkHttpClient) : AccountProvider {
    private fun request(account: android.accounts.Account) = AuthorizationRequest.builder()
        .setAccount(account)
        .setRequestedScopes(listOf(Scope("https://www.googleapis.com/auth/youtube.readonly")))
        .build()

    internal suspend fun restore(context: Context, name: String): String {
        val client = Identity.getAuthorizationClient(context)
        val result = try {
            client.authorize(request(android.accounts.Account(name, "com.google"))).awaitResult()
        } catch (error: com.google.android.gms.common.api.ApiException) {
            throw AccountAccessError(authorizationFailure(error))
        }
        if (result.hasResolution()) throw ConsentRequired()
        return result.accessToken ?: throw ConsentRequired()
    }

    internal suspend fun invalidate(context: Context, token: String) {
        Identity.getAuthorizationClient(context)
            .clearToken(ClearTokenRequest.builder().setToken(token).build()).awaitResult()
    }

    fun authorize(
        activity: Activity,
        account: android.accounts.Account,
        consent: (IntentSenderRequest) -> Unit,
        ready: (String) -> Unit,
        fail: (String) -> Unit,
    ) {
        Identity.getAuthorizationClient(activity)
            .authorize(request(account))
            .addOnSuccessListener {
                val pending = it.pendingIntent
                if (it.hasResolution() && pending != null)
                    consent(IntentSenderRequest.Builder(pending).build())
                else it.accessToken?.let(ready) ?: fail("Google did not grant playlist access.")
            }
            .addOnFailureListener { fail(authorizationFailure(it)) }
    }

    fun finish(
        activity: Activity,
        intent: Intent?,
        ready: (String) -> Unit,
        fail: (String) -> Unit,
    ) {
        if (intent == null) {
            fail("Sign-in canceled.")
            return
        }
        runCatching {
                requireNotNull(
                    Identity.getAuthorizationClient(activity)
                        .getAuthorizationResultFromIntent(intent)
                        .accessToken
                )
            }
            .onSuccess(ready)
            .onFailure { fail(authorizationFailure(it)) }
    }

    override suspend fun playlists(token: String): List<YouTubeLink> {
        val result = mutableListOf<YouTubeLink>()
        var page = ""
        do {
            val url =
                "https://www.googleapis.com/youtube/v3/playlists"
                    .toHttpUrl()
                    .newBuilder()
                    .addQueryParameter("part", "snippet")
                    .addQueryParameter("mine", "true")
                    .addQueryParameter("maxResults", "50")
                    .apply { if (page.isNotBlank()) addQueryParameter("pageToken", page) }
                    .build()
            val root =
                client.json(
                    Request.Builder()
                        .url(url)
                        .header("Authorization", "Bearer $token")
                        .header("Cache-Control", "no-store")
                        .build()
                )
            root.getValue("items").jsonArray.forEach { value ->
                val item = value.jsonObject
                result +=
                    YouTubeLink(
                        item.getValue("snippet").jsonObject.field("title"),
                        item.field("id"),
                    )
            }
            page = root.field("nextPageToken")
        } while (page.isNotEmpty() && result.size < 1000)
        return result
    }

    internal suspend fun playlistPage(id: String, cursor: String, token: String): PlaylistPage {
        val url = "https://www.googleapis.com/youtube/v3/playlistItems".toHttpUrl().newBuilder()
            .addQueryParameter("part", "snippet")
            .addQueryParameter("playlistId", id)
            .addQueryParameter("maxResults", "50")
            .apply { if (cursor.isNotEmpty()) addQueryParameter("pageToken", cursor) }
            .build()
        val root = client.json(Request.Builder().url(url)
            .header("Authorization", "Bearer $token")
            .header("Cache-Control", "no-store").build())
        val items = root.getValue("items").jsonArray.mapNotNull { item ->
            val video = YouTubeCatalog.parseItem(item, playlist = true) ?: return@mapNotNull null
            val itemId = item.jsonObject.field("id").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            itemId to video
        }
        return PlaylistPage(items, root.field("nextPageToken").ifBlank { null })
    }
}

private suspend fun <T> Task<T>.awaitResult(): T = suspendCancellableCoroutine { continuation ->
    addOnSuccessListener { continuation.resume(it) }
    addOnFailureListener { continuation.resumeWithException(it) }
    addOnCanceledListener { continuation.cancel() }
}

internal open class AccountAccessError(message: String) : Exception(message)
internal class ConsentRequired : AccountAccessError("Reconnect your Google account in Settings to grant playlist access.")

private fun authorizationFailure(error: Throwable): String {
    val status = (error as? com.google.android.gms.common.api.ApiException)?.status
    val code = status?.statusCode
    return when (code) {
        8 -> internalAuthorizationError(status.statusMessage.orEmpty())
        10 ->
            "Google rejected this app registration. Check the Android OAuth client package and signing certificate."
        7 -> "Google could not be reached. Check the network and try again."
        16,
        12501 -> "Google authorization was canceled."
        else ->
            "Google could not authorize playlist access (status ${code ?: "unknown"}). Check Play services and the OAuth consent settings."
    }
}

internal fun internalAuthorizationError(detail: String): String {
    val subcode = Regex("""\b\d{4,6}\b""").find(detail)?.value
    val label = if (subcode == null) "8" else "8/$subcode"
    return when {
        detail.contains("console", true) || detail.contains("configuration", true) ->
            "Google reports a project configuration error ($label). Verify this APK certificate and Android OAuth client in Google Cloud."
        detail.contains("network", true) ->
            "Google reports a network error ($label). Reconnect and try again."
        else -> "Google Play services returned an internal error ($label). Retry, then check the signing certificate and OAuth registration."
    }
}
internal fun playlistFailureMessage(error: Exception): String {
    if (error is AccountAccessError) return error.message.orEmpty()
    val failure = error as? org.recloud.music.network.ServiceFailure
        ?: return if (error is java.io.IOException) "Could not reach YouTube. Check the connection and retry playlists."
        else "YouTube returned an unreadable playlist response."
    return when {
        failure.reasons.any { it in setOf("accessNotConfigured", "SERVICE_DISABLED") } -> "Enable YouTube Data API v3 in the OAuth client's Google Cloud project, then retry playlists."
        "youtubeSignupRequired" in failure.reasons -> "Open YouTube with this Google account and create a channel, then retry playlists."
        failure.status == 401 -> "YouTube rejected the access token. Connect your Google account again."
        failure.reasons.any { it.contains("quota", true) || it.contains("rateLimit", true) } -> "YouTube API quota or rate limit reached. Retry later."
        else -> "YouTube playlist request failed (HTTP ${failure.status}${failure.reasons.firstOrNull()?.let { ", $it" }.orEmpty()})."
    }
}
