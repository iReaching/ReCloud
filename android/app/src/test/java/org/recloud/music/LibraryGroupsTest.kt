package org.recloud.music

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.recloud.music.data.Song
import org.recloud.music.data.groupLibrarySongs

class LibraryGroupsTest {
    private val saved = listOf(
        Song("a", "First", "Luna", "Live"),
        Song("b", "Second", "Nova", "Live"),
        Song("c", "Third", " luna ", " LIVE "),
        Song("d", "Untagged", "  ", ""),
        Song("e", "Untitled release", "Luna", "  "),
    )

    @Test fun artistsCombineAlbumsAndKeepLibraryTrackOrder() {
        val groups = groupLibrarySongs(saved, byAlbum = false)
        assertEquals(listOf("Luna", "Nova", "Unknown artist"), groups.map { it.title })
        assertEquals(listOf("a", "c", "e"), groups.first().tracks.map { it.id })
    }

    @Test fun albumsUseArtistAndTitleIncludingForMissingTags() {
        val groups = groupLibrarySongs(saved, byAlbum = true)
        assertEquals(listOf("Live", "Live", "Unknown album", "Unknown album"), groups.map { it.title })
        assertEquals(listOf("Luna", "Nova", "Luna", "Unknown artist"), groups.map { it.artist })
        assertNotEquals(groups[0].key, groups[1].key)
        assertEquals(listOf("a", "c"), groups.first().tracks.map { it.id })
        assertEquals(saved.size, groups.sumOf { it.tracks.size })
    }
}
