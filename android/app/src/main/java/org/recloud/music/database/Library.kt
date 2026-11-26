package org.recloud.music.database

import androidx.room.*
import kotlinx.coroutines.flow.Flow
import org.recloud.music.data.*

@Dao
interface LibraryDao {
    @Query("SELECT * FROM Song ORDER BY title COLLATE NOCASE") fun songs(): Flow<List<Song>>

    @Query("SELECT * FROM Mix WHERE deleted=0 ORDER BY name COLLATE NOCASE")
    fun mixes(): Flow<List<Mix>>

    @Query("SELECT * FROM Star WHERE enabled=1") fun stars(): Flow<List<Star>>

    @Query("SELECT * FROM Listening ORDER BY recent DESC") fun stats(): Flow<List<Listening>>

    @Query("SELECT * FROM Download") fun downloads(): Flow<List<Download>>

    @Query("SELECT song FROM HeldDownload ORDER BY rowid") fun heldSongs(): Flow<List<String>>
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun hold(rows: List<HeldDownload>)
    @Query("DELETE FROM HeldDownload WHERE song=:id") suspend fun releaseHeld(id: String)
    @Query("DELETE FROM HeldDownload") suspend fun releaseAllHeld()

    @Query("SELECT jpeg FROM PlaylistCover WHERE playlist=:id") fun cover(id: String): Flow<ByteArray?>
    @Upsert suspend fun put(cover: PlaylistCover)
    @Query("DELETE FROM PlaylistCover WHERE playlist=:id") suspend fun removeCover(id: String)

    @Query("SELECT * FROM SearchTerm ORDER BY used DESC LIMIT 20")
    fun history(): Flow<List<SearchTerm>>

    @Query("SELECT * FROM Song") suspend fun allSongs(): List<Song>

    @Query("SELECT * FROM Mix") suspend fun allMixes(): List<Mix>

    @Query("SELECT * FROM Star") suspend fun allStars(): List<Star>

    @Query("SELECT * FROM Listening") suspend fun allStats(): List<Listening>

    @Query("SELECT * FROM Mix WHERE id=:id") suspend fun mix(id: String): Mix?

    @Query("SELECT * FROM Song WHERE id=:id") suspend fun song(id: String): Song?

    @Query("SELECT uri FROM LocalSource WHERE song=:id") suspend fun source(id: String): String?

    @Query("SELECT * FROM Star WHERE song=:id") suspend fun star(id: String): Star?

    @Query("SELECT * FROM Listening WHERE song=:id AND device=:device")
    suspend fun stat(id: String, device: String): Listening?

    @Upsert suspend fun put(song: Song)

    @Upsert suspend fun put(mix: Mix)

    @Upsert suspend fun put(star: Star)

    @Upsert suspend fun put(stat: Listening)

    @Upsert suspend fun put(source: LocalSource)

    @Upsert suspend fun put(entry: Download)

    @Upsert suspend fun put(term: SearchTerm)

    @Query(
        "DELETE FROM SearchTerm WHERE term NOT IN (SELECT term FROM SearchTerm ORDER BY used DESC LIMIT 20)"
    )
    suspend fun trimHistory()

    @Query("DELETE FROM SearchTerm") suspend fun clearHistory()

    @Query("DELETE FROM Download WHERE song=:id") suspend fun removeDownload(id: String)
}

@Database(
    entities =
        [
            Song::class,
            Mix::class,
            Star::class,
            Listening::class,
            LocalSource::class,
            Download::class,
            HeldDownload::class,
            PlaylistCover::class,
            SearchTerm::class,
        ],
    version = 2,
    autoMigrations = [AutoMigration(from = 1, to = 2)],
)
abstract class Library : RoomDatabase() {
    abstract fun dao(): LibraryDao
}
