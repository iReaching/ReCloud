package org.recloud.music.auth

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.recloud.music.network.ServiceFailure

internal class PlaylistSession(
    val name: String,
    initialToken: String?,
    private val authorize: suspend (String) -> String,
    private val invalidate: suspend (String) -> Unit,
) {
    private val lock = Mutex()
    var token = initialToken
        private set

    private suspend fun credential(rejected: String? = null): String = lock.withLock {
        if (rejected != null && token == rejected) {
            invalidate(rejected)
            token = null
        }
        token ?: authorize(name).also { token = it }
    }

    suspend fun <T> request(fetch: suspend (String) -> T): T {
        val first = credential()
        return try {
            fetch(first)
        } catch (error: ServiceFailure) {
            if (error.status != 401 || "youtubeSignupRequired" in error.reasons) throw error
            fetch(credential(rejected = first))
        }
    }
}
