package org.recloud.music.repository

import android.content.Context
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import coil.imageLoader
import coil.request.ImageRequest
import java.io.ByteArrayOutputStream
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import androidx.room.withTransaction
import java.io.File
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.recloud.music.data.*
import org.recloud.music.database.Library
import org.recloud.music.network.archiveUrl

interface PlaybackProvider {
    suspend fun uri(id: String): String
}

class Collection(private val app: Context, val db: Library) : PlaybackProvider {
    val dao = db.dao()
    private val device =
        app.getSharedPreferences("install", 0).let { p ->
            p.getString("id", null)
                ?: UUID.randomUUID().toString().also { p.edit().putString("id", it).apply() }
        }
    val audioDir
        get() = File(app.filesDir, "audio").apply { mkdirs() }

    fun offline(id: String) = File(audioDir, digest(id))

    override suspend fun uri(id: String): String {
        val file = offline(id)
        if (file.isFile) return Uri.fromFile(file).toString()
        dao.source(id)?.let {
            return it
        }
        val song = dao.song(id)
        require(song != null && song.archived) {
            "Select this audio file on this phone to play it."
        }
        return archiveUrl("download", song.item, song.filename)
    }

    suspend fun import(uri: Uri) =
        withContext(Dispatchers.IO) {
            val resolver = app.contentResolver
            val display =
                resolver
                    .query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                    ?.use { if (it.moveToFirst()) it.getString(0) else null }
                    .orEmpty()
            val meta = MediaMetadataRetriever()
            try {
                meta.setDataSource(app, uri)
                fun field(key: Int) = meta.extractMetadata(key).orEmpty()
                val title =
                    field(MediaMetadataRetriever.METADATA_KEY_TITLE).ifBlank {
                        display.ifBlank { "Audio file" }
                    }
                val artist = field(MediaMetadataRetriever.METADATA_KEY_ARTIST)
                val album = field(MediaMetadataRetriever.METADATA_KEY_ALBUM)
                val duration =
                    field(MediaMetadataRetriever.METADATA_KEY_DURATION).toLongOrNull() ?: 0
                // Stable across devices and file providers, including files with identical tags.
                val hash = java.security.MessageDigest.getInstance("SHA-256")
                requireNotNull(resolver.openInputStream(uri)).use { input ->
                    val buf = ByteArray(65536)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        hash.update(buf, 0, n)
                    }
                }
                val id = "local-" + hash.digest().joinToString("") { "%02x".format(it) }
                val art =
                    meta.embeddedPicture
                        ?.takeIf { it.size <= 8 * 1024 * 1024 }
                        ?.let { bytes ->
                            val path = File(File(app.filesDir, "covers").apply { mkdirs() }, id)
                            android.util.AtomicFile(path).let { target ->
                                val out = target.startWrite()
                                try {
                                    out.write(bytes)
                                    target.finishWrite(out)
                                } catch (e: IOException) {
                                    target.failWrite(out)
                                    throw e
                                }
                            }
                            Uri.fromFile(path).toString()
                        }
                        .orEmpty()
                val song =
                    Song(
                        id,
                        title,
                        artist,
                        album,
                        art = art,
                        duration = duration,
                        bitrate =
                            (field(MediaMetadataRetriever.METADATA_KEY_BITRATE).toIntOrNull()
                                ?: 0) / 1000,
                    )
                db.withTransaction {
                    dao.put(song)
                    dao.put(LocalSource(id, uri.toString()))
                }
                song
            } finally {
                meta.release()
            }
        }

    suspend fun setPlaylistCover(id: String, uri: Uri) = withContext(Dispatchers.IO) {
        val image = app.imageLoader.execute(
            ImageRequest.Builder(app).data(uri).size(512, 512)
                .scale(coil.size.Scale.FIT).precision(coil.size.Precision.EXACT)
                .allowHardware(false).build()
        ).drawable as? BitmapDrawable ?: error("Could not open that image.")
        val jpeg = ByteArrayOutputStream().use {
            check(image.bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it)) { "Could not save cover." }
            it.toByteArray()
        }
        db.withTransaction {
            if (dao.mix(id)?.deleted == false) dao.put(PlaylistCover(id, jpeg))
        }
    }

    suspend fun changeMix(id: String?, name: String? = null, edit: (Mix) -> Mix = { it }) =
        db.withTransaction {
            val mix =
                if (id == null)
                    Mix(UUID.randomUUID().toString(), requireNotNull(name).trim(), edited = 0)
                else dao.mix(id)?.takeUnless { it.deleted } ?: return@withTransaction
            val next = edit(mix)
            if (next.deleted) dao.removeCover(mix.id)
            require(next.name.isNotBlank())
            dao.put(
                next.copy(
                    name = next.name.trim().take(120),
                    edited = maxOf(System.currentTimeMillis(), mix.edited + 1),
                )
            )
        }

    suspend fun member(mix: String, song: Song, add: Boolean) =
        db.withTransaction {
            dao.put(song)
            changeMix(mix) {
                val ids = if (add) (it.ids() + song.id).distinct() else it.ids() - song.id
                it.copy(members = codec.encodeToString(ids))
            }
        }

    suspend fun favorite(song: Song) =
        db.withTransaction {
            dao.put(song)
            val prev = dao.star(song.id)
            dao.put(
                Star(
                    song.id,
                    prev?.enabled != true,
                    maxOf(System.currentTimeMillis(), (prev?.edited ?: 0) + 1),
                )
            )
        }

    suspend fun listened(id: String, ms: Long, start: Boolean, metadata: Song? = null) =
        db.withTransaction {
            if (metadata != null && dao.song(id) == null) dao.put(metadata)
            val old = dao.stat(id, device) ?: Listening(id, device)
            dao.put(
                old.copy(
                    starts = old.starts + if (start) 1 else 0,
                    millis = old.millis + ms,
                    recent = System.currentTimeMillis(),
                )
            )
        }

    suspend fun snapshot() =
        db.withTransaction {
            LibraryPacket(
                songs = dao.allSongs().map { if (it.archived) it else it.copy(art = "") },
                mixes = dao.allMixes(),
                stars = dao.allStars(),
                stats = dao.allStats(),
            )
        }

    suspend fun merge(packet: LibraryPacket) =
        db.withTransaction {
            require(packet.version == 1 && packet.songs.size <= 20000)
            val ids = packet.songs.map { it.id }.toSet()
            packet.songs.forEach {
                require(
                    it.title.length <= 2000 &&
                        (it.id.matches(Regex("local-[a-f0-9]{64}")) ||
                            it.archived ||
                            it.youtubeId != null)
                )
                val safe =
                    it.copy(art = if (it.archived) archiveUrl("services", "img", it.item) else "")
                if (dao.song(it.id) == null) dao.put(safe)
            }
            packet.mixes.forEach { next ->
                require(
                    next.id.length in 1..120 &&
                        next.name.length in 1..120 &&
                        next.ids().all { it in ids }
                )
                val old = dao.mix(next.id)
                if (
                    old == null ||
                        newer(
                            next.edited,
                            old.edited,
                            codec.encodeToString(next),
                            codec.encodeToString(old),
                        )
                ) {
                    dao.put(next)
                    if (next.deleted) dao.removeCover(next.id)
                }
            }
            packet.stars.forEach {
                require(it.song in ids)
                val old = dao.star(it.song)
                if (
                    old == null ||
                        newer(it.edited, old.edited, it.enabled.toString(), old.enabled.toString())
                )
                    dao.put(it)
            }
            packet.stats.forEach {
                require(
                    it.song in ids && it.device.length in 1..120 && it.starts >= 0 && it.millis >= 0
                )
                val old = dao.stat(it.song, it.device)
                dao.put(if (old == null) it else mergeCounts(old, it))
            }
        }
}

fun newer(a: Long, b: Long, aValue: String, bValue: String) = a > b || a == b && aValue > bValue

fun mergeCounts(a: Listening, b: Listening) =
    a.copy(
        starts = maxOf(a.starts, b.starts),
        millis = maxOf(a.millis, b.millis),
        recent = maxOf(a.recent, b.recent),
    )
