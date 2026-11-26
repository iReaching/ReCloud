package org.recloud.music

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.recloud.music.auth.ConsentRequired
import org.recloud.music.auth.PlaylistSession
import org.recloud.music.network.ServiceFailure

class PlaylistSessionTest {
    @Test
    fun coldSessionRestoresUsingRememberedIdentityAndReusesGrant() = runBlocking {
        val attempts = mutableListOf<String>()
        val session = PlaylistSession("listener", null, { attempts += it; "granted" }, { error("Unexpected invalidation") })
        repeat(2) { assertEquals("granted", session.request { it }) }
        assertEquals(listOf("listener"), attempts)
    }

    @Test
    fun rejectedTokenIsClearedAndRequestRetriedOnce() = runBlocking {
        val invalidated = mutableListOf<String>()
        val attempted = mutableListOf<String>()
        val session = PlaylistSession("listener", "expired", { "renewed" }, { invalidated += it })
        val failure = runCatching {
            session.request {
                attempted += it
                throw ServiceFailure(401, emptyList())
            }
        }.exceptionOrNull()
        assertTrue(failure is ServiceFailure)
        assertEquals(listOf("expired", "renewed"), attempted)
        assertEquals(listOf("expired"), invalidated)
    }

    @Test
    fun consentOrNetworkFailureKeepsRememberedIdentityForRetry() = runBlocking {
        var attempts = 0
        val session = PlaylistSession("listener", null, {
            when (++attempts) {
                1 -> throw ConsentRequired()
                2 -> throw java.io.IOException()
                else -> "renewed"
            }
        }, { error("Unexpected invalidation") })
        assertTrue(runCatching { session.request { it } }.exceptionOrNull() is ConsentRequired)
        assertTrue(runCatching { session.request { it } }.exceptionOrNull() is java.io.IOException)
        assertEquals("renewed", session.request { it })
    }
}
