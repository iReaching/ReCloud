package org.recloud.music.data

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.net.URI
import java.security.MessageDigest
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

val codec = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
}

fun digest(value: String) =
    MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") {
        "%02x".format(it)
    }

@Serializable
@Entity
data class Song(
    @PrimaryKey val id: String,
    val title: String,
    val artist: String = "",
    val album: String = "",
    val item: String = "",
    val filename: String = "",
    val art: String = "",
    val license: String = "",
    val duration: Long = 0,
    val bitrate: Int = 0,
) {
    val youtubeId
        get() =
            id.removePrefix("yt-").takeIf {
                id.startsWith("yt-") && it.matches(Regex("[A-Za-z0-9_-]{11}"))
            }

    val archived
        get() =
            item.matches(Regex("[A-Za-z0-9._-]{1,240}")) &&
                item !in listOf(".", "..") &&
                filename.isNotBlank() &&
                filename.length <= 512 &&
                filename.none { it == '/' || it == '\\' || it.isISOControl() } &&
                filename !in listOf(".", "..") &&
                allowedLicense(license) &&
                id == archiveId(item, filename)
}

fun archiveId(item: String, file: String) = "ia-" + digest("$item/$file")

fun allowedLicense(value: String): Boolean =
    runCatching {
            val url = URI(value)
            url.scheme in setOf("https", "http") &&
                url.host == "creativecommons.org" &&
                Regex(
                        "/(licenses/(by|by-sa|by-nd|by-nc|by-nc-sa|by-nc-nd)|publicdomain/(zero|mark))/[1-4]\\.0(/[a-zA-Z-]+)?/?"
                    )
                    .matches(url.path)
        }
        .getOrDefault(false)

@Serializable
@Entity
data class Mix(
    @PrimaryKey val id: String,
    val name: String,
    val members: String = "[]",
    val edited: Long,
    val deleted: Boolean = false,
) {
    fun ids(): List<String> = codec.decodeFromString(members)
}

@Serializable
@Entity
data class Star(@PrimaryKey val song: String, val enabled: Boolean, val edited: Long)

@Serializable
@Entity(primaryKeys = ["song", "device"])
data class Listening(
    val song: String,
    val device: String,
    val starts: Long = 0,
    val millis: Long = 0,
    val recent: Long = 0,
)

@Entity data class LocalSource(@PrimaryKey val song: String, val uri: String)

@Entity data class SearchTerm(@PrimaryKey val term: String, val used: Long)

@Entity data class Download(@PrimaryKey val song: String, val bytes: Long)

@Entity data class HeldDownload(@PrimaryKey val song: String)

@Entity data class PlaylistCover(@PrimaryKey val playlist: String, val jpeg: ByteArray)

@Serializable
data class LibraryPacket(
    val version: Int = 1,
    val songs: List<Song>,
    val mixes: List<Mix>,
    val stars: List<Star>,
    val stats: List<Listening>,
)
