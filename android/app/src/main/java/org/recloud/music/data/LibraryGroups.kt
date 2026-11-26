package org.recloud.music.data

import java.io.Serializable

internal data class LibraryGroupKey(val artist: String, val album: String?) : Serializable

internal data class LibraryGroup(
    val key: LibraryGroupKey,
    val title: String,
    val artist: String,
    val tracks: List<Song>,
)

internal fun groupLibrarySongs(songs: List<Song>, byAlbum: Boolean): List<LibraryGroup> {
    fun artist(song: Song) = song.artist.trim().ifEmpty { "Unknown artist" }
    fun album(song: Song) = song.album.trim().ifEmpty { "Unknown album" }

    return songs.groupBy { song ->
        LibraryGroupKey(artist(song).lowercase(), if (byAlbum) album(song).lowercase() else null)
    }.map { (key, tracks) ->
        val first = tracks.first()
        LibraryGroup(key, if (byAlbum) album(first) else artist(first), artist(first), tracks)
    }.sortedWith(compareBy({ it.key.album ?: it.key.artist }, { it.key.artist }))
}
