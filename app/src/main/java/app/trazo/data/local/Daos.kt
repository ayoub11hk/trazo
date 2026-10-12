package app.trazo.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface TripDao {
    @Query("SELECT * FROM trips WHERE deleted = 0 AND status = 'completed' ORDER BY startedAt DESC")
    fun observeCompleted(): Flow<List<TripEntity>>

    @Query("SELECT * FROM trips WHERE deleted = 0 AND status = 'completed' ORDER BY startedAt DESC")
    suspend fun completed(): List<TripEntity>

    @Query("SELECT * FROM trips WHERE id = :id")
    fun observe(id: String): Flow<TripEntity?>

    @Query("SELECT * FROM trips WHERE id = :id")
    suspend fun get(id: String): TripEntity?

    @Query("SELECT * FROM trips WHERE status = 'recording' AND deleted = 0 ORDER BY startedAt DESC LIMIT 1")
    suspend fun recording(): TripEntity?

    @Query("SELECT * FROM trips WHERE status = 'recording' AND deleted = 0 ORDER BY startedAt DESC LIMIT 1")
    fun observeRecording(): Flow<TripEntity?>

    @Upsert
    suspend fun upsert(trip: TripEntity)

    @Query("SELECT * FROM trips WHERE dirty = 1 AND deleted = 0")
    suspend fun dirty(): List<TripEntity>

    @Query("SELECT * FROM trips WHERE deleted = 1")
    suspend fun tombstones(): List<TripEntity>

    @Query("UPDATE trips SET dirty = 0 WHERE id = :id AND updatedAt = :updatedAt")
    suspend fun markClean(id: String, updatedAt: Long)

    @Query("UPDATE trips SET deleted = 1, dirty = 0, updatedAt = :now WHERE id = :id")
    suspend fun markDeleted(id: String, now: Long = System.currentTimeMillis())

    @Query("UPDATE trips SET deleted = 1, dirty = 0 WHERE startedAt < :before AND status = 'completed'")
    suspend fun markDeletedBefore(before: Long): Int

    @Query("DELETE FROM trips WHERE id = :id")
    suspend fun purge(id: String)

    @Query("DELETE FROM trips")
    suspend fun purgeAll()

    @Query("SELECT * FROM trips WHERE deleted = 0 AND status = 'completed' AND startedAt < :t ORDER BY startedAt DESC LIMIT 1")
    suspend fun previous(t: Long): TripEntity?

    @Query("SELECT * FROM trips WHERE deleted = 0 AND status = 'completed' AND startedAt > :t ORDER BY startedAt ASC LIMIT 1")
    suspend fun next(t: Long): TripEntity?

    @Query("SELECT COUNT(*) FROM trips WHERE deleted = 0 AND status = 'completed'")
    fun observeCount(): Flow<Int>
}

@Dao
abstract class PointDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract suspend fun insert(p: PointEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun insertAll(p: List<PointEntity>)

    @Query("SELECT * FROM points WHERE tripId = :tripId ORDER BY seq")
    abstract suspend fun forTrip(tripId: String): List<PointEntity>

    @Query("SELECT * FROM points WHERE tripId = :tripId ORDER BY seq")
    abstract fun observeForTrip(tripId: String): Flow<List<PointEntity>>

    @Query("SELECT * FROM points WHERE tripId = :tripId AND uploaded = 0 ORDER BY seq LIMIT :limit")
    abstract suspend fun pendingUpload(tripId: String, limit: Int): List<PointEntity>

    @Query("SELECT DISTINCT tripId FROM points WHERE uploaded = 0")
    abstract suspend fun tripsWithPending(): List<String>

    @Query("UPDATE points SET uploaded = 1 WHERE tripId = :tripId AND seq IN (:seqs)")
    abstract suspend fun markUploaded(tripId: String, seqs: List<Int>)

    @Query("UPDATE points SET uploaded = 0 WHERE tripId = :tripId")
    abstract suspend fun markAllPending(tripId: String)

    @Query("SELECT COUNT(*) FROM points WHERE uploaded = 0")
    abstract fun observePendingCount(): Flow<Int>

    @Query("DELETE FROM points WHERE tripId = :tripId")
    abstract suspend fun deleteForTrip(tripId: String)

    @Query("DELETE FROM points WHERE tripId = :tripId AND seq > :seq")
    abstract suspend fun deleteAfter(tripId: String, seq: Int)

    @Query("SELECT COUNT(*) FROM points WHERE tripId = :tripId")
    abstract suspend fun count(tripId: String): Int

    /** Accepted fixes of all completed trips, thinned for the heatmap. */
    @Query(
        "SELECT p.lat AS lat, p.lng AS lng FROM points p JOIN trips t ON t.id = p.tripId " +
            "WHERE t.deleted = 0 AND t.status = 'completed' AND t.simulated = 0 AND p.flag IN (0, 4) AND p.seq % :every = 0"
    )
    abstract suspend fun heatmapSample(every: Int): List<LatLngRow>

    @Transaction
    open suspend fun replaceTrip(tripId: String, points: List<PointEntity>) {
        deleteForTrip(tripId)
        insertAll(points)
    }
}

data class LatLngRow(val lat: Double, val lng: Double)

@Dao
interface VehicleDao {
    @Query("SELECT * FROM vehicles WHERE deleted = 0 ORDER BY name COLLATE NOCASE")
    fun observeAll(): Flow<List<VehicleEntity>>

    @Query("SELECT * FROM vehicles WHERE deleted = 0 ORDER BY name COLLATE NOCASE")
    suspend fun all(): List<VehicleEntity>

    @Query("SELECT * FROM vehicles WHERE id = :id")
    suspend fun get(id: String): VehicleEntity?

    @Upsert
    suspend fun upsert(v: VehicleEntity)

    @Query("SELECT * FROM vehicles WHERE dirty = 1 AND deleted = 0")
    suspend fun dirty(): List<VehicleEntity>

    @Query("SELECT * FROM vehicles WHERE deleted = 1")
    suspend fun tombstones(): List<VehicleEntity>

    @Query("UPDATE vehicles SET dirty = 0 WHERE id = :id AND updatedAt = :updatedAt")
    suspend fun markClean(id: String, updatedAt: Long)

    @Query("DELETE FROM vehicles WHERE id = :id")
    suspend fun purge(id: String)

    @Query("DELETE FROM vehicles")
    suspend fun purgeAll()
}

@Dao
interface KeyValueDao {
    @Query("SELECT value FROM kv WHERE `key` = :key")
    suspend fun get(key: String): String?

    @Upsert
    suspend fun put(e: KeyValueEntity)

    @Query("DELETE FROM kv WHERE `key` = :key")
    suspend fun delete(key: String)
}

@Dao
interface FriendCacheDao {
    @Query("SELECT * FROM friend_cache")
    suspend fun all(): List<FriendCacheEntity>

    @Upsert
    suspend fun upsertAll(list: List<FriendCacheEntity>)

    @Query("DELETE FROM friend_cache")
    suspend fun clear()
}
